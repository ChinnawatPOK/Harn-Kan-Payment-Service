package com.harnkan.payment.config;

import com.harnkan.payment.gateway.FakePaymentGateway;
import com.harnkan.payment.gateway.PaymentGateway;
import com.harnkan.payment.gateway.StripePaymentGateway;
import com.stripe.StripeClient;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentConfiguration {
    @Bean
    Clock paymentClock() {
        // DATETIME and the proto carry no offset: all application timestamps use UTC.
        return Clock.systemUTC();
    }

    @Bean
    PaymentGateway paymentGateway(@Value("${payment.gateway-mode:fake}") String mode,
            @Value("${payment.stripe.secret-key:}") String secretKey,
            @Value("${payment.stripe.webhook-secret:}") String webhookSecret) {
        return switch (mode) {
            case "fake" -> new FakePaymentGateway();
            case "stripe" -> {
                if (secretKey.isBlank() || webhookSecret.isBlank()) {
                    throw new IllegalStateException("Stripe mode requires STRIPE_SECRET_KEY and STRIPE_WEBHOOK_SECRET");
                }
                var client = StripeClient.builder().setApiKey(secretKey).setMaxNetworkRetries(1)
                        .setConnectTimeout(5000).setReadTimeout(15000).build();
                yield new StripePaymentGateway(client);
            }
            default -> throw new IllegalStateException("PAYMENT_GATEWAY_MODE must be fake or stripe");
        };
    }
}
