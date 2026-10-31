package com.harnkan.payment.gateway;

import java.math.BigDecimal;

public interface PaymentGateway {
    StartPaymentResult startPayment(String paymentId, BigDecimal amount);
    RefundResult refund(String paymentGatewayRef, BigDecimal amount);
    PayoutResult payout(String creatorId, BigDecimal amount, String reference);
}
