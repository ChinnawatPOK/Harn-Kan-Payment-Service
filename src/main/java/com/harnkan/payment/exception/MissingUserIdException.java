package com.harnkan.payment.exception;

public class MissingUserIdException extends RuntimeException {
    public MissingUserIdException() {
        super("X-User-Id header is required");
    }
}
