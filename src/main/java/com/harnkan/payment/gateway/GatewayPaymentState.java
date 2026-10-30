package com.harnkan.payment.gateway;

/** Provider status used to repair missed callbacks; pending includes an unconfirmed intent. */
public enum GatewayPaymentState { PENDING, SUCCEEDED, FAILED }
