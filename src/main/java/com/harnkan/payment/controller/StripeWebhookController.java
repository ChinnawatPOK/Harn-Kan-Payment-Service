package com.harnkan.payment.controller;

import com.harnkan.payment.exception.InvalidStripeWebhookException;
import com.harnkan.payment.exception.PaymentNotFoundException;
import com.harnkan.payment.service.PaymentService;
import com.stripe.net.Webhook;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ConditionalOnProperty(name = "payment.gateway-mode", havingValue = "stripe")
public class StripeWebhookController {
    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);
    private static final Set<String> TYPES = Set.of("payment_intent.succeeded", "payment_intent.payment_failed", "payment_intent.canceled");
    private final PaymentService service;
    private final String secret;
    private final Clock clock;
    private final JsonMapper json = new JsonMapper();

    public StripeWebhookController(PaymentService service, @Value("${payment.stripe.webhook-secret}") String secret, Clock clock) {
        this.service = service;
        this.secret = secret;
        this.clock = clock;
    }

    @PostMapping("/webhooks/stripe")
    public Map<String, Boolean> receive(@RequestBody byte[] rawBody,
            @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        JsonNode event;
        try {
            String payload = new String(rawBody, StandardCharsets.UTF_8);
            if (signature == null || signature.isBlank()) throw new InvalidStripeWebhookException();
            Webhook.Signature.verifyHeader(payload, signature, secret, 300L, clock);
            // Read only stable snapshot fields. This also accepts the CLI's older 2020-03-02 API version,
            // without using a typed SDK Event deserializer tied to a newer Stripe API version.
            event = json.readTree(payload);
            if (event == null || !event.path("type").isTextual()) throw new InvalidStripeWebhookException();
        } catch (Exception failure) {
            throw new InvalidStripeWebhookException();
        }
        String type = event.path("type").asText();
        if (!TYPES.contains(type)) return Map.of("received", true);
        String reference = event.path("data").path("object").path("id").asText("");
        if (!reference.startsWith("pi_") || reference.length() > 255) throw new InvalidStripeWebhookException();
        try {
            // Commits state + a durable Deal-delivery row, but never waits for Deal inside the webhook.
            service.handleStripeResult(reference, "payment_intent.succeeded".equals(type));
        } catch (PaymentNotFoundException unrelatedPayment) {
            // CLI listeners can forward other PaymentIntents belonging to this Stripe sandbox.
            log.info("STRIPE_UNRELATED_EVENT type={}", type);
        }
        return Map.of("received", true);
    }
}
