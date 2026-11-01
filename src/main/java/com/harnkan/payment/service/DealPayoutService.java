package com.harnkan.payment.service;

import com.harnkan.payment.exception.*;
import com.harnkan.payment.gateway.*;
import com.harnkan.payment.model.Payment;
import com.harnkan.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

/** One transfer per deal. Its ID is shared by the existing payments.payment_transfer_gateway_ref columns. */
@Service
public class DealPayoutService {
    private final PaymentRepository repository;
    private final PaymentGateway gateway;

    public DealPayoutService(PaymentRepository repository, PaymentGateway gateway) {
        this.repository = repository;
        this.gateway = gateway;
    }

    public ReleaseOutcome releaseFunds(String dealId, String creatorId) {
        if (dealId == null || !dealId.matches("[A-Za-z0-9_-]{1,32}")
                || creatorId == null || !creatorId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Valid dealId and creatorId are required");
        return repository.withDealLock(dealId, () -> {
            var rows = repository.findByDealId(dealId);
            if (rows.stream().anyMatch(p -> List.of("RESERVED", "PAYMENT_PENDING", "REFUND_PENDING").contains(p.getStatus())))
                throw new InvalidPaymentStateException();
            var paid = rows.stream().filter(p -> List.of("HELD", "PAYOUT_PENDING", "RELEASED").contains(p.getStatus())).toList();
            if (paid.isEmpty()) throw new InvalidPaymentStateException();
            if (paid.stream().anyMatch(p -> !Boolean.TRUE.equals(p.getConfirmedReceipt())))
                throw new InvalidPaymentStateException();
            var pending = paid.stream().filter(p -> !"RELEASED".equals(p.getStatus())).toList();
            if (pending.isEmpty()) return outcome(rows);
            Set<String> saved = new HashSet<>();
            for (Payment p : paid) {
                if (p.getPaymentTransferGatewayRef() != null && !p.getPaymentTransferGatewayRef().isBlank()) saved.add(p.getPaymentTransferGatewayRef());
                if ("RELEASED".equals(p.getStatus()) && (p.getPaymentTransferGatewayRef() == null || p.getPaymentTransferGatewayRef().isBlank()))
                    throw new InvalidPaymentStateException();
            }
            if (saved.size() > 1) throw new InvalidPaymentStateException();
            for (Payment p : pending) repository.updateStatus(p.getId(), "PAYOUT_PENDING");
            var sources = paid.stream().map(p -> new PayoutPayment(p.getId(), p.getPaymentGatewayRef(), p.getAmount())).toList();
            // If a previous row was saved, Stripe must find that same aggregate transfer; never create another.
            var result = gateway.payoutDeal(dealId, creatorId, sources, saved.isEmpty());
            if (result == null || result.gatewayReference() == null || result.gatewayReference().isBlank()
                    || result.gatewayReference().length() > 255
                    || (!saved.isEmpty() && !saved.contains(result.gatewayReference()))) throw new PaymentGatewayException();
            for (Payment p : pending) {
                markReleased(p.getId(), result.gatewayReference());
            }
            var updated = repository.findByDealId(dealId);
            for (Payment p : paid) {
                if (updated.stream().noneMatch(row -> row.getId().equals(p.getId()) && "RELEASED".equals(row.getStatus())
                        && result.gatewayReference().equals(row.getPaymentTransferGatewayRef()))) throw new PaymentGatewayException();
            }
            return outcome(updated);
        });
    }

    /** The retained checkout snapshot returned void here; current repository returns an update count. */
    private void markReleased(String paymentId, String reference) {
        try {
            Object changed = PaymentRepository.class.getMethod("markReleased", String.class, String.class)
                    .invoke(repository, paymentId, reference);
            if (changed instanceof Integer count && count != 1) throw new PaymentGatewayException();
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private ReleaseOutcome outcome(List<Payment> rows) {
        var released = rows.stream().filter(p -> "RELEASED".equals(p.getStatus())).toList();
        BigDecimal total = released.stream().map(Payment::getAmount).reduce(new BigDecimal("0.00"), BigDecimal::add);
        return new ReleaseOutcome(released.size(), total, rows);
    }
}
