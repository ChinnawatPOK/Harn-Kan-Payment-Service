package com.harnkan.payment.gateway;

import com.harnkan.payment.exception.PaymentGatewayException;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import java.math.BigDecimal;

/** Sandbox only. All creators share one configured Connect destination; funds remain in Stripe. */
public class StripeTransferService {
    private final StripeClient client;
    private final String destination;

    public StripeTransferService(StripeClient client, String apiKey, String destination) {
        if (apiKey == null || !(apiKey.startsWith("sk_test_") || apiKey.startsWith("rk_test_")))
            throw new IllegalArgumentException("Sandbox transfers require a Stripe test key");
        if (destination == null || !destination.matches("acct_[A-Za-z0-9]+"))
            throw new IllegalArgumentException("Set STRIPE_SANDBOX_HOST_ACCOUNT_ID to a test connected account");
        this.client = client;
        this.destination = destination;
    }

    public record Result(String transferId, String destination, String currency, BigDecimal amount) {}

    public Result transfer(String reference, String creatorId, String paymentIntentId, BigDecimal amount) {
        if (reference == null || !reference.matches("[A-Za-z0-9_-]{1,64}")
                || creatorId == null || !creatorId.matches("[A-Za-z0-9_-]{1,64}")
                || paymentIntentId == null || !paymentIntentId.matches("pi_[A-Za-z0-9]+")
                || amount == null || amount.signum() <= 0 || amount.scale() > 2
                || amount.compareTo(new BigDecimal("99999999.99")) > 0)
            throw new IllegalArgumentException("Supply reference, creatorId, paymentIntentId and a positive THB amount with at most 2 decimals");
        long satang = amount.movePointRight(2).longValueExact();
        try {
            var intent = client.v1().paymentIntents().retrieve(paymentIntentId,
                    PaymentIntentRetrieveParams.builder().addExpand("latest_charge.balance_transaction").build());
            if (!Boolean.FALSE.equals(intent.getLivemode()) || !"succeeded".equals(intent.getStatus())
                    || !"thb".equals(intent.getCurrency()) || intent.getAmount() == null || intent.getAmount() != satang
                    || intent.getMetadata() == null || !reference.equals(intent.getMetadata().get("paymentId")))
                throw new PaymentGatewayException();
            var charge = intent.getLatestChargeObject();
            if (charge == null || charge.getId() == null || !Boolean.TRUE.equals(charge.getPaid())
                    || charge.getAmountRefunded() == null || charge.getAmountRefunded() != 0L)
                throw new PaymentGatewayException();
            var balance = charge.getBalanceTransactionObject();
            if (balance == null || balance.getAmount() == null || balance.getAmount() <= 0
                    || !("thb".equals(balance.getCurrency()) || "usd".equals(balance.getCurrency())))
                throw new PaymentGatewayException();
            if ("thb".equals(balance.getCurrency()) && balance.getAmount() != satang)
                throw new PaymentGatewayException();
            // Stripe's source balance transaction supplies both currency and gross minor-unit amount.
            // Never reinterpret the original THB amount as USD or calculate our own exchange rate.
            String transferCurrency = balance.getCurrency();
            long transferMinorAmount = balance.getAmount();
            String group = "payment_" + reference;
            var existing = client.v1().transfers().list(TransferListParams.builder().setTransferGroup(group).setLimit(100L).build());
            for (Transfer transfer : existing.autoPagingIterable())
                return confirmed(transfer, reference, creatorId, charge.getId(), transferCurrency, transferMinorAmount);
            var transfer = client.v1().transfers().create(TransferCreateParams.builder()
                    .setAmount(transferMinorAmount).setCurrency(transferCurrency).setDestination(destination)
                    .setSourceTransaction(charge.getId()).setTransferGroup(group)
                    .putMetadata("paymentId", reference).putMetadata("hostUserId", creatorId)
                    .putMetadata("originalAmount", amount.toPlainString()).putMetadata("originalCurrency", "thb").build(),
                    RequestOptions.builder().setIdempotencyKey("release-" + reference).build());
            return confirmed(transfer, reference, creatorId, charge.getId(), transferCurrency, transferMinorAmount);
        } catch (StripeException failure) {
            // Do not return Stripe request/credential details through REST or gRPC.
            throw new PaymentGatewayException();
        }
    }

