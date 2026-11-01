package com.harnkan.payment.service;

import com.harnkan.payment.model.Payment;
import java.math.BigDecimal;
import java.util.List;

/** Result of ReleaseFunds: how many payments of the deal are RELEASED, their sum, and every payment of the deal. */
public record ReleaseOutcome(int releasedCount, BigDecimal totalAmount, List<Payment> payments) {}
