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

/** Stripe collects/refunds payments; host payouts remain a deterministic simulated ledger. */
public class StripePaymentGateway implements PaymentGateway {
    private final StripeClient client;

    public StripePaymentGateway(StripeClient client) {
        this.client = client;
    }

    @Override
    public StartPaymentResult startPayment(String paymentId, BigDecimal amount) {
        try {
            var params = PaymentIntentCreateParams.builder().setCurrency("thb").setAmount(satang(amount))
                    .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                            .setEnabled(true)
                            .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER).build()).putMetadata("paymentId", paymentId).build();
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
        // Specification section 9's proposed ledger policy; no Stripe Connect transfer occurs.
        return new PayoutResult("fake_payout_" + reference);
    }

    private static long satang(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    private static RequestOptions options(String key) {
        return RequestOptions.builder().setIdempotencyKey(key).build();
    }
}
