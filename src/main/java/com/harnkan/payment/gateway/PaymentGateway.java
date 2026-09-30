package com.harnkan.payment.gateway;

import java.math.BigDecimal;

/** Implementations must deduplicate starts by paymentId and full refunds by gateway reference. */
public interface PaymentGateway {
    StartPaymentResult startPayment(String paymentId, BigDecimal amount);
    RefundResult refund(String paymentGatewayRef, BigDecimal amount);
}
