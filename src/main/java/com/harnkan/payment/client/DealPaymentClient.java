package com.harnkan.payment.client;

import com.harnkan.contract.deal.DealServiceGrpc;
import com.harnkan.contract.deal.UpdateParticipantPaymentRequest;
import com.harnkan.payment.model.Payment;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DealPaymentClient {
    private final ManagedChannel channel;
    private final DealServiceGrpc.DealServiceBlockingStub stub;

    public DealPaymentClient(@Value("${payment.deal.target:localhost:9090}") String target) {
        channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        stub = DealServiceGrpc.newBlockingStub(channel);
    }

    public void update(Payment payment, String status) {
        stub.withDeadlineAfter(3, TimeUnit.SECONDS).updateParticipantPayment(
                UpdateParticipantPaymentRequest.newBuilder()
                        .setDealId(payment.getDealId()).setParticipantId(payment.getParticipantId())
                        .setPaymentId(payment.getId()).setStatus(status).build());
    }

    @PreDestroy
    public void close() {
        channel.shutdownNow();
    }
}
