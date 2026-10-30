package com.harnkan.payment.dto;

import com.harnkan.payment.model.Payment;
import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigDecimal;

public record PaymentResponse(String paymentId, String dealId, String participantId, BigDecimal amount,
                              String status, Instant reservedUntil, Boolean confirmedReceipt) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getDealId(), payment.getParticipantId(),
                payment.getAmount(), payment.getStatus(), payment.getReservedUntil() == null ? null : payment.getReservedUntil().toInstant(ZoneOffset.UTC),
                payment.getConfirmedReceipt());
    }
}
