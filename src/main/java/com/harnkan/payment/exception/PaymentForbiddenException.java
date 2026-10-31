package com.harnkan.payment.exception;

public class PaymentForbiddenException extends RuntimeException {
    public PaymentForbiddenException() {
        super("You are not the owner of this payment");
    }
}