    /** Aggregate transfer from the platform's available balance; no single charge can fund the whole deal. */
    public Result transferDeal(String dealId, String creatorId, java.util.List<PayoutPayment> payments, boolean allowCreate) {
        if (dealId == null || !dealId.matches("[A-Za-z0-9_-]{1,32}")
                || creatorId == null || !creatorId.matches("[A-Za-z0-9_-]{1,64}") || payments == null || payments.isEmpty())
            throw new IllegalArgumentException("Valid dealId, creatorId and paid payments are required");
        if (payments.stream().anyMatch(p -> p == null || p.paymentId() == null))
            throw new IllegalArgumentException("Every source payment must have an ID");
        var ordered = payments.stream().sorted(java.util.Comparator.comparing(PayoutPayment::paymentId)).toList();
        var ids = new java.util.HashSet<String>();
        var intents = new java.util.HashSet<String>();
        for (var p : ordered) {
            if (p.paymentId() == null || !p.paymentId().matches("[A-Za-z0-9_-]{1,64}")
                    || p.paymentGatewayRef() == null || !p.paymentGatewayRef().matches("pi_[A-Za-z0-9]+")
                    || p.amount() == null || p.amount().signum() <= 0 || p.amount().scale() > 2
                    || p.amount().compareTo(new BigDecimal("99999999.99")) > 0
                    || !ids.add(p.paymentId()) || !intents.add(p.paymentGatewayRef()))
                throw new IllegalArgumentException("Every source payment must be unique with a valid THB amount and PaymentIntent");
        }
        try {
            long total = 0;
            String currency = null;
            BigDecimal originalTotal = new BigDecimal("0.00");
            var canonical = new StringBuilder();
            for (var p : ordered) {
                var source = settledSource(p);
                if (currency != null && !currency.equals(source.currency())) throw new PaymentGatewayException();
                currency = source.currency();
                total = Math.addExact(total, source.minorAmount());
                originalTotal = originalTotal.add(p.amount());
                canonical.append(p.paymentId()).append('|').append(p.paymentGatewayRef()).append('|')
                        .append(p.amount().setScale(2).toPlainString()).append('|').append(source.chargeId()).append('|')
                        .append(source.currency()).append('|').append(source.minorAmount()).append('\n');
            }
            String hash = fingerprint(canonical.toString());
            String group = "deal_" + dealId;
            var existing = client.v1().transfers().list(TransferListParams.builder().setTransferGroup(group).setLimit(100L).build());
            Transfer recovered = null;
            for (Transfer transfer : existing.autoPagingIterable()) {
                if (recovered != null) throw new PaymentGatewayException();
                recovered = transfer;
            }
            if (recovered != null) return confirmedDeal(recovered, dealId, creatorId, hash, currency, total);
            if (!allowCreate) throw new PaymentGatewayException();
            // Do not aggregate a payment that the old single-payment test/release already transferred.
            for (var p : ordered) {
                var legacy = client.v1().transfers().list(TransferListParams.builder()
                        .setTransferGroup("payment_" + p.paymentId()).setLimit(1L).build());
                if (legacy.autoPagingIterable().iterator().hasNext()) throw new PaymentGatewayException();
            }
            var params = TransferCreateParams.builder()
                    .setAmount(total).setCurrency(currency).setDestination(destination).setTransferGroup(group)
                    .putMetadata("dealId", dealId).putMetadata("hostUserId", creatorId)
                    .putMetadata("paymentsHash", hash).putMetadata("paymentCount", Integer.toString(ordered.size()))
                    .putMetadata("originalAmount", originalTotal.toPlainString()).putMetadata("originalCurrency", "thb").build();
            return createFundedDeal(params, dealId, creatorId, hash, currency, total);
        } catch (StripeException | ArithmeticException failure) { throw new PaymentGatewayException(); }
    }

