package com.harnkan.payment.service;

import com.harnkan.payment.model.Payment;
import java.util.List;

public record RefundOutcome(int refundedCount, List<Payment> payments) {}
