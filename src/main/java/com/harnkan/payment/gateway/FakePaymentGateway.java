package com.harnkan.payment.gateway;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class FakePaymentGateway implements PaymentGateway {
    @Override
    public StartPaymentResult startPayment(String paymentId, BigDecimal amount) {
        return new StartPaymentResult("fake_pay_" + paymentId, "fake_secret_" + paymentId);
    }

    @Override
    public PayoutResult payout(String creatorId, BigDecimal amount, String reference) {
        return new PayoutResult("fake_payout_" + reference);
    }

    @Override
    public RefundResult refund(String paymentGatewayRef, BigDecimal amount) {
        return new RefundResult("fake_refund_" + UUID.nameUUIDFromBytes(paymentGatewayRef.getBytes(StandardCharsets.UTF_8)));
    }
}
