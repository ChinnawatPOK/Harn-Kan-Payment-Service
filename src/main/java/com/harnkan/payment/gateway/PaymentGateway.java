package com.harnkan.payment.gateway;

import java.math.BigDecimal;

public interface PaymentGateway {
    StartPaymentResult startPayment(String paymentId, BigDecimal amount);
    RefundResult refund(String paymentGatewayRef, BigDecimal amount);
    PayoutResult payout(String creatorId, BigDecimal amount, String reference);
    default PayoutResult payout(String creatorId, BigDecimal amount, String reference, String paymentGatewayRef) {
        return payout(creatorId, amount, reference);
    }
    default GatewayPaymentState paymentState(String reference) { return GatewayPaymentState.PENDING; }

    default PayoutResult payoutDeal(String dealId, String creatorId, java.util.List<PayoutPayment> payments,
            boolean allowCreate) {
        var total = payments.stream().map(PayoutPayment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return payout(creatorId, total, "deal_" + dealId);
    }
}
