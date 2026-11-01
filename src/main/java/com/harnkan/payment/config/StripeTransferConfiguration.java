package com.harnkan.payment.config;

import com.harnkan.payment.gateway.StripeTransferService;
import com.stripe.StripeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Separate transfer wiring so the test endpoint can also run beside an existing Stripe checkout adapter. */
@Configuration
@ConditionalOnExpression("'${payment.gateway-mode:fake}' == 'stripe' and '${payment.stripe.payout-mode:ledger}' == 'sandbox-transfer'")
public class StripeTransferConfiguration {
    @Bean
    StripeTransferService stripeTransferService(@Value("${payment.stripe.secret-key:}") String key,
            @Value("${payment.stripe.sandbox-host-account-id:}") String destination) {
        var client = StripeClient.builder().setApiKey(key).setMaxNetworkRetries(1)
                .setConnectTimeout(5000).setReadTimeout(15000).build();
        return new StripeTransferService(client, key, destination);
    }
}
