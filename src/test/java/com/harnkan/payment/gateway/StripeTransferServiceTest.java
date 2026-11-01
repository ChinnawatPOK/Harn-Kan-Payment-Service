package com.harnkan.payment.gateway;

import com.harnkan.payment.exception.PaymentGatewayException;
import com.stripe.StripeClient;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class StripeTransferServiceTest {
    HttpServer server;
    StripeClient client;
    StripeTransferService service;
    String currency = "thb", status = "succeeded", host = "creator1", destination = "acct_shared", existing = "[]";
    boolean live, reversed;
    long refunded;
    long settlementAmount = 18900;
    int posts, transferStatus = 200;
    String idempotencyKey;
    Map<String,String> posted;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/", exchange -> {
            int code = 200;
            String response;
            if (exchange.getRequestURI().getPath().equals("/v1/transfers")) {
                if (exchange.getRequestMethod().equals("POST")) {
                    posts++;
                    idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                    posted = new HashMap<>();
                    for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                        var parts = pair.split("=",2);
                        posted.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),URLDecoder.decode(parts[1],StandardCharsets.UTF_8));
                    }
                    code = transferStatus;
                    host = posted.get("metadata[hostUserId]");
                    response = code == 200 ? transfer() : "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"private-provider-detail\"}}";
                    if (code == 200) existing = "[" + response + "]";
                } else response = "{\"object\":\"list\",\"url\":\"/v1/transfers\",\"has_more\":false,\"data\":" + existing + "}";
            } else response = """
                {"object":"payment_intent","id":"pi_paid","livemode":%s,"status":"%s","amount":18900,
                 "currency":"thb","metadata":{"paymentId":"payment1"},
                 "latest_charge":{"object":"charge","id":"ch_paid","paid":true,"amount_refunded":%d,
                 "balance_transaction":{"object":"balance_transaction","id":"txn_paid","currency":"%s","amount":%d}}}
                """.formatted(live,status,refunded,currency,settlementAmount);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(code,bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        client = StripeClient.builder().setApiKey("sk_test_fixture").setMaxNetworkRetries(0)
                .setApiBase("http://localhost:" + server.getAddress().getPort()).build();
        service = new StripeTransferService(client,"sk_test_fixture","acct_shared");
    }
    @AfterEach void cleanup() { server.stop(0); }
    String transfer() { return """
        {"object":"transfer","id":"tr_test","livemode":false,"reversed":%s,"amount":%d,"currency":"%s",
         "destination":"%s","source_transaction":"ch_paid","metadata":{"paymentId":"payment1","hostUserId":"%s"}}
        """.formatted(reversed,settlementAmount,currency,destination,host); }
    StripeTransferService.Result release() { return service.transfer("payment1",host,"pi_paid",new BigDecimal("189.00")); }

    @Test void sendsThbToSharedDestinationForAnyCreatorAndRetriesWithoutDuplicate() {
        host = "797fcdfd-4299-4446-aa1e-58bca8c3ed4f";
        var result = release();
        assertThat(result.transferId()).isEqualTo("tr_test");
        assertThat(result.destination()).isEqualTo("acct_shared");
        assertThat(posted).containsEntry("amount","18900").containsEntry("currency","thb")
                .containsEntry("destination","acct_shared").containsEntry("source_transaction","ch_paid")
                .containsEntry("transfer_group","payment_payment1").containsEntry("metadata[hostUserId]",host);
        assertThat(idempotencyKey).isEqualTo("release-payment1");
        assertThat(release()).isEqualTo(result);
        assertThat(posts).isEqualTo(1);
    }
    @Test void usdSettlementUsesStripeAmountNotTheOriginalBahtAmountAndRetriesOnce() {
        currency = "usd";
        settlementAmount = 560;
        var result = release();
        assertThat(result.currency()).isEqualTo("usd");
        assertThat(result.amount()).isEqualByComparingTo("5.60");
        assertThat(posted).containsEntry("currency","usd").containsEntry("amount","560")
                .containsEntry("metadata[originalCurrency]","thb").containsEntry("metadata[originalAmount]","189.00");
        assertThat(release()).isEqualTo(result);
        assertThat(posts).isEqualTo(1);
    }
    @Test void unsupportedCurrencyAndInvalidSettlementAmountNeverCreateTransfers() {
        currency = "jpy";
        assertThatThrownBy(this::release).isInstanceOf(PaymentGatewayException.class);
        currency = "usd"; settlementAmount = 0;
        assertThatThrownBy(this::release).isInstanceOf(PaymentGatewayException.class);
        currency = "thb"; settlementAmount = 560;
        assertThatThrownBy(this::release).isInstanceOf(PaymentGatewayException.class);
        assertThat(posts).isZero();
    }
    @Test void liveUnpaidAndRefundedSourcesAreRejected() {
        live = true; assertThatThrownBy(this::release).isInstanceOf(PaymentGatewayException.class);
        live = false; status = "requires_payment_method";
        assertThatThrownBy(this::release).isInstanceOf(PaymentGatewayException.class);
        status = "succeeded"; refunded = 1;
        assertThatThrownBy(this::release).isInstanceOf(PaymentGatewayException.class);
        assertThat(posts).isZero();
    }
    @Test void mismatchedMetadataOrAmountCannotSpendAnotherPayment() {
        assertThatThrownBy(() -> service.transfer("anotherPayment",host,"pi_paid",new BigDecimal("189.00")))
                .isInstanceOf(PaymentGatewayException.class);
        assertThatThrownBy(() -> service.transfer("payment1",host,"pi_paid",new BigDecimal("188.00")))
                .isInstanceOf(PaymentGatewayException.class);
        assertThat(posts).isZero();
    }
    @Test void mismatchedOrReversedExistingTransferCannotReportSuccess() {
        destination = "acct_other"; existing = "["+transfer()+"]";
        assertThatThrownBy(this::release).isInstanceOf(PaymentGatewayException.class);
        destination = "acct_shared"; reversed = true; existing = "["+transfer()+"]";
        assertThatThrownBy(this::release).isInstanceOf(PaymentGatewayException.class);
        assertThat(posts).isZero();
    }
    @Test void providerFailureDoesNotReturnFakeSuccessOrPrivateDetails() {
        transferStatus = 400;
        assertThatThrownBy(this::release).isInstanceOf(PaymentGatewayException.class)
                .hasMessageNotContaining("private-provider-detail");
    }
    @Test void inputAndLiveKeysAreRejectedBeforeAnyRequest() {
        assertThatThrownBy(() -> new StripeTransferService(client,"sk_live_fixture","acct_shared"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StripeTransferService(client,"sk_test_fixture",""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.transfer("payment1",host,"fake_pay_x",BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.transfer("payment1",host,"pi_paid",new BigDecimal("1.001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(posts).isZero();
    }
    @Test void gatewayUsesPaidSourceAndNeverFallsBackToLedger() {
        var gateway = new StripePaymentGateway(client,service);
        assertThat(gateway.paymentState("pi_paid")).isEqualTo(GatewayPaymentState.SUCCEEDED);
        assertThat(gateway.payout(host,new BigDecimal("189.00"),"payment1","pi_paid").gatewayReference()).isEqualTo("tr_test");
        assertThatThrownBy(() -> gateway.payout(host,BigDecimal.ONE,"payment1"))
                .isInstanceOf(PaymentGatewayException.class);
    }
}
