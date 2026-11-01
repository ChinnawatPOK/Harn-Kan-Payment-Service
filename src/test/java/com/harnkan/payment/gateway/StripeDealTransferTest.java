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

class StripeDealTransferTest {
    HttpServer server; StripeTransferService service;
    int posts,code=200; boolean legacy, mixed, refunded;
    String existing="[]",key,query; Map<String,String> form;
    long availableAmount;
    int fundsFailures, successfulTransfers;
    java.util.Set<String> failedKeys = new java.util.HashSet<>();
    java.util.List<String> keys = new java.util.ArrayList<>();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        server.createContext("/v1/",e->{
            String response;int responseCode=200;String path=e.getRequestURI().getPath();
            if(path.equals("/v1/transfers")) {
                if(e.getRequestMethod().equals("POST")) {
                    posts++;key=e.getRequestHeaders().getFirst("Idempotency-Key");keys.add(key);form=new HashMap<>();
                    for(String pair:new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8).split("&")) {
                        var p=pair.split("=",2);form.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),URLDecoder.decode(p[1],StandardCharsets.UTF_8));
                    }
                    responseCode=code;
                    response=code==200?"""
                      {"object":"transfer","id":"tr_deal","livemode":false,"reversed":false,"amount":%s,
                       "currency":"%s","destination":"%s","transfer_group":"%s",
                       "metadata":{"dealId":"%s","hostUserId":"%s","paymentsHash":"%s"}}
                      """.formatted(form.get("amount"),form.get("currency"),form.get("destination"),form.get("transfer_group"),
                        form.get("metadata[dealId]"),form.get("metadata[hostUserId]"),form.get("metadata[paymentsHash]"))
                      :"{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"insufficient_funds\",\"message\":\"private detail\"}}";
                    if (failedKeys.contains(key) || fundsFailures > 0) {
                        if (!failedKeys.contains(key)) { failedKeys.add(key); fundsFailures--; }
                        responseCode=400;
                        response="{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"balance_insufficient\",\"message\":\"cached provider failure\"}}";
                    }
                    if(responseCode==200) { successfulTransfers++;existing="["+response+"]"; }
                } else {
                    query=e.getRequestURI().getRawQuery();
                    String data=query.contains("payment_")?(legacy?"[{\"id\":\"tr_old\",\"object\":\"transfer\"}]":"[]"):existing;
                    response="{\"object\":\"list\",\"url\":\"/v1/transfers\",\"has_more\":false,\"data\":"+data+"}";
                }
            } else if (path.equals("/v1/balance")) {
                response="{\"object\":\"balance\",\"available\":[{\"amount\":"+availableAmount+",\"currency\":\"usd\"}],\"pending\":[]}";
            } else {
                String suffix=path.substring(path.lastIndexOf("pi_")+3);
                response="""
                  {"object":"payment_intent","id":"pi_%s","status":"succeeded","livemode":false,"currency":"thb",
                   "amount":18900,"metadata":{"paymentId":"p%s"},"latest_charge":{"object":"charge","id":"ch_%s",
                   "paid":true,"amount_refunded":%d,"balance_transaction":{"object":"balance_transaction","id":"txn_%s",
                   "amount":%d,"currency":"%s"}}}
                  """.formatted(suffix,suffix,suffix,refunded?18900:0,suffix,mixed&&suffix.equals("9")?18900:560,
                    mixed&&suffix.equals("9")?"thb":"usd");
            }
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");
            e.sendResponseHeaders(responseCode,bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();
        var client=StripeClient.builder().setApiKey("sk_test_fixture").setMaxNetworkRetries(0)
                .setApiBase("http://localhost:"+server.getAddress().getPort()).build();
        service=new StripeTransferService(client,"sk_test_fixture","acct_shared");
    }
    @AfterEach void stop(){server.stop(0);}
    List<PayoutPayment> payments(){return java.util.stream.IntStream.range(0,10)
        .mapToObj(n->new PayoutPayment("p"+n,"pi_"+n,new BigDecimal("189.00"))).toList();}
    @Test void tenPaymentsMakeOneTransferOfSettlementTotalAndStableRetry() {
        var result=service.transferDeal("deal1","creator1",payments(),true);
        assertThat(result.currency()).isEqualTo("usd");assertThat(result.amount()).isEqualByComparingTo("56.00");
        assertThat(form).containsEntry("amount","5600").containsEntry("currency","usd")
                .containsEntry("metadata[paymentCount]","10").containsEntry("metadata[originalAmount]","1890.00")
                .containsEntry("transfer_group","deal_deal1").doesNotContainKey("source_transaction");
        assertThat(key).isEqualTo("release-deal-deal1");
        var reversed=new ArrayList<>(payments());Collections.reverse(reversed);
        assertThat(service.transferDeal("deal1","creator1",reversed,false)).isEqualTo(result);
        assertThat(posts).isEqualTo(1);
    }
    @Test void changedMembershipOrCreatorCannotCauseAnotherTransfer() {
        service.transferDeal("deal1","creator1",payments(),true);
        assertThatThrownBy(()->service.transferDeal("deal1","creator1",payments().subList(0,9),true)).isInstanceOf(PaymentGatewayException.class);
        assertThatThrownBy(()->service.transferDeal("deal1","creator2",payments(),true)).isInstanceOf(PaymentGatewayException.class);
        assertThat(posts).isEqualTo(1);
    }
    @Test void partialDatabaseRecoveryCannotCreateANewTransfer() {
        assertThatThrownBy(()->service.transferDeal("deal1","creator1",payments(),false)).isInstanceOf(PaymentGatewayException.class);
        assertThat(posts).isZero();
    }
    @Test void mixedCurrenciesRefundsAndOldSingleTransfersAreRejected() {
        mixed=true;assertThatThrownBy(()->service.transferDeal("deal1","creator1",payments(),true)).isInstanceOf(PaymentGatewayException.class);
        mixed=false;refunded=true;assertThatThrownBy(()->service.transferDeal("deal1","creator1",payments(),true)).isInstanceOf(PaymentGatewayException.class);
        refunded=false;legacy=true;assertThatThrownBy(()->service.transferDeal("deal1","creator1",payments(),true)).isInstanceOf(PaymentGatewayException.class);
        assertThat(posts).isZero();
    }
    @Test void insufficientAvailableBalanceIsNotReportedAsSuccess() {
        code=400;assertThatThrownBy(()->service.transferDeal("deal1","creator1",payments(),true))
                .isInstanceOf(PaymentGatewayException.class).hasMessageNotContaining("private detail");
    }
    @Test void repeatedPaymentIntentIsNotCountedTwice() {
        var sources=new ArrayList<>(payments());sources.set(9,new PayoutPayment("p9","pi_0",new BigDecimal("189.00")));
        assertThatThrownBy(()->service.transferDeal("deal1","creator1",sources,true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(posts).isZero();
    }
    @Test void fundingAfterCachedFailureUsesAStableNewKeyAndCreatesOnlyOneTransfer() {
        fundsFailures=1;
        assertThatThrownBy(()->service.transferDeal("deal1","creator1",payments(),true)).isInstanceOf(PaymentGatewayException.class);
        assertThat(posts).isEqualTo(1);
        availableAmount=9410;
        var result=service.transferDeal("deal1","creator1",payments(),true);
        assertThat(result.transferId()).isEqualTo("tr_deal");
        assertThat(keys).containsExactly("release-deal-deal1","release-deal-deal1","release-deal-deal1-funded-1");
        assertThat(successfulTransfers).isEqualTo(1);
        assertThat(service.transferDeal("deal1","creator1",payments(),true)).isEqualTo(result);
        assertThat(successfulTransfers).isEqualTo(1);
    }
    @Test void repeatedDefinitiveFundFailuresAdvanceStableKeysButStayBounded() {
        availableAmount=9410;fundsFailures=2;
        assertThat(service.transferDeal("deal1","creator1",payments(),true).transferId()).isEqualTo("tr_deal");
        assertThat(keys).containsExactly("release-deal-deal1","release-deal-deal1-funded-1","release-deal-deal1-funded-2");
        assertThat(successfulTransfers).isEqualTo(1);
    }
    @Test void persistentInsufficientFundsDoesNotRetryForever() {
        availableAmount=9410;fundsFailures=10;
        assertThatThrownBy(()->service.transferDeal("deal1","creator1",payments(),true)).isInstanceOf(PaymentGatewayException.class);
        assertThat(posts).isEqualTo(4);assertThat(successfulTransfers).isZero();
    }
    @Test void ambiguousProviderErrorNeverRotatesTheIdempotencyKey() {
        code=500;availableAmount=9410;
        assertThatThrownBy(()->service.transferDeal("deal1","creator1",payments(),true)).isInstanceOf(PaymentGatewayException.class);
        assertThat(keys).containsExactly("release-deal-deal1");assertThat(successfulTransfers).isZero();
    }

}
