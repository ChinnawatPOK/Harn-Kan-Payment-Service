package com.harnkan.payment.exception;

public class PaymentExpiredException extends RuntimeException {
    public PaymentExpiredException() {
        super("Payment reservation has expired");
    }
}
