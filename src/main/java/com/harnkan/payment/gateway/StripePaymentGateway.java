package com.harnkan.payment.gateway;

import com.harnkan.payment.exception.PaymentGatewayException;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import java.math.BigDecimal;

public class StripePaymentGateway implements PaymentGateway {
    private final StripeClient client;
    private final StripeTransferService transfers;
    private boolean ledgerPayout;

    public StripePaymentGateway(StripeClient client, StripeTransferService transfers) {
        this.client = client;
        this.transfers = transfers;
    }

    /** Compatibility with the existing deployed Stripe configuration while preserving checkout/reconciliation. */
    public StripePaymentGateway(StripeClient client) {
        this(client, (StripeTransferService) null);
        this.ledgerPayout = true;
    }

    public StripePaymentGateway(StripeClient client, String payoutMode, String destination) {
        this(client, "sandbox-transfer".equals(payoutMode)
                ? new StripeTransferService(client, System.getenv("STRIPE_SECRET_KEY"), destination) : null);
        if (!java.util.Set.of("ledger", "sandbox-transfer").contains(payoutMode))
            throw new IllegalArgumentException("STRIPE_PAYOUT_MODE must be ledger or sandbox-transfer");
        this.ledgerPayout = "ledger".equals(payoutMode);
    }

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
        } catch (StripeException failure) { throw new PaymentGatewayException(); }
    }

    @Override
    public StartPaymentResult startPayment(String paymentId, BigDecimal amount) {
        try {
            var intent = client.v1().paymentIntents().create(PaymentIntentCreateParams.builder()
                    .setCurrency("thb").setAmount(amount.movePointRight(2).longValueExact())
                    .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                            .setEnabled(true).setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER).build())
                    .putMetadata("paymentId", paymentId).setTransferGroup("payment_" + paymentId).build(),
                    RequestOptions.builder().setIdempotencyKey("pay-" + paymentId).build());
            if (intent.getId() == null || intent.getClientSecret() == null) throw new PaymentGatewayException();
            return new StartPaymentResult(intent.getId(), intent.getClientSecret());
        } catch (StripeException failure) { throw new PaymentGatewayException(); }
    }

    @Override
    public RefundResult refund(String paymentGatewayRef, BigDecimal amount) {
        try {
            var intent = client.v1().paymentIntents().retrieve(paymentGatewayRef);
            String paymentId = intent.getMetadata() == null ? null : intent.getMetadata().get("paymentId");
            long satang = amount.movePointRight(2).longValueExact();
            if (paymentId == null || paymentId.isBlank() || !"thb".equals(intent.getCurrency())
                    || intent.getAmount() == null || intent.getAmount() != satang) throw new PaymentGatewayException();
            for (Refund refund : client.v1().refunds().list(RefundListParams.builder()
                    .setPaymentIntent(paymentGatewayRef).setLimit(100L).build()).autoPagingIterable()) {
                if (refund.getMetadata() != null && paymentId.equals(refund.getMetadata().get("paymentId")))
                    return confirmedRefund(refund, satang);
            }
            return confirmedRefund(client.v1().refunds().create(RefundCreateParams.builder()
                    .setPaymentIntent(paymentGatewayRef).setAmount(satang).putMetadata("paymentId", paymentId).build(),
                    RequestOptions.builder().setIdempotencyKey("refund-" + paymentId).build()), satang);
        } catch (StripeException failure) { throw new PaymentGatewayException(); }
    }

    private RefundResult confirmedRefund(Refund refund, long satang) {
        if (refund.getId() == null || !"succeeded".equals(refund.getStatus())
                || refund.getAmount() == null || refund.getAmount() != satang) throw new PaymentGatewayException();
        return new RefundResult(refund.getId());
    }

    @Override
    public PayoutResult payout(String creatorId, BigDecimal amount, String reference) {
        if (ledgerPayout) return new PayoutResult("fake_payout_" + reference);
        // A Stripe transfer must be tied to the original paid charge.
        throw new PaymentGatewayException();
    }

    @Override
    public PayoutResult payout(String creatorId, BigDecimal amount, String reference, String paymentGatewayRef) {
        if (ledgerPayout) return payout(creatorId, amount, reference);
        if (transfers == null) throw new PaymentGatewayException();
        return new PayoutResult(transfers.transfer(reference, creatorId, paymentGatewayRef, amount).transferId());
    }
}
