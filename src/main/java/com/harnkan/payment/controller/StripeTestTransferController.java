package com.harnkan.payment.controller;

import com.harnkan.payment.service.DealPayoutService;
import com.harnkan.payment.service.ReleaseOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Explicit sandbox test entry point using the same receipt checks and persistence as ReleaseFunds. */
@RestController
@RequestMapping("/internal/dev/stripe/transfers")
@ConditionalOnProperty(name = "payment.stripe.test-transfer-enabled", havingValue = "true")
public class StripeTestTransferController {
    private final DealPayoutService payouts;
    public StripeTestTransferController(DealPayoutService payouts) { this.payouts = payouts; }
    public record DealRequest(String dealId, String creatorId) {}

    @PostMapping
    public ReleaseOutcome releaseDeal(@RequestBody DealRequest request) {
        return payouts.releaseFunds(request.dealId(), request.creatorId());
    }
}
