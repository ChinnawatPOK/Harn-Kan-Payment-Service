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
