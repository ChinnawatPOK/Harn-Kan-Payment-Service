package com.harnkan.payment.dto;

public record StartPaymentResponse(String paymentId, String status, String clientSecret) {}
