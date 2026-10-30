package com.harnkan.payment.client;

import com.harnkan.payment.repository.PaymentRepository;
import com.harnkan.payment.repository.PaymentRepository.DealUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PaymentDealNotifier {
    private static final Logger log = LoggerFactory.getLogger(PaymentDealNotifier.class);
    private final PaymentRepository repository;
    private final DealPaymentClient client;

    public PaymentDealNotifier(PaymentRepository repository, DealPaymentClient client) {
        this.repository = repository;
        this.client = client;
    }

    public void notifyPending(String paymentId) {
        try {
            repository.withDeliveryLock(paymentId, () -> {
                repository.pendingDealUpdate(paymentId).ifPresent(update -> {
                    var payment = repository.findById(update.paymentId()).orElseThrow();
                    client.update(payment, update.status());
                    repository.acknowledgeDealUpdate(update);
                });
                return null;
            });
        } catch (Exception failure) {
            // The committed state and delivery row survive; retry after outages/restarts.
            log.warn("DEAL_SYNC_RETRY paymentId={} type={}", paymentId, failure.getClass().getSimpleName());
        }
    }

    public void deliver(DealUpdate update) {
        // Re-read under a separate delivery lock so immediate sends/workers cannot send stale states out of order.
        notifyPending(update.paymentId());
    }
}
