package com.harnkan.payment.config;

import com.harnkan.payment.gateway.*;
import com.stripe.StripeClient;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(StripeTransferConfiguration.class)
public class PaymentConfiguration {
    @Bean Clock paymentClock() { return Clock.systemUTC(); }

    @Bean
    @ConditionalOnProperty(name = "payment.gateway-mode", havingValue = "stripe")
    StripeClient stripeClient(@Value("${payment.stripe.secret-key:}") String key) {
        if (key.isBlank()) throw new IllegalArgumentException("Stripe mode requires STRIPE_SECRET_KEY");
        return StripeClient.builder().setApiKey(key).setMaxNetworkRetries(1)
                .setConnectTimeout(5000).setReadTimeout(15000).build();
    }

    @Bean
    PaymentGateway paymentGateway(@Value("${payment.gateway-mode:fake}") String mode,
            @Value("${payment.stripe.payout-mode:ledger}") String payoutMode,
            ObjectProvider<StripeClient> clients, ObjectProvider<StripeTransferService> transfers) {
        if (!java.util.Set.of("ledger", "sandbox-transfer").contains(payoutMode))
            throw new IllegalArgumentException("STRIPE_PAYOUT_MODE must be ledger or sandbox-transfer");
        return switch (mode) {
            case "fake" -> new FakePaymentGateway();
            case "stripe" -> new StripePaymentGateway(clients.getObject(), transfers.getIfAvailable());
            default -> throw new IllegalArgumentException("PAYMENT_GATEWAY_MODE must be fake or stripe");
        };
    }
}
