package com.harnkan.payment.gateway;

import com.harnkan.payment.exception.PaymentGatewayException;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.model.Transfer;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import java.math.BigDecimal;

/** Stripe payments/refunds, with optional sandbox-only Connect transfers to one shared configured test account. */
public class StripePaymentGateway implements PaymentGateway {
    private final StripeClient client;
    private final String payoutMode;
    private final String sandboxAccountId;

    public StripePaymentGateway(StripeClient client) {
        this(client, "ledger", "");
    }

    public StripePaymentGateway(StripeClient client, String payoutMode, String sandboxAccountId) {
        if (!java.util.Set.of("ledger", "sandbox-transfer").contains(payoutMode)) {
            throw new IllegalArgumentException("STRIPE_PAYOUT_MODE must be ledger or sandbox-transfer");
        }
        if ("sandbox-transfer".equals(payoutMode) &&
                !sandboxAccountId.matches("acct_[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("Sandbox transfers require STRIPE_SANDBOX_HOST_ACCOUNT_ID");
        }
        this.client = client;
        this.payoutMode = payoutMode;
        this.sandboxAccountId = sandboxAccountId;
    }

    @Override
    public StartPaymentResult startPayment(String paymentId, BigDecimal amount) {
        try {
            var params = PaymentIntentCreateParams.builder().setCurrency("thb").setAmount(satang(amount))
                    .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                            .setEnabled(true)
                            .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER).build()).putMetadata("paymentId", paymentId).setTransferGroup("payment_" + paymentId).build();
            var intent = client.v1().paymentIntents().create(params, options("pay-" + paymentId));
            if (intent.getId() == null || intent.getClientSecret() == null) throw new PaymentGatewayException();
            return new StartPaymentResult(intent.getId(), intent.getClientSecret());
        } catch (StripeException failure) {
            // Never expose SDK exception text (which can contain request data) through REST/gRPC/logs.
            throw new PaymentGatewayException();
        }
    }

    @Override
    public RefundResult refund(String paymentGatewayRef, BigDecimal amount) {
        try {
            var intent = client.v1().paymentIntents().retrieve(paymentGatewayRef);
            String paymentId = intent.getMetadata() == null ? null : intent.getMetadata().get("paymentId");
            if (paymentId == null || paymentId.isBlank() || !"thb".equals(intent.getCurrency())
                    || intent.getAmount() == null || intent.getAmount() != satang(amount)) throw new PaymentGatewayException();
            // Read current refund status first: an idempotent POST can replay an old 'pending' response.
            var refunds = client.v1().refunds().list(RefundListParams.builder()
                    .setPaymentIntent(paymentGatewayRef).setLimit(100L).build());
            for (Refund refund : refunds.autoPagingIterable()) {
                if (refund.getMetadata() != null && paymentId.equals(refund.getMetadata().get("paymentId"))) {
                    return confirmedRefund(refund, satang(amount));
                }
            }
            var refund = client.v1().refunds().create(RefundCreateParams.builder()
                    .setPaymentIntent(paymentGatewayRef).setAmount(satang(amount))
                    .putMetadata("paymentId", paymentId).build(), options("refund-" + paymentId));
            return confirmedRefund(refund, satang(amount));
        } catch (StripeException failure) {
            throw new PaymentGatewayException();
        }
    }

    private RefundResult confirmedRefund(Refund refund, long amount) {
        if (!"succeeded".equals(refund.getStatus()) || refund.getId() == null
                || refund.getAmount() == null || refund.getAmount() != amount) throw new PaymentGatewayException();
        return new RefundResult(refund.getId());
    }

    @Override
    public GatewayPaymentState paymentState(String reference) {
        try {
            var intent = client.v1().paymentIntents().retrieve(reference);
            if ("requires_payment_method".equals(intent.getStatus()) && intent.getLastPaymentError() != null)
                return GatewayPaymentState.FAILED;
            return switch (intent.getStatus()) {
                case "succeeded" -> GatewayPaymentState.SUCCEEDED;
                case "canceled" -> GatewayPaymentState.FAILED;
                default -> GatewayPaymentState.PENDING;
            };
        } catch (StripeException failure) {
            throw new PaymentGatewayException();
        }
    }

    @Override
    public PayoutResult payout(String creatorId, BigDecimal amount, String reference) {
        if (!"ledger".equals(payoutMode)) throw new PaymentGatewayException();
        return new PayoutResult("fake_payout_" + reference);
    }

    @Override
    public PayoutResult payout(String creatorId, BigDecimal amount, String reference, String paymentGatewayRef) {
        if ("ledger".equals(payoutMode)) return payout(creatorId, amount, reference);
        if (creatorId == null || creatorId.isBlank() || paymentGatewayRef == null || !paymentGatewayRef.startsWith("pi_"))
            throw new PaymentGatewayException();
        try {
            var intent = client.v1().paymentIntents().retrieve(paymentGatewayRef,
                    PaymentIntentRetrieveParams.builder().addExpand("latest_charge.balance_transaction").build());
            if (!Boolean.FALSE.equals(intent.getLivemode()) || !"succeeded".equals(intent.getStatus())
                    || !"thb".equals(intent.getCurrency()) || intent.getAmount() == null
                    || intent.getAmount() != satang(amount) || intent.getMetadata() == null
                    || !reference.equals(intent.getMetadata().get("paymentId"))) throw new PaymentGatewayException();
            var charge = intent.getLatestChargeObject();
            if (charge == null || !Boolean.TRUE.equals(charge.getPaid()) || charge.getAmountRefunded() == null
                    || charge.getAmountRefunded() != 0L) throw new PaymentGatewayException();
            var balance = charge.getBalanceTransactionObject();
            // This integration transfers THB only. Never reinterpret a USD settlement as THB satang.
            if (balance == null || balance.getAmount() == null || !"thb".equals(balance.getCurrency())
                    || balance.getAmount() != satang(amount)) throw new PaymentGatewayException();
            long transferAmount = satang(amount);
            String currency = "thb";
            String group = "payment_" + reference;
            var existing = client.v1().transfers().list(TransferListParams.builder()
                    .setTransferGroup(group).setLimit(100L).build());
            for (Transfer transfer : existing.autoPagingIterable()) {
                // Recover a transfer even after the provider's idempotency-key retention period.
                return confirmedTransfer(transfer, creatorId, reference, charge.getId(), currency, transferAmount);
            }
            var transfer = client.v1().transfers().create(TransferCreateParams.builder()
                    .setAmount(transferAmount).setCurrency(currency).setDestination(sandboxAccountId)
                    .setSourceTransaction(charge.getId()).setTransferGroup(group)
                    .putMetadata("paymentId", reference).putMetadata("hostUserId", creatorId)
                    .putMetadata("originalAmount", amount.toPlainString()).putMetadata("originalCurrency", "thb")
                    .build(), options("release-" + reference));
            return confirmedTransfer(transfer, creatorId, reference, charge.getId(), currency, transferAmount);
        } catch (StripeException failure) {
            throw new PaymentGatewayException();
        }
    }

    private PayoutResult confirmedTransfer(Transfer transfer, String hostId, String reference,
            String chargeId, String currency, long amount) {
        if (transfer.getId() == null || !transfer.getId().startsWith("tr_")
                || !Boolean.FALSE.equals(transfer.getLivemode()) || !Boolean.FALSE.equals(transfer.getReversed())
                || transfer.getAmount() == null || transfer.getAmount() != amount
                || !currency.equals(transfer.getCurrency()) || !sandboxAccountId.equals(transfer.getDestination())
                || !chargeId.equals(transfer.getSourceTransaction()) || transfer.getMetadata() == null
                || !reference.equals(transfer.getMetadata().get("paymentId"))
                || !hostId.equals(transfer.getMetadata().get("hostUserId"))) throw new PaymentGatewayException();
        return new PayoutResult(transfer.getId());
    }

    private static long satang(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    private static RequestOptions options(String key) {
        return RequestOptions.builder().setIdempotencyKey(key).build();
    }
}
