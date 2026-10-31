package com.harnkan.payment.exception;

/** Business ownership failure, independent of any authentication framework. */
public class PaymentAccessDeniedException extends RuntimeException {
    public PaymentAccessDeniedException(String message) {
        super(message);
    }
}
