package com.harnkan.payment.service;

import com.harnkan.payment.exception.*;
import com.harnkan.payment.gateway.PaymentGateway;
import com.harnkan.payment.gateway.RefundResult;
import com.harnkan.payment.gateway.StartPaymentResult;
import com.harnkan.payment.model.Payment;
import com.harnkan.payment.repository.PaymentRepository;
import com.harnkan.payment.util.IdGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");
    private final PaymentRepository repository;
    private final PaymentGateway gateway;
    private final Clock clock;

    public PaymentService(PaymentRepository repository, PaymentGateway gateway, Clock clock) {
        this.repository = repository;
        this.gateway = gateway;
        this.clock = clock;
    }

    public Payment createPayment(String dealId, String participantId, BigDecimal amount) {
        validateId(dealId, "dealId");
        validateId(participantId, "participantId");
        BigDecimal validatedAmount = validateAmount(amount);
        log.info("PAYMENT_CREATE_REQUEST dealId={} participantId={}", dealId, participantId);
        return repository.withDealLock(dealId, () -> {
            if (repository.existsActivePayment(dealId, participantId)) {
                throw new DuplicatePaymentException();
            }
            LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
            Payment payment = new Payment();
            payment.setId(IdGenerator.generate());
            payment.setDealId(dealId);
            payment.setParticipantId(participantId);
            payment.setAmount(validatedAmount);
            payment.setStatus("RESERVED");
            payment.setReservedUntil(now.plusMinutes(5));
            payment.setConfirmedReceipt(false);
            payment.setCreatedAt(now);
            payment.setUpdatedAt(now);
            repository.insert(payment);
            log.info("PAYMENT_RESERVED paymentId={} dealId={}", payment.getId(), dealId);
            return payment;
        });
    }

    public StartPaymentResult startPayment(String paymentId) {
        Payment initial = getPayment(paymentId);
        log.info("PAYMENT_START_REQUEST paymentId={}", paymentId);
        return repository.withDealLock(initial.getDealId(), () -> {
            Payment payment = getPayment(paymentId);
            if (!"RESERVED".equals(payment.getStatus())) {
                throw new InvalidPaymentStateException();
            }
            if (payment.getReservedUntil() == null || !LocalDateTime.now(clock).isBefore(payment.getReservedUntil())) {
                repository.updateStatus(paymentId, "FAILED");
                log.info("PAYMENT_FAILED paymentId={} reason=expired", paymentId);
                // Autocommitted: this update is not rolled back when the exception is mapped to 409.
                throw new PaymentExpiredException();
            }
            StartPaymentResult result = gateway.startPayment(paymentId, payment.getAmount());
            if (result == null || !validReference(result.gatewayReference()) || result.clientSecret() == null
                    || result.clientSecret().isBlank()) {
                throw new PaymentGatewayException();
            }
            if (repository.updatePaymentStarted(paymentId, result.gatewayReference(), "PAYMENT_PENDING") != 1) {
                throw new InvalidPaymentStateException();
            }
            log.info("PAYMENT_PENDING paymentId={}", paymentId);
            return result;
        });
    }

    public Payment getPayment(String paymentId) {
        validateId(paymentId, "paymentId");
        return repository.findById(paymentId).orElseThrow(PaymentNotFoundException::new);
    }

    public int refundDeal(String dealId) {
        validateId(dealId, "dealId");
        log.info("REFUND_DEAL_REQUEST dealId={}", dealId);
        return repository.withDealLock(dealId, () -> {
            int refundedCount = 0;
            boolean gatewayFailed = false;
            for (Payment payment : repository.findByDealId(dealId)) {
                switch (payment.getStatus()) {
                    case "RESERVED", "PAYMENT_PENDING" -> {
                        repository.updateStatus(payment.getId(), "FAILED");
                        log.info("PAYMENT_FAILED paymentId={} reason=deal_cancelled", payment.getId());
                    }
                    case "HELD", "REFUND_PENDING" -> {
                        // Retry REFUND_PENDING with the same gateway identity after a crash/outage.
                        repository.updateStatus(payment.getId(), "REFUND_PENDING");
                        log.info("REFUND_STARTED paymentId={} dealId={}", payment.getId(), dealId);
                        try {
                            if (!validReference(payment.getPaymentGatewayRef())) {
                                throw new PaymentGatewayException();
                            }
                            RefundResult result = gateway.refund(payment.getPaymentGatewayRef(), payment.getAmount());
                            if (result == null || !validReference(result.gatewayReference())) {
                                throw new PaymentGatewayException();
                            }
                            refundedCount += repository.markRefunded(payment.getId(), result.gatewayReference());
                            log.info("REFUND_COMPLETED paymentId={} dealId={}", payment.getId(), dealId);
                        } catch (PaymentGatewayException exception) {
                            gatewayFailed = true;
                            log.warn("REFUND_FAILED paymentId={} dealId={}", payment.getId(), dealId);
                        }
                    }
                    default -> { /* FAILED, REFUNDED, and future payout states require no refund here. */ }
                }
            }
            if (gatewayFailed) {
                throw new PaymentGatewayException();
            }
            return refundedCount;
        });
    }

    public void handlePaymentSucceeded(String gatewayReference) {
        handleGatewayResult(gatewayReference, "HELD", "PAYMENT_SUCCEEDED");
    }

    public void handlePaymentFailed(String gatewayReference) {
        handleGatewayResult(gatewayReference, "FAILED", "PAYMENT_FAILED");
    }

    private void handleGatewayResult(String gatewayReference, String targetStatus, String event) {
        if (!validReference(gatewayReference)) {
            throw new IllegalArgumentException("gatewayReference is required and must not exceed 255 characters");
        }
        Payment initial = repository.findByGatewayReference(gatewayReference).orElseThrow(PaymentNotFoundException::new);
        repository.withDealLock(initial.getDealId(), () -> {
            Payment payment = getPayment(initial.getId());
            if ("PAYMENT_PENDING".equals(payment.getStatus())) {
                repository.updateStatus(payment.getId(), targetStatus);
                log.info("{} paymentId={}", event, payment.getId());
            }
            // Duplicate/out-of-order events never resurrect a cancelled or refunded payment.
            return null;
        });
    }

    private static boolean validReference(String reference) {
        return reference != null && !reference.isBlank() && reference.length() <= 255;
    }

    private static void validateId(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException(name + " must contain 1-32 letters, digits, underscores or hyphens");
        }
    }

    private static BigDecimal validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("amount must be between 0.01 and 99999999.99");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("amount must have at most two decimal places");
        }
    }
}
