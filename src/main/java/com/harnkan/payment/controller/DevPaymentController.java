package com.harnkan.payment.controller;

import com.harnkan.payment.service.PaymentService;
import com.harnkan.payment.exception.PaymentNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Explicitly opt in on a development instance; Gateway must block internal routes. */
@RestController
@RequestMapping("/internal/dev/payments")
@ConditionalOnProperty(name = "payment.gateway-mode", havingValue = "fake", matchIfMissing = true)
public class DevPaymentController {
    private final PaymentService service;
    private final boolean enabled;

    public DevPaymentController(PaymentService service,
            @Value("${payment.dev.enabled:false}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    @PostMapping("/{paymentId}/simulate-success")
    public Map<String, String> simulateSuccess(@PathVariable String paymentId) {
        if (!enabled) throw new PaymentNotFoundException();
        var payment = service.simulateSuccess(paymentId);
        return Map.of("paymentId", payment.getId(), "status", payment.getStatus());
    }
}
