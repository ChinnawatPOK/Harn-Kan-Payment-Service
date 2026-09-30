package com.harnkan.payment.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    public record ErrorBody(String code, String message) {}

    @ExceptionHandler(PaymentNotFoundException.class)
    ResponseEntity<ErrorBody> notFound(PaymentNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(PaymentExpiredException.class)
    ResponseEntity<ErrorBody> expired(PaymentExpiredException exception) {
        return error(HttpStatus.CONFLICT, "PAYMENT_EXPIRED", exception.getMessage());
    }

    @ExceptionHandler(InvalidPaymentStateException.class)
    ResponseEntity<ErrorBody> invalidState(InvalidPaymentStateException exception) {
        return error(HttpStatus.CONFLICT, "INVALID_PAYMENT_STATE", exception.getMessage());
    }

    @ExceptionHandler(DuplicatePaymentException.class)
    ResponseEntity<ErrorBody> duplicate(DuplicatePaymentException exception) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_PAYMENT", exception.getMessage());
    }

    @ExceptionHandler(PaymentGatewayException.class)
    ResponseEntity<ErrorBody> gateway(PaymentGatewayException exception) {
        return error(HttpStatus.BAD_GATEWAY, "PAYMENT_GATEWAY_ERROR", exception.getMessage());
    }

    @ExceptionHandler(PaymentBusyException.class)
    ResponseEntity<ErrorBody> busy(PaymentBusyException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_BUSY", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorBody> badInput(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception exception) {
        // Exception messages can contain JDBC credentials or sensitive gateway payloads.
        log.error("PAYMENT_UNEXPECTED_ERROR type={}", exception.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(code, message));
    }
}
