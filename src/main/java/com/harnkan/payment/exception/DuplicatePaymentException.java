package com.harnkan.payment.exception;

public class DuplicatePaymentException extends RuntimeException {
    public DuplicatePaymentException() {
        super("An active payment already exists for this deal and participant");
    }
}
