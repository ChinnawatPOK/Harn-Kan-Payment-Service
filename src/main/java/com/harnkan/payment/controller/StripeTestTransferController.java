package com.harnkan.payment.controller;

import com.harnkan.payment.gateway.StripeTransferService;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Direct provider test only: does not change deal/payment rows or enforce receipt confirmation. */
@RestController
@RequestMapping("/internal/dev/stripe/transfers")
@ConditionalOnProperty(name = "payment.stripe.test-transfer-enabled", havingValue = "true")
public class StripeTestTransferController {
    private final StripeTransferService transfers;

    public StripeTestTransferController(StripeTransferService transfers) { this.transfers = transfers; }

    public record Request(String reference, String creatorId, String paymentIntentId, BigDecimal amount) {}

    @PostMapping
    public StripeTransferService.Result transfer(@RequestBody Request request) {
        return transfers.transfer(request.reference(), request.creatorId(), request.paymentIntentId(), request.amount());
    }
}
