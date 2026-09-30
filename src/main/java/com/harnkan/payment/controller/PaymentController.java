package com.harnkan.payment.controller;

import com.harnkan.payment.dto.PaymentResponse;
import com.harnkan.payment.dto.StartPaymentResponse;
import com.harnkan.payment.gateway.StartPaymentResult;
import com.harnkan.payment.service.PaymentService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping("/{paymentId}/pay")
    public StartPaymentResponse pay(@PathVariable String paymentId) {
        StartPaymentResult result = service.startPayment(paymentId);
        return new StartPaymentResponse(paymentId, "PAYMENT_PENDING", result.clientSecret());
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse get(@PathVariable String paymentId) {
        return PaymentResponse.from(service.getPayment(paymentId));
    }
}
