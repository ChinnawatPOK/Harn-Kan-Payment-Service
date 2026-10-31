package com.harnkan.payment.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.harnkan.contract.payment.*;
import com.harnkan.payment.exception.InvalidPaymentStateException;
import com.harnkan.payment.exception.PaymentNotFoundException;
import com.harnkan.payment.model.Payment;
import com.harnkan.payment.service.PaymentService;
import com.harnkan.payment.service.RefundOutcome;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentGrpcServiceTest {
    @Mock private PaymentService service;
    private Server server;
    private ManagedChannel channel;
    private PaymentServiceGrpc.PaymentServiceBlockingStub stub;

    @BeforeEach
    void start() throws Exception {
        String name = "payment-" + System.nanoTime();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(new PaymentGrpcService(service)).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = PaymentServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static Payment payment(String id, String participant, String status) {
        Payment p = new Payment();
        p.setId(id);
        p.setDealId("d1");
        p.setParticipantId(participant);
        p.setAmount(new BigDecimal("190.00"));
        p.setStatus(status);
        p.setReservedUntil(LocalDateTime.of(2026, 10, 28, 9, 5, 0));
        return p;
    }

    @Test
    void createPayment_reservedUntilIsIsoUtcWithZ() {
        when(service.createPayment("d1", "u1", new BigDecimal("190.00"))).thenReturn(payment("p1", "u1", "RESERVED"));

        CreatePaymentResponse response = stub.createPayment(CreatePaymentRequest.newBuilder()
                .setDealId("d1").setParticipantId("u1").setAmount("190.00").build());

        assertThat(response.getPaymentId()).isEqualTo("p1");
        assertThat(response.getReservedUntil()).isEqualTo("2026-10-28T09:05:00Z");
    }

    @Test
    void cancelPayment_returnsFailed() {
        when(service.cancelPayment("p1")).thenReturn(payment("p1", "u1", "FAILED"));

        CancelPaymentResponse response = stub.cancelPayment(CancelPaymentRequest.newBuilder().setPaymentId("p1").build());

        assertThat(response.getPaymentId()).isEqualTo("p1");
        assertThat(response.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void cancelPayment_mapsErrors() {
        when(service.cancelPayment("held")).thenThrow(new InvalidPaymentStateException());
        when(service.cancelPayment("nope")).thenThrow(new PaymentNotFoundException());

        assertThat(codeOf(() -> stub.cancelPayment(CancelPaymentRequest.newBuilder().setPaymentId("held").build())))
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(codeOf(() -> stub.cancelPayment(CancelPaymentRequest.newBuilder().setPaymentId("nope").build())))
                .isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void refundDeal_returnsCountAndEveryPayment() {
        when(service.refundDeal("d1")).thenReturn(new RefundOutcome(1,
                List.of(payment("p1", "u1", "REFUNDED"), payment("p2", "u2", "FAILED"))));

        RefundDealResponse response = stub.refundDeal(RefundDealRequest.newBuilder().setDealId("d1").build());

        assertThat(response.getRefundedCount()).isEqualTo(1);
        assertThat(response.getPaymentsList()).extracting(PaymentStatusItem::getPaymentId, PaymentStatusItem::getParticipantId,
                PaymentStatusItem::getStatus).containsExactly(
                org.assertj.core.groups.Tuple.tuple("p1", "u1", "REFUNDED"),
                org.assertj.core.groups.Tuple.tuple("p2", "u2", "FAILED"));
    }

    @Test
    void confirmReceipt_returnsConfirmedFlag_andForbiddenMapsToPermissionDenied() {
        Payment held = payment("p1", "u1", "HELD");
        held.setConfirmedReceipt(true);
        when(service.confirmReceipt("p1", "u1")).thenReturn(held);
        when(service.confirmReceipt("p1", "u2")).thenThrow(new com.harnkan.payment.exception.PaymentForbiddenException());

        ConfirmReceiptResponse response = stub.confirmReceipt(
                ConfirmReceiptRequest.newBuilder().setPaymentId("p1").setParticipantId("u1").build());

        assertThat(response.getStatus()).isEqualTo("HELD");
        assertThat(response.getIsConfirmedReceipt()).isTrue();
        assertThat(codeOf(() -> stub.confirmReceipt(
                ConfirmReceiptRequest.newBuilder().setPaymentId("p1").setParticipantId("u2").build())))
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void releaseFunds_returnsCountTotalAndPayments_andNotConfirmedIsFailedPrecondition() {
        when(service.releaseFunds("d1", "host-1")).thenReturn(new com.harnkan.payment.service.ReleaseOutcome(2,
                new BigDecimal("380.00"), List.of(payment("p1", "u1", "RELEASED"), payment("p2", "u2", "RELEASED"))));
        when(service.releaseFunds("d2", "host-1")).thenThrow(new InvalidPaymentStateException("not confirmed"));

        ReleaseFundsResponse response = stub.releaseFunds(
                ReleaseFundsRequest.newBuilder().setDealId("d1").setCreatorId("host-1").build());

        assertThat(response.getReleasedCount()).isEqualTo(2);
        assertThat(response.getTotalAmount()).isEqualTo("380.00");
        assertThat(response.getPaymentsList()).extracting(PaymentStatusItem::getStatus).containsExactly("RELEASED", "RELEASED");
        assertThat(codeOf(() -> stub.releaseFunds(
                ReleaseFundsRequest.newBuilder().setDealId("d2").setCreatorId("host-1").build())))
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    private static Status.Code codeOf(Runnable call) {
        try {
            call.run();
        } catch (StatusRuntimeException e) {
            return e.getStatus().getCode();
        }
        throw new AssertionError("expected a gRPC error");
    }
}
