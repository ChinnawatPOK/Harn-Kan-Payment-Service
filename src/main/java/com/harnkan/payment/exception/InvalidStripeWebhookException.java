package com.harnkan.payment.exception;

public class InvalidStripeWebhookException extends RuntimeException {
    public InvalidStripeWebhookException() {
        super("Invalid Stripe webhook signature or payload");
    }
}
