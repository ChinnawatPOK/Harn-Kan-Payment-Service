package com.harnkan.payment.gateway;

/** Returned only when the gateway confirms that the refund has completed. */
public record RefundResult(String gatewayReference) {}
