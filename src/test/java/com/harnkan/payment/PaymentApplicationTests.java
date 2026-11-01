package com.harnkan.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:context;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
    "grpc.server.port=0", "payment.gateway-mode=fake", "deal.callback.enabled=false"
})
class PaymentApplicationTests {

	@Test
	void contextLoads() {
	}

}
