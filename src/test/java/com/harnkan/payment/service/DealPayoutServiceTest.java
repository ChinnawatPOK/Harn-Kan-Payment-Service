package com.harnkan.payment.service;

import com.harnkan.payment.exception.*;
import com.harnkan.payment.gateway.*;
import com.harnkan.payment.model.Payment;
import com.harnkan.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DealPayoutServiceTest {
    PaymentRepository repository;
    PaymentGateway gateway;
    DealPayoutService service;
    List<Payment> rows;
    @BeforeEach void setup() {
        repository=mock(PaymentRepository.class);gateway=mock(PaymentGateway.class);service=new DealPayoutService(repository,gateway);
        rows=new ArrayList<>();
        when(repository.withDealLock(eq("deal1"),any())).thenAnswer(i->((Supplier<?>)i.getArgument(1)).get());
        when(repository.findByDealId("deal1")).thenAnswer(i->rows);
    }
    Payment row(String id,String status,String ref) {
        var p=new Payment();p.setId(id);p.setDealId("deal1");p.setAmount(new BigDecimal("189.00"));
        p.setPaymentGatewayRef("pi_"+id);p.setStatus(status);p.setConfirmedReceipt(true);p.setPaymentTransferGatewayRef(ref);
        rows.add(p);return p;
    }
    void providerSuccess(boolean allowCreate) {
        when(gateway.payoutDeal(eq("deal1"),eq("creator1"),anyList(),eq(allowCreate))).thenReturn(new PayoutResult("tr_deal"));
    }
    void saveRows() {
        when(repository.markReleased(anyString(),eq("tr_deal"))).thenAnswer(i->{
            var p=rows.stream().filter(r->r.getId().equals(i.getArgument(0))).findFirst().orElseThrow();
            p.setStatus("RELEASED");p.setPaymentTransferGatewayRef("tr_deal");return 1;
        });
    }
    @Test void tenPaymentsProduceOneTransferAndReuseExistingColumnThenSkipRepeat() {
        for(int n=0;n<10;n++)row("payment"+n,"HELD",null);
        providerSuccess(true);saveRows();
        var result=service.releaseFunds("deal1","creator1");
        assertThat(result.releasedCount()).isEqualTo(10);assertThat(result.totalAmount()).isEqualByComparingTo("1890.00");
        assertThat(rows).allMatch(p->"tr_deal".equals(p.getPaymentTransferGatewayRef()));
        assertThat(service.releaseFunds("deal1","creator1").releasedCount()).isEqualTo(10);
        verify(gateway,times(1)).payoutDeal(eq("deal1"),eq("creator1"),argThat(p->p.size()==10),eq(true));
    }
    @Test void unconfirmedOrUnresolvedPaymentPreventsAnyTransfer() {
        row("p1","HELD",null).setConfirmedReceipt(false);
        assertThatThrownBy(()->service.releaseFunds("deal1","creator1")).isInstanceOf(InvalidPaymentStateException.class);
        rows.clear();row("p1","RESERVED",null);
        assertThatThrownBy(()->service.releaseFunds("deal1","creator1")).isInstanceOf(InvalidPaymentStateException.class);
        verifyNoInteractions(gateway);
    }
    @Test void failedStripeCallKeepsRowsPendingAndWritesNoReference() {
        row("p1","HELD",null);row("p2","HELD",null);
        when(gateway.payoutDeal(anyString(),anyString(),anyList(),anyBoolean())).thenThrow(new PaymentGatewayException());
        assertThatThrownBy(()->service.releaseFunds("deal1","creator1")).isInstanceOf(PaymentGatewayException.class);
        verify(repository).updateStatus("p1","PAYOUT_PENDING");verify(repository).updateStatus("p2","PAYOUT_PENDING");
        verify(repository,never()).markReleased(anyString(),anyString());
    }
    @Test void partialDatabaseSaveRecoversSameTransferWithFullPaymentSet() {
        row("p1","RELEASED","tr_deal");row("p2","PAYOUT_PENDING",null);
        providerSuccess(false);saveRows();
        assertThat(service.releaseFunds("deal1","creator1").releasedCount()).isEqualTo(2);
        verify(gateway).payoutDeal(eq("deal1"),eq("creator1"),argThat(p->p.size()==2),eq(false));
        verify(repository,never()).markReleased(eq("p1"),anyString());
    }
    @Test void previousPerPaymentTransfersCannotBeAggregatedAndPaidAgain() {
        row("p1","RELEASED","tr_old1");row("p2","RELEASED","tr_old2");row("p3","PAYOUT_PENDING",null);
        assertThatThrownBy(()->service.releaseFunds("deal1","creator1")).isInstanceOf(InvalidPaymentStateException.class);
        verifyNoInteractions(gateway);
    }
    @Test void databaseFailureAfterStripeSuccessLeavesRecoverablePendingRows() {
        row("p1","HELD",null);row("p2","HELD",null);providerSuccess(true);
        when(repository.markReleased("p1","tr_deal")).thenAnswer(i->{rows.get(0).setStatus("RELEASED");rows.get(0).setPaymentTransferGatewayRef("tr_deal");return 1;});
        when(repository.markReleased("p2","tr_deal")).thenThrow(new RuntimeException("database offline"));
        assertThatThrownBy(()->service.releaseFunds("deal1","creator1")).hasMessage("database offline");
        assertThat(rows.get(0).getPaymentTransferGatewayRef()).isEqualTo("tr_deal");
        providerSuccess(false);
        doAnswer(i->{rows.get(1).setStatus("RELEASED");rows.get(1).setPaymentTransferGatewayRef("tr_deal");return 1;})
                .when(repository).markReleased("p2","tr_deal");
        assertThat(service.releaseFunds("deal1","creator1").releasedCount()).isEqualTo(2);
    }
}
