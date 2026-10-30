package com.harnkan.payment.gateway;

/** Implementations deduplicate payouts by reference, including retries after a crash. */
public record PayoutResult(String gatewayReference) {}
