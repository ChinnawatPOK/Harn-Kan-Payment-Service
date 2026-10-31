package com.harnkan.payment.service;

import com.harnkan.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "payment.gateway-mode", havingValue = "stripe")
public class StripeReconciliationWorker {
    private static final Logger log = LoggerFactory.getLogger(StripeReconciliationWorker.class);
    private final PaymentRepository repository;
    private final PaymentService service;

    public StripeReconciliationWorker(PaymentRepository repository, PaymentService service) {
        this.repository = repository;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${payment.stripe.reconcile-ms:60000}", initialDelayString = "${payment.stripe.reconcile-ms:60000}")
    public void reconcile() {
        for (String id : repository.paymentsToReconcile()) {
            try { service.reconcilePayment(id); }
            catch (RuntimeException failure) {
                log.warn("STRIPE_RECONCILIATION_RETRY paymentId={} type={}", id, failure.getClass().getSimpleName());
            }
        }
    }
}
