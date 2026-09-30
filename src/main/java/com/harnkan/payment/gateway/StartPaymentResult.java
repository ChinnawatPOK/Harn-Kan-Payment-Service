package com.harnkan.payment.gateway;

public record StartPaymentResult(String gatewayReference, String clientSecret) {}
