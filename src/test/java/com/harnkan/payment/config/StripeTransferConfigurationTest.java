package com.harnkan.payment.config;

import com.harnkan.payment.controller.StripeTestTransferController;
import com.harnkan.payment.gateway.StripeTransferService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class StripeTransferConfigurationTest {
    ApplicationContextRunner runner = new ApplicationContextRunner()
            // same values application.yaml supplies when the service runs
            .withPropertyValues("payment.gateway-mode=fake", "payment.stripe.payout-mode=ledger",
                    "payment.stripe.secret-key=", "payment.stripe.sandbox-host-account-id=")
            .withBean(com.harnkan.payment.service.DealPayoutService.class, () -> org.mockito.Mockito.mock(com.harnkan.payment.service.DealPayoutService.class))
            .withUserConfiguration(PaymentConfiguration.class,StripeTestTransferController.class);
    @Test void testEndpointIsOffByDefaultAndExplicitlyEnabledForSandboxOnly() {
        runner.run(c -> { assertThat(c).hasNotFailed(); assertThat(c).doesNotHaveBean(StripeTestTransferController.class); });
        runner.withPropertyValues("payment.gateway-mode=stripe","payment.stripe.secret-key=sk_test_fixture",
                "payment.stripe.payout-mode=sandbox-transfer","payment.stripe.sandbox-host-account-id=acct_shared",
                "payment.stripe.test-transfer-enabled=true").run(c -> {
                    assertThat(c).hasNotFailed(); assertThat(c).hasSingleBean(StripeTestTransferController.class);
                    assertThat(c).hasSingleBean(StripeTransferService.class);
                });
    }
    @Test void liveKeyAndMissingDestinationFailStartup() {
        for (String key : new String[]{"sk_live_fixture","sk_test_fixture"}) {
            runner.withPropertyValues("payment.gateway-mode=stripe","payment.stripe.secret-key="+key,
                    "payment.stripe.payout-mode=sandbox-transfer",
                    "payment.stripe.sandbox-host-account-id="+(key.startsWith("sk_live")?"acct_shared":""))
                    .run(c -> assertThat(c).hasFailed());
        }
    }
}
