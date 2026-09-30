package com.harnkan.payment.grpc;

import com.harnkan.contract.payment.*;
import com.harnkan.payment.exception.*;
import com.harnkan.payment.model.Payment;
import com.harnkan.payment.service.PaymentService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
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
                    .setReservedUntil(payment.getReservedUntil().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .build();
        });
    }

    @Override
    public void refundDeal(RefundDealRequest request, StreamObserver<RefundDealResponse> observer) {
        respond(observer, () -> RefundDealResponse.newBuilder()
                .setRefundedCount(service.refundDeal(request.getDealId())).build());
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
