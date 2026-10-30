package com.harnkan.payment.messaging;

import com.harnkan.payment.client.PaymentDealNotifier;
import com.harnkan.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Network delivery is deliberately outside payment locks to avoid Deal <-> Payment deadlocks. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "payment.delivery.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(PaymentDeliveryWorker.class);
    private final PaymentRepository repository;
    private final PaymentDealNotifier deal;
    private final PaymentEventPublisher events;

    public PaymentDeliveryWorker(PaymentRepository repository, PaymentDealNotifier deal, PaymentEventPublisher events) {
        this.repository = repository;
        this.deal = deal;
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${payment.delivery.retry-ms:5000}")
    public void deliverDealUpdates() {
        for (var update : repository.pendingDealUpdates()) {
            deal.deliver(update);
        }
    }

    @Scheduled(fixedDelayString = "${payment.delivery.retry-ms:5000}")
    public void deliverEvents() {
        for (var event : repository.pendingEvents()) {
            try {
                events.publish(event);
                repository.acknowledgeEvent(event.eventId());
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
                log.warn("EVENT_DELIVERY_RETRY eventId={} type={}", event.eventId(), failure.getClass().getSimpleName());
            }
        }
    }
}
