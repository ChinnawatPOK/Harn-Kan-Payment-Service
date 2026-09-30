package com.harnkan.payment.exception;

public class PaymentGatewayException extends RuntimeException {
    public PaymentGatewayException() {
        super("Payment gateway is unavailable; retry the operation");
    }
}
