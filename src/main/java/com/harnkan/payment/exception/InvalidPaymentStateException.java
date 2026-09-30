package com.harnkan.payment.exception;

public class InvalidPaymentStateException extends RuntimeException {
    public InvalidPaymentStateException() {
        super("Payment is not in a valid state for this operation");
    }
}
