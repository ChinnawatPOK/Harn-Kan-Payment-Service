package com.harnkan.payment.gateway;

import java.math.BigDecimal;

/** Implementations must deduplicate starts by paymentId and full refunds by gateway reference. */
public interface PaymentGateway {
    StartPaymentResult startPayment(String paymentId, BigDecimal amount);
    PayoutResult payout(String creatorId, BigDecimal amount, String reference);
    default PayoutResult payout(String creatorId, BigDecimal amount, String reference, String paymentGatewayRef) {
        return payout(creatorId, amount, reference);
    }
    default GatewayPaymentState paymentState(String paymentGatewayRef) {
        return GatewayPaymentState.PENDING;
    }
    RefundResult refund(String paymentGatewayRef, BigDecimal amount);
}
