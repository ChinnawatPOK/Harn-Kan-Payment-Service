package com.harnkan.payment.client;

import com.harnkan.payment.repository.PaymentRepository;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Direct DG5 notification; callers must release payment mutation locks first. */
@Component
public class PaymentDealNotifier {
    private static final Logger log = LoggerFactory.getLogger(PaymentDealNotifier.class);
    private static final Set<String> STATES = Set.of("PAYMENT_PENDING", "HELD", "FAILED");
    private final PaymentRepository repository;
    private final DealPaymentClient client;

    public PaymentDealNotifier(PaymentRepository repository, DealPaymentClient client) {
        this.repository = repository;
        this.client = client;
    }

    public void notifyCurrent(String paymentId) {
        try {
            repository.findById(paymentId).filter(payment -> STATES.contains(payment.getStatus()))
                    .ifPresent(payment -> client.update(payment, payment.getStatus()));
        } catch (Exception failure) {
            // Payment remains committed. An owner GET can resend its current state after recovery.
            log.warn("DEAL_SYNC_FAILED paymentId={} type={}", paymentId, failure.getClass().getSimpleName());
        }
    }
}
