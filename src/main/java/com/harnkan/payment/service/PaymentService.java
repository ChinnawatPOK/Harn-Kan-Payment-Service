package com.harnkan.payment.service;

import com.harnkan.payment.client.PaymentDealNotifier;
import com.harnkan.payment.exception.*;
import com.harnkan.payment.gateway.PaymentGateway;
import com.harnkan.payment.gateway.GatewayPaymentState;
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
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");
    private final PaymentRepository repository;
    private final PaymentGateway gateway;
    private final Clock clock;
    private final PaymentDealNotifier notifier;
    private final JsonMapper json = new JsonMapper();

    public PaymentService(PaymentRepository repository, PaymentGateway gateway, Clock clock, PaymentDealNotifier notifier) {
        this.repository = repository;
        this.gateway = gateway;
        this.clock = clock;
        this.notifier = notifier;
    }

    public Payment createPayment(String dealId, String participantId, BigDecimal amount) {
        validateId(dealId, "dealId");
        validateId(participantId, "participantId");
        BigDecimal validatedAmount = validateAmount(amount);
        return repository.withDealLock(dealId, () -> {
            if (repository.existsActivePayment(dealId, participantId)) throw new DuplicatePaymentException();
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
            return payment;
        });
    }

    public StartPaymentResult startPayment(String paymentId, String userId) {
        Payment initial = getOwnedPayment(paymentId, userId);
        try {
            return repository.withDealLock(initial.getDealId(), () -> {
                Payment payment = getOwnedPayment(paymentId, userId);
                if (!"RESERVED".equals(payment.getStatus())) throw new InvalidPaymentStateException();
                if (payment.getReservedUntil() == null || !LocalDateTime.now(clock).isBefore(payment.getReservedUntil())) {
                    changeAndNotify(paymentId, "FAILED");
                    throw new PaymentExpiredException();
                }
                StartPaymentResult result = gateway.startPayment(paymentId, payment.getAmount());
                if (result == null || !validReference(result.gatewayReference()) || result.clientSecret() == null
                        || result.clientSecret().isBlank()) throw new PaymentGatewayException();
                repository.atomic(() -> {
                    if (repository.updatePaymentStarted(paymentId, result.gatewayReference(), "PAYMENT_PENDING") != 1)
                        throw new InvalidPaymentStateException();
                    repository.enqueueDealUpdate(paymentId, "PAYMENT_PENDING");
                    return null;
                });
                return result;
            });
        } finally {
            // Never call Deal with a payment lock held, including the expired-payment path.
            notifier.notifyPending(paymentId);
        }
    }

    public Payment getPayment(String paymentId) {
        validateId(paymentId, "paymentId");
        return repository.findById(paymentId).orElseThrow(PaymentNotFoundException::new);
    }

    public Payment getOwnedPayment(String paymentId, String userId) {
        Payment payment = getPayment(paymentId);
        if (!payment.getParticipantId().equals(userId)) throw new AccessDeniedException("Payment belongs to another user");
        return payment;
    }

    public Payment readOwnedPayment(String paymentId, String userId) {
        Payment payment = getOwnedPayment(paymentId, userId); // Authorize before any provider request.
        if ("REFUND_PENDING".equals(payment.getStatus()) ||
                (List.of("PAYMENT_PENDING", "FAILED").contains(payment.getStatus())
                        && payment.getUpdatedAt().isBefore(LocalDateTime.now(clock).minusMinutes(10)))) {
            reconcilePayment(paymentId);
        }
        return getOwnedPayment(paymentId, userId);
    }

    public void reconcilePayment(String paymentId) {
        Payment payment = getPayment(paymentId);
        if (!validReference(payment.getPaymentGatewayRef()) ||
                !List.of("PAYMENT_PENDING", "FAILED", "REFUND_PENDING").contains(payment.getStatus())) return;
        try {
            GatewayPaymentState status = gateway.paymentState(payment.getPaymentGatewayRef());
            if (status == GatewayPaymentState.SUCCEEDED) handleStripeResult(payment.getPaymentGatewayRef(), true);
            else if (status == GatewayPaymentState.FAILED) handleStripeResult(payment.getPaymentGatewayRef(), false);
        } catch (PaymentGatewayException failure) {
            // A provider outage must not prevent users from reading their persisted payment state.
            log.warn("STRIPE_RECONCILIATION_RETRY paymentId={}", paymentId);
        }
    }

    public Payment cancelPayment(String paymentId) {
        Payment initial = getPayment(paymentId);
        return repository.withDealLock(initial.getDealId(), () -> {
            Payment payment = getPayment(paymentId);
            switch (payment.getStatus()) {
                case "RESERVED", "PAYMENT_PENDING" -> repository.atomic(() -> {
                    repository.updateStatus(paymentId, "FAILED");
                    repository.clearDealUpdate(paymentId);
                    return null;
                });
                case "FAILED" -> { /* Idempotent retry. */ }
                default -> throw new InvalidPaymentStateException();
            }
            // Deal initiated this RPC: only return state, never enqueue a callback.
            return getPayment(paymentId);
        });
    }

    public record RefundOutcome(int refundedCount, List<Payment> payments) {}

    public RefundOutcome refundDeal(String dealId) {
        validateId(dealId, "dealId");
        return repository.withDealLock(dealId, () -> {
            List<Payment> payments = repository.findByDealId(dealId);
            if (payments.stream().anyMatch(p -> List.of("PAYOUT_PENDING", "RELEASED").contains(p.getStatus())))
                throw new InvalidPaymentStateException();
            int refundedCount = 0;
            for (Payment payment : payments) {
                switch (payment.getStatus()) {
                    case "RESERVED", "PAYMENT_PENDING" -> repository.atomic(() -> {
                        repository.updateStatus(payment.getId(), "FAILED");
                        repository.clearDealUpdate(payment.getId());
                        return null;
                    });
                    case "HELD", "REFUND_PENDING" -> {
                        repository.clearDealUpdate(payment.getId());
                        if (refund(payment)) refundedCount++;
                    }
                    default -> { /* Already FAILED or REFUNDED. */ }
                }
            }
            // Return partial progress so Deal can persist REFUND_PENDING and schedule retries.
            return new RefundOutcome(refundedCount, repository.findByDealId(dealId));
        });
    }

    private boolean refund(Payment payment) {
        repository.updateStatus(payment.getId(), "REFUND_PENDING");
        try {
            if (!validReference(payment.getPaymentGatewayRef())) throw new PaymentGatewayException();
            var result = gateway.refund(payment.getPaymentGatewayRef(), payment.getAmount());
            if (result == null || !validReference(result.gatewayReference())) throw new PaymentGatewayException();
            return repository.atomic(() -> {
                int changed = repository.markRefunded(payment.getId(), result.gatewayReference());
                if (changed == 1) {
                    enqueueEvent("payment.refunded:" + payment.getId(), "payment.refunded", Map.of(
                            "dealId", payment.getDealId(), "paymentId", payment.getId(),
                            "participantId", payment.getParticipantId(), "amount", payment.getAmount().toPlainString()));
                }
                return changed == 1;
            });
        } catch (PaymentGatewayException failure) {
            log.warn("REFUND_PENDING paymentId={}", payment.getId());
            return false;
        }
    }

    public Payment confirmReceipt(String paymentId, String participantId) {
        validateId(participantId, "participantId");
        Payment initial = getPayment(paymentId);
        return repository.withDealLock(initial.getDealId(), () -> {
            Payment payment = getPayment(paymentId);
            if (!payment.getParticipantId().equals(participantId)) throw new AccessDeniedException("Participant does not own payment");
            if (!"HELD".equals(payment.getStatus())) {
                if (Boolean.TRUE.equals(payment.getConfirmedReceipt())
                        && List.of("PAYOUT_PENDING", "RELEASED").contains(payment.getStatus())) return payment;
                throw new InvalidPaymentStateException();
            }
            repository.atomic(() -> {
                repository.confirmReceipt(paymentId);
                repository.clearDealUpdate(paymentId);
                return null;
            });
            return getPayment(paymentId);
        });
    }

    public record ReleaseOutcome(int releasedCount, BigDecimal totalAmount, List<Payment> payments) {}

    public ReleaseOutcome releaseFunds(String dealId, String creatorId) {
        validateId(dealId, "dealId");
        validateId(creatorId, "creatorId");
        return repository.withDealLock(dealId, () -> {
            List<Payment> payments = repository.findByDealId(dealId);
            List<Payment> eligible = payments.stream().filter(p -> !List.of("FAILED", "REFUNDED").contains(p.getStatus())).toList();
            if (eligible.isEmpty() || eligible.stream().anyMatch(p ->
                    !List.of("HELD", "PAYOUT_PENDING", "RELEASED").contains(p.getStatus())
                            || !Boolean.TRUE.equals(p.getConfirmedReceipt()))) throw new InvalidPaymentStateException();
            var boundCreator = repository.payoutCreator(dealId);
            if (boundCreator.isPresent() && !boundCreator.get().equals(creatorId)) throw new InvalidPaymentStateException();
            if (boundCreator.isEmpty()) repository.bindPayoutCreator(dealId, creatorId);
            for (Payment payment : eligible) {
                if ("RELEASED".equals(payment.getStatus())) continue;
                repository.updateStatus(payment.getId(), "PAYOUT_PENDING");
                try {
                    var result = gateway.payout(creatorId, payment.getAmount(), payment.getId());
                    if (result == null || !validReference(result.gatewayReference())) throw new PaymentGatewayException();
                    repository.markReleased(payment.getId(), result.gatewayReference());
                } catch (PaymentGatewayException failure) {
                    // Keep progress and allow the Deal scheduler to retry with the same gateway identity.
                    log.warn("PAYOUT_PENDING paymentId={}", payment.getId());
                }
            }
            List<Payment> current = repository.findByDealId(dealId);
            var released = current.stream().filter(p -> "RELEASED".equals(p.getStatus())).toList();
            BigDecimal total = released.stream().map(Payment::getAmount).reduce(new BigDecimal("0.00"), BigDecimal::add);
            if (released.size() == eligible.size()) {
                enqueueEvent("payout.released:" + dealId, "payout.released", Map.of(
                        "dealId", dealId, "creatorId", creatorId, "totalAmount", total.toPlainString()));
            }
            return new ReleaseOutcome(released.size(), total, current);
        });
    }

    public Payment simulateSuccess(String paymentId) {
        Payment initial = getPayment(paymentId);
        repository.withDealLock(initial.getDealId(), () -> {
            Payment payment = getPayment(paymentId);
            if ("PAYMENT_PENDING".equals(payment.getStatus())) changeAndNotify(paymentId, "HELD");
            else if (!"HELD".equals(payment.getStatus())) throw new InvalidPaymentStateException();
            return null;
        });
        notifier.notifyPending(paymentId);
        return getPayment(paymentId);
    }

    public void handlePaymentSucceeded(String gatewayReference) {
        handleGatewayResult(gatewayReference, true, true);
    }

    public void handlePaymentFailed(String gatewayReference) {
        handleGatewayResult(gatewayReference, false, true);
    }

    public void handleStripeResult(String reference, boolean success) {
        handleGatewayResult(reference, success, false);
    }

    private void handleGatewayResult(String reference, boolean success, boolean notifyImmediately) {
        if (!validReference(reference)) throw new IllegalArgumentException("Invalid gateway reference");
        Payment initial = repository.findByGatewayReference(reference).orElseThrow(PaymentNotFoundException::new);
        repository.withDealLock(initial.getDealId(), () -> {
            Payment payment = getPayment(initial.getId());
            if ("PAYMENT_PENDING".equals(payment.getStatus())) changeAndNotify(payment.getId(), success ? "HELD" : "FAILED");
            else if (success && List.of("FAILED", "REFUND_PENDING").contains(payment.getStatus())) refund(payment);
            return null;
        });
        if (notifyImmediately) notifier.notifyPending(initial.getId());
    }

    private void changeAndNotify(String paymentId, String status) {
        repository.atomic(() -> {
            repository.updateStatus(paymentId, status);
            repository.enqueueDealUpdate(paymentId, status);
            return null;
        });
    }

    private void enqueueEvent(String id, String type, Map<String, String> fields) {
        Map<String, String> payload = new LinkedHashMap<>(fields);
        payload.put("eventId", id);
        payload.put("eventType", type);
        payload.put("occurredAt", clock.instant().truncatedTo(ChronoUnit.SECONDS).toString());
        repository.enqueueEvent(id, type, json.writeValueAsString(payload));
    }

    private static boolean validReference(String reference) {
        return reference != null && !reference.isBlank() && reference.length() <= 255;
    }

    private static void validateId(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,32}"))
            throw new IllegalArgumentException(name + " must contain 1-32 letters, digits, underscores or hyphens");
    }

    private static BigDecimal validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0)
            throw new IllegalArgumentException("amount must be between 0.01 and 99999999.99");
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("amount must have at most two decimal places");
        }
    }
}
