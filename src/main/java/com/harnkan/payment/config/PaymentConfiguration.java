package com.harnkan.payment.config;

import com.harnkan.payment.gateway.FakePaymentGateway;
import com.harnkan.payment.gateway.PaymentGateway;
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
    PaymentGateway paymentGateway(@Value("${payment.gateway-mode:fake}") String mode) {
        if (!"fake".equals(mode)) {
            throw new IllegalStateException("Only PAYMENT_GATEWAY_MODE=fake is implemented in this phase; Stripe is not enabled");
        }
        return new FakePaymentGateway();
    }
}
