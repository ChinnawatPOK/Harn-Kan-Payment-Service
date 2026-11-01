package com.harnkan.payment.gateway;

import java.math.BigDecimal;

/** Immutable original THB payment used to calculate a deal's Stripe settlement total. */
public record PayoutPayment(String paymentId, String paymentGatewayRef, BigDecimal amount) {}