    private Result createFundedDeal(TransferCreateParams params, String dealId, String creatorId,
            String hash, String currency, long total) throws StripeException {
        String baseKey = "release-deal-" + dealId;
        for (int attempt = 0; attempt < 4; attempt++) {
            // Advance a stable sequence only after a definitive insufficient-funds response.
            // A timeout or an unknown result must always retry its original key.
            String key = attempt == 0 ? baseKey : baseKey + "-funded-" + attempt;
            try {
                var transfer = client.v1().transfers().create(params,
                        RequestOptions.builder().setIdempotencyKey(key).build());
                return confirmedDeal(transfer, dealId, creatorId, hash, currency, total);
            } catch (StripeException failure) {
                org.slf4j.LoggerFactory.getLogger(StripeTransferService.class).warn(
                        "STRIPE_DEAL_TRANSFER_ERROR dealId={} code={} requestId={} attempt={}",
                        dealId, failure.getCode(), failure.getRequestId(), attempt);
                if (attempt == 3 || !Integer.valueOf(400).equals(failure.getStatusCode())
                        || !("balance_insufficient".equals(failure.getCode())
                        || "insufficient_funds".equals(failure.getCode()))) throw failure;
                Transfer recovered = null;
                for (Transfer transfer : client.v1().transfers().list(TransferListParams.builder()
                        .setTransferGroup("deal_" + dealId).setLimit(100L).build()).autoPagingIterable()) {
                    if (recovered != null) throw new PaymentGatewayException();
                    recovered = transfer;
                }
                if (recovered != null) return confirmedDeal(recovered, dealId, creatorId, hash, currency, total);
                var available = client.v1().balance().retrieve().getAvailable();
                long funds = 0;
                if (available != null) for (var balance : available) {
                    if (currency.equals(balance.getCurrency()) && balance.getAmount() != null)
                        funds = Math.addExact(funds, balance.getAmount());
                }
                if (funds < total) throw failure;
            }
        }
        throw new PaymentGatewayException();
    }

    private record Source(String chargeId, String currency, long minorAmount) {}

    private Source settledSource(PayoutPayment payment) throws StripeException {
        var intent = client.v1().paymentIntents().retrieve(payment.paymentGatewayRef(),
                PaymentIntentRetrieveParams.builder().addExpand("latest_charge.balance_transaction").build());
        long satang = payment.amount().movePointRight(2).longValueExact();
        if (!Boolean.FALSE.equals(intent.getLivemode()) || !"succeeded".equals(intent.getStatus())
                || !"thb".equals(intent.getCurrency()) || intent.getAmount() == null || intent.getAmount() != satang
                || intent.getMetadata() == null || !payment.paymentId().equals(intent.getMetadata().get("paymentId")))
            throw new PaymentGatewayException();
        var charge = intent.getLatestChargeObject();
        if (charge == null || charge.getId() == null || !Boolean.TRUE.equals(charge.getPaid())
                || charge.getAmountRefunded() == null || charge.getAmountRefunded() != 0L) throw new PaymentGatewayException();
        var balance = charge.getBalanceTransactionObject();
        if (balance == null || balance.getAmount() == null || balance.getAmount() <= 0
                || !("thb".equals(balance.getCurrency()) || "usd".equals(balance.getCurrency()))
                || ("thb".equals(balance.getCurrency()) && balance.getAmount() != satang)) throw new PaymentGatewayException();
        return new Source(charge.getId(), balance.getCurrency(), balance.getAmount());
    }

    private static String fingerprint(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private Result confirmedDeal(Transfer transfer, String dealId, String creatorId, String hash, String currency, long total) {
        if (transfer.getId() == null || !transfer.getId().startsWith("tr_")
                || !Boolean.FALSE.equals(transfer.getLivemode()) || !Boolean.FALSE.equals(transfer.getReversed())
                || transfer.getSourceTransaction() != null || transfer.getAmount() == null || transfer.getAmount() != total
                || !currency.equals(transfer.getCurrency()) || !destination.equals(transfer.getDestination())
                || !("deal_" + dealId).equals(transfer.getTransferGroup()) || transfer.getMetadata() == null
                || !dealId.equals(transfer.getMetadata().get("dealId")) || !creatorId.equals(transfer.getMetadata().get("hostUserId"))
                || !hash.equals(transfer.getMetadata().get("paymentsHash"))) throw new PaymentGatewayException();
        return new Result(transfer.getId(), destination, currency, BigDecimal.valueOf(total, 2));
    }

    private Result confirmed(Transfer transfer, String reference, String creatorId, String chargeId,
            String currency, long minorAmount) {
        if (transfer.getId() == null || !transfer.getId().startsWith("tr_")
                || !Boolean.FALSE.equals(transfer.getLivemode()) || !Boolean.FALSE.equals(transfer.getReversed())
                || transfer.getAmount() == null || transfer.getAmount() != minorAmount || !currency.equals(transfer.getCurrency())
                || !destination.equals(transfer.getDestination()) || !chargeId.equals(transfer.getSourceTransaction())
                || transfer.getMetadata() == null || !reference.equals(transfer.getMetadata().get("paymentId"))
                || !creatorId.equals(transfer.getMetadata().get("hostUserId"))) throw new PaymentGatewayException();
        return new Result(transfer.getId(), destination, currency, BigDecimal.valueOf(minorAmount, 2));
    }
}
