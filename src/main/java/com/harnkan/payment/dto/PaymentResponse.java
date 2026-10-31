package com.harnkan.payment.dto;

import com.harnkan.payment.model.Payment;
import java.time.ZoneOffset;

public record PaymentResponse(String paymentId, String dealId, String participantId, String amount,
                              String status, String reservedUntil, Boolean confirmedReceipt) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getDealId(), payment.getParticipantId(),
                payment.getAmount().toPlainString(), payment.getStatus(),
                payment.getReservedUntil() == null ? null : payment.getReservedUntil().toInstant(ZoneOffset.UTC).toString(),
                payment.getConfirmedReceipt());
    }
}
