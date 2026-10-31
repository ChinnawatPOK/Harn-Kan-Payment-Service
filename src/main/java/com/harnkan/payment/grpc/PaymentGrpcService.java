package com.harnkan.payment.grpc;

import com.harnkan.contract.payment.*;
import com.harnkan.payment.exception.*;
import com.harnkan.payment.model.Payment;
import com.harnkan.payment.service.PaymentService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(PaymentGrpcService.class);
    private final PaymentService service;

    public PaymentGrpcService(PaymentService service) {
        this.service = service;
    }

    @Override
    public void createPayment(CreatePaymentRequest request, StreamObserver<CreatePaymentResponse> observer) {
        respond(observer, () -> {
            String amount = request.getAmount();
            if (!amount.matches("[0-9]{1,8}(\\.[0-9]{1,2})?")) {
                throw new IllegalArgumentException("amount must be a positive decimal string with at most two decimal places");
            }
            Payment payment = service.createPayment(request.getDealId(), request.getParticipantId(), new BigDecimal(amount));
            return CreatePaymentResponse.newBuilder()
                    .setPaymentId(payment.getId())
                    .setStatus(payment.getStatus())
                    .setReservedUntil(payment.getReservedUntil().toInstant(ZoneOffset.UTC).toString())
                    .build();
        });
    }

    @Override
    public void refundDeal(RefundDealRequest request, StreamObserver<RefundDealResponse> observer) {
        respond(observer, () -> {
            var result = service.refundDeal(request.getDealId());
            return RefundDealResponse.newBuilder().setRefundedCount(result.refundedCount())
                    .addAllPayments(result.payments().stream().map(this::statusItem).toList()).build();
        });
    }

    @Override
    public void cancelPayment(CancelPaymentRequest request, StreamObserver<CancelPaymentResponse> observer) {
        respond(observer, () -> {
            Payment payment = service.cancelPayment(request.getPaymentId());
            return CancelPaymentResponse.newBuilder().setPaymentId(payment.getId()).setStatus(payment.getStatus()).build();
        });
    }

    @Override
    public void confirmReceipt(ConfirmReceiptRequest request, StreamObserver<ConfirmReceiptResponse> observer) {
        respond(observer, () -> {
            Payment payment = service.confirmReceipt(request.getPaymentId(), request.getParticipantId());
            return ConfirmReceiptResponse.newBuilder().setPaymentId(payment.getId()).setStatus(payment.getStatus())
                    .setIsConfirmedReceipt(Boolean.TRUE.equals(payment.getConfirmedReceipt())).build();
        });
    }

    @Override
    public void releaseFunds(ReleaseFundsRequest request, StreamObserver<ReleaseFundsResponse> observer) {
        respond(observer, () -> {
            var result = service.releaseFunds(request.getDealId(), request.getCreatorId());
            return ReleaseFundsResponse.newBuilder().setReleasedCount(result.releasedCount())
                    .setTotalAmount(result.totalAmount().toPlainString())
                    .addAllPayments(result.payments().stream().map(this::statusItem).toList()).build();
        });
    }

    private PaymentStatusItem statusItem(Payment payment) {
        return PaymentStatusItem.newBuilder().setPaymentId(payment.getId())
                .setParticipantId(payment.getParticipantId()).setStatus(payment.getStatus()).build();
    }

    private <T> void respond(StreamObserver<T> observer, Supplier<T> action) {
        T result;
        try {
            result = action.get();
        } catch (Exception exception) {
            observer.onError(mapError(exception).asRuntimeException());
            return;
        }
        observer.onNext(result);
        observer.onCompleted();
    }

    private Status mapError(Exception exception) {
        Status status;
        if (exception instanceof IllegalArgumentException) status = Status.INVALID_ARGUMENT;
        else if (exception instanceof DuplicatePaymentException) status = Status.ALREADY_EXISTS;
        else if (exception instanceof PaymentAccessDeniedException) status = Status.PERMISSION_DENIED;
        else if (exception instanceof PaymentNotFoundException) status = Status.NOT_FOUND;
        else if (exception instanceof InvalidPaymentStateException || exception instanceof PaymentExpiredException)
            status = Status.FAILED_PRECONDITION;
        else if (exception instanceof PaymentGatewayException || exception instanceof PaymentBusyException) status = Status.UNAVAILABLE;
        else {
            log.error("PAYMENT_GRPC_ERROR type={}", exception.getClass().getSimpleName());
            return Status.INTERNAL.withDescription("An unexpected error occurred");
        }
        return status.withDescription(exception.getMessage());
    }
}
