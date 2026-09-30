package com.harnkan.payment.exception;

public class PaymentBusyException extends RuntimeException {
    public PaymentBusyException() {
        super("Payment is busy; retry the operation");
    }
}
