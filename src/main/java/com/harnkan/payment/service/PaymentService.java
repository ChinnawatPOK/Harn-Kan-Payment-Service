package com.harnkan.payment.service;

import com.harnkan.payment.client.DealServiceClient;
import com.harnkan.payment.exception.*;
import com.harnkan.payment.gateway.PaymentGateway;
import com.harnkan.payment.gateway.PayoutResult;
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
import java.util.List;
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
    private final DealServiceClient dealClient;

    public PaymentService(PaymentRepository repository, PaymentGateway gateway, Clock clock, DealServiceClient dealClient) {
        this.repository = repository;
        this.gateway = gateway;
        this.clock = clock;
        this.dealClient = dealClient;
    }

    public Payment createPayment(String dealId, String participantId, BigDecimal amount) {
        validateDealId(dealId);
        validateParticipantId(participantId);
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

    /** PG2: RESERVED / PAYMENT_PENDING -> FAILED. Deal started this, so Deal is not called back. */
    public Payment cancelPayment(String paymentId) {
        Payment initial = getPayment(paymentId);
        log.info("PAYMENT_CANCEL_REQUEST paymentId={}", paymentId);
        return repository.withDealLock(initial.getDealId(), () -> {
            Payment payment = getPayment(paymentId);
            switch (payment.getStatus()) {
                case "RESERVED", "PAYMENT_PENDING" -> {
                    // Fake gateway: nothing to cancel there. A real gateway must cancel the PaymentIntent here.
                    repository.updateStatus(paymentId, "FAILED");
                    payment.setStatus("FAILED");
                    log.info("PAYMENT_FAILED paymentId={} reason=cancelled", paymentId);
                }
                case "FAILED" -> { /* already cancelled: lets Deal retry safely */ }
                default -> throw new InvalidPaymentStateException();
            }
            return payment;
        });
    }

    /** PY5: only the owner can pay. Deal is told PAYMENT_PENDING (or FAILED when the reservation had expired). */
    public StartPaymentResult startPayment(String paymentId, String userId) {
        Payment initial = getPayment(paymentId);
        requireOwner(initial, userId);
        log.info("PAYMENT_START_REQUEST paymentId={}", paymentId);
        StartPaymentResult result;
        try {
            result = repository.withDealLock(initial.getDealId(), () -> {
                Payment payment = getPayment(paymentId);
                if (!"RESERVED".equals(payment.getStatus())) {
                    throw new InvalidPaymentStateException();
                }
                if (payment.getReservedUntil() == null || !LocalDateTime.now(clock).isBefore(payment.getReservedUntil())) {
                    repository.updateStatus(paymentId, "FAILED");
                    log.info("PAYMENT_FAILED paymentId={} reason=expired", paymentId);
                    throw new PaymentExpiredException();
                }
                StartPaymentResult started = gateway.startPayment(paymentId, payment.getAmount());
                if (started == null || !validReference(started.gatewayReference()) || started.clientSecret() == null
                        || started.clientSecret().isBlank()) {
                    throw new PaymentGatewayException();
                }
                if (repository.updatePaymentStarted(paymentId, started.gatewayReference(), "PAYMENT_PENDING") != 1) {
                    throw new InvalidPaymentStateException();
                }
                log.info("PAYMENT_PENDING paymentId={}", paymentId);
                return started;
            });
        } catch (PaymentExpiredException expired) {
            dealClient.updateParticipantPayment(initial, "FAILED");
            throw expired;
        }
        dealClient.updateParticipantPayment(initial, "PAYMENT_PENDING");
        return result;
    }

    /** PY4 (fake mode only): stands in for the Stripe webhook. Repeating it re-sends HELD to Deal. */
    public Payment simulateSuccess(String paymentId) {
        Payment initial = getPayment(paymentId);
        Payment payment = repository.withDealLock(initial.getDealId(), () -> {
            Payment current = getPayment(paymentId);
            if ("PAYMENT_PENDING".equals(current.getStatus())) {
                repository.updateStatus(paymentId, "HELD");
                current.setStatus("HELD");
                log.info("PAYMENT_SUCCEEDED paymentId={} source=simulate", paymentId);
            } else if (!"HELD".equals(current.getStatus())) {
                throw new InvalidPaymentStateException();
            }
            return current;
        });
        dealClient.updateParticipantPayment(payment, "HELD");
        return payment;
    }

    public Payment getPayment(String paymentId) {
        validateId(paymentId, "paymentId");
        return repository.findById(paymentId).orElseThrow(PaymentNotFoundException::new);
    }

    public Payment getOwnPayment(String paymentId, String userId) {
        Payment payment = getPayment(paymentId);
        requireOwner(payment, userId);
        return payment;
    }

    public RefundOutcome refundDeal(String dealId) {
        validateDealId(dealId);
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
            // Re-read so payments[] carries the final status of every row.
            return new RefundOutcome(refundedCount, repository.findByDealId(dealId));
        });
    }

    /**
     * PG5: the participant got their items. Only a HELD payment of that participant can be confirmed; repeating it
     * (also after the payout started) answers the same. Deal started this, so Deal is not called back.
     */
    public Payment confirmReceipt(String paymentId, String participantId) {
        Payment initial = getPayment(paymentId);
        validateParticipantId(participantId);
        if (!participantId.equals(initial.getParticipantId())) {
            throw new PaymentForbiddenException();
        }
        log.info("CONFIRM_RECEIPT_REQUEST paymentId={}", paymentId);
        return repository.withDealLock(initial.getDealId(), () -> {
            Payment payment = getPayment(paymentId);
            boolean confirmed = Boolean.TRUE.equals(payment.getConfirmedReceipt());
            if (!"HELD".equals(payment.getStatus())) {
                if (confirmed && ("PAYOUT_PENDING".equals(payment.getStatus()) || "RELEASED".equals(payment.getStatus()))) {
                    return payment;
                }
                throw new InvalidPaymentStateException();
            }
            if (!confirmed) {
                repository.markConfirmedReceipt(paymentId);
                payment.setConfirmedReceipt(true);
                log.info("RECEIPT_CONFIRMED paymentId={}", paymentId);
            }
            return payment;
        });
    }

    /**
     * PG3: pays every HELD payment of the deal to the host (simulated ledger in fake mode). All of them must be
     * confirmed first. PAYOUT_PENDING is saved before the gateway call, so a failed payout is retried by calling
     * again; RELEASED rows are skipped, so repeating it answers the same totals.
     */
    public ReleaseOutcome releaseFunds(String dealId, String creatorId) {
        validateDealId(dealId);
        if (creatorId == null || !creatorId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("creatorId must contain 1-64 letters, digits, underscores or hyphens");
        }
        log.info("RELEASE_FUNDS_REQUEST dealId={}", dealId);
        return repository.withDealLock(dealId, () -> {
            List<Payment> rows = repository.findByDealId(dealId);
            if (rows.stream().anyMatch(p -> "HELD".equals(p.getStatus()) && !Boolean.TRUE.equals(p.getConfirmedReceipt()))) {
                throw new InvalidPaymentStateException("Every paid payment must have its receipt confirmed before release");
            }
            boolean gatewayFailed = false;
            for (Payment payment : rows) {
                if (!"HELD".equals(payment.getStatus()) && !"PAYOUT_PENDING".equals(payment.getStatus())) {
                    continue; // FAILED, REFUNDED and already RELEASED rows need nothing
                }
                repository.updateStatus(payment.getId(), "PAYOUT_PENDING");
                try {
                    PayoutResult result = gateway.payout(creatorId, payment.getAmount(), payment.getId());
                    if (result == null || !validReference(result.gatewayReference())) {
                        throw new PaymentGatewayException();
                    }
                    repository.markReleased(payment.getId(), result.gatewayReference());
                    log.info("PAYOUT_RELEASED paymentId={} dealId={}", payment.getId(), dealId);
                } catch (PaymentGatewayException exception) {
                    gatewayFailed = true;
                    log.warn("PAYOUT_FAILED paymentId={} dealId={}", payment.getId(), dealId);
                }
            }
            if (gatewayFailed) {
                throw new PaymentGatewayException();
            }
            List<Payment> after = repository.findByDealId(dealId);
            List<Payment> released = after.stream().filter(p -> "RELEASED".equals(p.getStatus())).toList();
            BigDecimal total = released.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.UNNECESSARY);
            return new ReleaseOutcome(released.size(), total, after);
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
        Payment changed = repository.withDealLock(initial.getDealId(), () -> {
            Payment payment = getPayment(initial.getId());
            if ("PAYMENT_PENDING".equals(payment.getStatus())) {
                repository.updateStatus(payment.getId(), targetStatus);
                payment.setStatus(targetStatus);
                log.info("{} paymentId={}", event, payment.getId());
                return payment;
            }
            // Duplicate/out-of-order events never resurrect a cancelled or refunded payment.
            return null;
        });
        if (changed != null) {
            dealClient.updateParticipantPayment(changed, targetStatus);
        }
    }

    private static void requireOwner(Payment payment, String userId) {
        if (userId == null || !userId.equals(payment.getParticipantId())) {
            throw new PaymentForbiddenException();
        }
    }

    private static boolean validReference(String reference) {
        return reference != null && !reference.isBlank() && reference.length() <= 255;
    }

    private static void validateId(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException(name + " must contain 1-32 letters, digits, underscores or hyphens");
        }
    }

    private static void validateDealId(String value) {
        validateId(value, "dealId");
    }

    /** participant_id is a user id: ObjectId (24) or UUID (36), up to 64 characters. */
    private static void validateParticipantId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("participantId must contain 1-64 letters, digits, underscores or hyphens");
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
