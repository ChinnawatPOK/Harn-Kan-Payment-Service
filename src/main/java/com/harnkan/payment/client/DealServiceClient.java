package com.harnkan.payment.client;

import com.harnkan.contract.deal.DealServiceGrpc;
import com.harnkan.contract.deal.UpdateParticipantPaymentRequest;
import com.harnkan.payment.model.Payment;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DealServiceClient {
    private static final Logger log = LoggerFactory.getLogger(DealServiceClient.class);
    private static final long DEADLINE_SECONDS = 3;

    private final boolean enabled;
    private final ManagedChannel channel;

    public DealServiceClient(@Value("${deal.callback.enabled:true}") boolean enabled,
                             @Value("${deal.service.host:localhost}") String host,
                             @Value("${deal.service.port:9090}") int port) {
        this.enabled = enabled;
        this.channel = enabled ? ManagedChannelBuilder.forAddress(host, port).usePlaintext().build() : null;
    }

    public void updateParticipantPayment(Payment payment, String status) {
        if (!enabled) {
            return;
        }
        try {
            DealServiceGrpc.newBlockingStub(channel).withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .updateParticipantPayment(UpdateParticipantPaymentRequest.newBuilder()
                            .setDealId(payment.getDealId())
                            .setParticipantId(payment.getParticipantId())
                            .setPaymentId(payment.getId())
                            .setStatus(status)
                            .build());
            log.info("DEAL_CALLBACK_OK paymentId={} status={}", payment.getId(), status);
        } catch (RuntimeException exception) {
            log.warn("DEAL_CALLBACK_FAILED paymentId={} status={} reason={}", payment.getId(), status, exception.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        if (channel != null) {
            channel.shutdown();
        }
    }
}
