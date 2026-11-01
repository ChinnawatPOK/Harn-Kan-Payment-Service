package com.harnkan.payment.controller;

import com.harnkan.payment.auth.CurrentUser;
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

    /** PY5 */
    @PostMapping("/{paymentId}/pay")
    public StartPaymentResponse pay(@PathVariable String paymentId, CurrentUser user) {
        StartPaymentResult result = service.startPayment(paymentId, user.id());
        return new StartPaymentResponse(paymentId, "PAYMENT_PENDING", result.clientSecret());
    }

    /** PY2 */
    @GetMapping("/{paymentId}")
    public PaymentResponse get(@PathVariable String paymentId, CurrentUser user) {
        return PaymentResponse.from(service.getOwnPayment(paymentId, user.id()));
    }
}
