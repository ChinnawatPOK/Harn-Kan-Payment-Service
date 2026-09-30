package com.harnkan.payment.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Payment {
    private String id;
    private String dealId;
    private String participantId;
    private BigDecimal amount;
    private String paymentGatewayRef;
    private String refundGatewayRef;
    private String payoutGatewayRef;
    private String status;
    private LocalDateTime reservedUntil;
    private Boolean confirmedReceipt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
