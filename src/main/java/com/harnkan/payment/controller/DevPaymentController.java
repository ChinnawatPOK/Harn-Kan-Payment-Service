package com.harnkan.payment.controller;

import com.harnkan.payment.dto.SimulatedPaymentResponse;
import com.harnkan.payment.model.Payment;
import com.harnkan.payment.service.PaymentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/dev/payments")
@ConditionalOnProperty(name = "payment.gateway-mode", havingValue = "fake", matchIfMissing = true)
public class DevPaymentController {
    private final PaymentService service;

    public DevPaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping("/{paymentId}/simulate-success")
    public SimulatedPaymentResponse simulateSuccess(@PathVariable String paymentId) {
        Payment payment = service.simulateSuccess(paymentId);
        return new SimulatedPaymentResponse(payment.getId(), payment.getStatus());
    }
}
