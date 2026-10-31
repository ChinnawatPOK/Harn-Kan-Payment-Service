package com.harnkan.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harnkan.payment.client.DealServiceClient;
import com.harnkan.payment.exception.*;
import com.harnkan.payment.gateway.PaymentGateway;
import com.harnkan.payment.gateway.RefundResult;
import com.harnkan.payment.gateway.StartPaymentResult;
import com.harnkan.payment.model.Payment;
import com.harnkan.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {
    private static final String DEAL = "d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1";
    private static final String PAYMENT = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1";
    private static final String UUID_USER = "123e4567-e89b-12d3-a456-426614174000";
    private static final Instant NOW = Instant.parse("2026-10-28T09:00:00Z");

    @Mock private PaymentRepository repository;
    @Mock private PaymentGateway gateway;
    @Mock private DealServiceClient dealClient;

    private final AtomicBoolean lockHeld = new AtomicBoolean();
    private PaymentService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Runs the action like the real lock does, and records whether the lock is currently held.
        org.mockito.Mockito.lenient().when(repository.withDealLock(anyString(), any())).thenAnswer(invocation -> {
            lockHeld.set(true);
            try {
                return ((Supplier<Object>) invocation.getArgument(1)).get();
            } finally {
                lockHeld.set(false);
            }
        });
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            assertThat(lockHeld).as("Deal must be called after the deal lock is released").isFalse();
            return null;
        }).when(dealClient).updateParticipantPayment(any(), anyString());
        service = new PaymentService(repository, gateway, Clock.fixed(NOW, ZoneOffset.UTC), dealClient);
    }

    private Payment payment(String status, String participant) {
        Payment p = new Payment();
        p.setId(PAYMENT);
        p.setDealId(DEAL);
        p.setParticipantId(participant);
        p.setAmount(new BigDecimal("190.00"));
        p.setStatus(status);
        p.setReservedUntil(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(3));
        p.setPaymentGatewayRef("fake_pay_" + PAYMENT);
        return p;
    }

    private void stored(Payment p) {
        when(repository.findById(PAYMENT)).thenReturn(Optional.of(p));
    }

    // ---- create --------------------------------------------------------------------------------

    @Test
    void createPayment_acceptsUuidUserIds_upTo64Chars() {
        Payment created = service.createPayment(DEAL, UUID_USER, new BigDecimal("190.00"));

        assertThat(created.getParticipantId()).isEqualTo(UUID_USER);
        assertThat(created.getStatus()).isEqualTo("RESERVED");
        assertThat(created.getReservedUntil()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(5));
        verify(repository).insert(created);
    }

    @Test
    void createPayment_rejectsTooLongParticipantAndDealIds() {
        assertThatThrownBy(() -> service.createPayment(DEAL, "x".repeat(65), BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createPayment("d".repeat(33), "u1", BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- cancel (PG2) --------------------------------------------------------------------------

    @Test
    void cancelPayment_reservedAndPending_becomeFailed_withoutCallingDeal() {
        for (String status : List.of("RESERVED", "PAYMENT_PENDING")) {
            stored(payment(status, "u1"));

            Payment result = service.cancelPayment(PAYMENT);

            assertThat(result.getStatus()).isEqualTo("FAILED");
        }
        verify(repository, times(2)).updateStatus(PAYMENT, "FAILED");
        verify(dealClient, never()).updateParticipantPayment(any(), anyString());
    }

    @Test
    void cancelPayment_failedAgain_isIdempotent() {
        stored(payment("FAILED", "u1"));

        assertThat(service.cancelPayment(PAYMENT).getStatus()).isEqualTo("FAILED");

        verify(repository, never()).updateStatus(anyString(), anyString());
    }

    @Test
    void cancelPayment_heldOrRefunded_isInvalidState() {
        stored(payment("HELD", "u1"));
        assertThatThrownBy(() -> service.cancelPayment(PAYMENT)).isInstanceOf(InvalidPaymentStateException.class);
        stored(payment("REFUNDED", "u1"));
        assertThatThrownBy(() -> service.cancelPayment(PAYMENT)).isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    void cancelPayment_unknown_isNotFound() {
        when(repository.findById(PAYMENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelPayment(PAYMENT)).isInstanceOf(PaymentNotFoundException.class);
    }

    // ---- pay (PY5) -----------------------------------------------------------------------------

    @Test
    void startPayment_owner_startsGateway_thenTellsDealPaymentPending() {
        Payment p = payment("RESERVED", UUID_USER);
        stored(p);
        when(gateway.startPayment(PAYMENT, p.getAmount())).thenReturn(new StartPaymentResult("ref-1", "secret-1"));
        when(repository.updatePaymentStarted(PAYMENT, "ref-1", "PAYMENT_PENDING")).thenReturn(1);

        StartPaymentResult result = service.startPayment(PAYMENT, UUID_USER);

        assertThat(result.clientSecret()).isEqualTo("secret-1");
        verify(dealClient).updateParticipantPayment(p, "PAYMENT_PENDING");
    }

    @Test
    void startPayment_notTheOwner_isForbidden() {
        stored(payment("RESERVED", "u1"));

        assertThatThrownBy(() -> service.startPayment(PAYMENT, "someone-else")).isInstanceOf(PaymentForbiddenException.class);

        verify(gateway, never()).startPayment(anyString(), any());
        verify(dealClient, never()).updateParticipantPayment(any(), anyString());
    }

    @Test
    void startPayment_expired_failsPayment_andTellsDealFailed() {
        Payment p = payment("RESERVED", "u1");
        p.setReservedUntil(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusSeconds(1));
        stored(p);

        assertThatThrownBy(() -> service.startPayment(PAYMENT, "u1")).isInstanceOf(PaymentExpiredException.class);

        verify(repository).updateStatus(PAYMENT, "FAILED");
        verify(dealClient).updateParticipantPayment(p, "FAILED");
        verify(gateway, never()).startPayment(anyString(), any());
    }

    @Test
    void startPayment_notReserved_isInvalidState_andDealIsNotTold() {
        stored(payment("PAYMENT_PENDING", "u1"));

        assertThatThrownBy(() -> service.startPayment(PAYMENT, "u1")).isInstanceOf(InvalidPaymentStateException.class);

        verify(dealClient, never()).updateParticipantPayment(any(), anyString());
    }

    // ---- simulate-success (PY4) ----------------------------------------------------------------

    @Test
    void simulateSuccess_pending_becomesHeld_andDealIsTold() {
        Payment p = payment("PAYMENT_PENDING", "u1");
        stored(p);

        Payment result = service.simulateSuccess(PAYMENT);

        assertThat(result.getStatus()).isEqualTo("HELD");
        verify(repository).updateStatus(PAYMENT, "HELD");
        verify(dealClient).updateParticipantPayment(p, "HELD");
    }

    @Test
    void simulateSuccess_alreadyHeld_resendsToDeal_withoutChangingTheRow() {
        Payment p = payment("HELD", "u1");
        stored(p);

        assertThat(service.simulateSuccess(PAYMENT).getStatus()).isEqualTo("HELD");

        verify(repository, never()).updateStatus(anyString(), anyString());
        verify(dealClient).updateParticipantPayment(p, "HELD");
    }

    @Test
    void simulateSuccess_reservedOrFailed_isInvalidState() {
        stored(payment("RESERVED", "u1"));
        assertThatThrownBy(() -> service.simulateSuccess(PAYMENT)).isInstanceOf(InvalidPaymentStateException.class);
        stored(payment("FAILED", "u1"));
        assertThatThrownBy(() -> service.simulateSuccess(PAYMENT)).isInstanceOf(InvalidPaymentStateException.class);

        verify(dealClient, never()).updateParticipantPayment(any(), anyString());
    }

    // ---- gateway callbacks ---------------------------------------------------------------------

    @Test
    void handlePaymentSucceeded_pending_tellsDealHeld_butDuplicateEventDoesNot() {
        Payment p = payment("PAYMENT_PENDING", "u1");
        when(repository.findByGatewayReference("ref-1")).thenReturn(Optional.of(p));
        stored(p);

        service.handlePaymentSucceeded("ref-1");
        verify(dealClient).updateParticipantPayment(p, "HELD");

        // The row is HELD now; a repeated Stripe event changes nothing and tells nobody.
        service.handlePaymentSucceeded("ref-1");
        verify(dealClient, times(1)).updateParticipantPayment(any(), eq("HELD"));
    }

    // ---- refund (PG4) --------------------------------------------------------------------------

    @Test
    void refundDeal_refundsHeld_failsUnpaid_andReturnsEveryRow() {
        Payment held = payment("HELD", "u1");
        held.setId("h".repeat(32));
        Payment reserved = payment("RESERVED", "u2");
        reserved.setId("r".repeat(32));
        when(repository.findByDealId(DEAL)).thenReturn(List.of(held, reserved));
        when(gateway.refund(held.getPaymentGatewayRef(), held.getAmount())).thenReturn(new RefundResult("refund-1"));
        when(repository.markRefunded(held.getId(), "refund-1")).thenReturn(1);

        RefundOutcome outcome = service.refundDeal(DEAL);

        assertThat(outcome.refundedCount()).isEqualTo(1);
        assertThat(outcome.payments()).hasSize(2);
        verify(repository).updateStatus(reserved.getId(), "FAILED");
        verify(repository).updateStatus(held.getId(), "REFUND_PENDING");
    }

    @Test
    void refundDeal_gatewayFailure_surfacesAsGatewayException_soDealRetries() {
        Payment held = payment("HELD", "u1");
        when(repository.findByDealId(DEAL)).thenReturn(List.of(held));
        when(gateway.refund(anyString(), any())).thenThrow(new PaymentGatewayException());

        assertThatThrownBy(() -> service.refundDeal(DEAL)).isInstanceOf(PaymentGatewayException.class);

        verify(repository).updateStatus(PAYMENT, "REFUND_PENDING");
        verify(repository, never()).markRefunded(anyString(), anyString());
    }

    // ---- ownership on read ---------------------------------------------------------------------

    @Test
    void getOwnPayment_checksOwner() {
        stored(payment("RESERVED", "u1"));

        assertThat(service.getOwnPayment(PAYMENT, "u1").getId()).isEqualTo(PAYMENT);
        assertThatThrownBy(() -> service.getOwnPayment(PAYMENT, "u2")).isInstanceOf(PaymentForbiddenException.class);
    }

    // ---- confirm receipt (PG5) -----------------------------------------------------------------

    @Test
    void confirmReceipt_heldPaymentOfThatParticipant_isConfirmed_withoutCallingDeal() {
        stored(payment("HELD", UUID_USER));

        Payment result = service.confirmReceipt(PAYMENT, UUID_USER);

        assertThat(result.getConfirmedReceipt()).isTrue();
        assertThat(result.getStatus()).isEqualTo("HELD");
        verify(repository).markConfirmedReceipt(PAYMENT);
        verify(dealClient, never()).updateParticipantPayment(any(), anyString());
    }

    @Test
    void confirmReceipt_again_changesNothing() {
        Payment p = payment("HELD", "u1");
        p.setConfirmedReceipt(true);
        stored(p);

        assertThat(service.confirmReceipt(PAYMENT, "u1").getConfirmedReceipt()).isTrue();

        verify(repository, never()).markConfirmedReceipt(anyString());
    }

    @Test
    void confirmReceipt_afterPayout_answersTheSame() {
        Payment p = payment("RELEASED", "u1");
        p.setConfirmedReceipt(true);
        stored(p);

        assertThat(service.confirmReceipt(PAYMENT, "u1").getStatus()).isEqualTo("RELEASED");
    }

    @Test
    void confirmReceipt_someoneElsesPayment_isForbidden() {
        stored(payment("HELD", "u1"));

        assertThatThrownBy(() -> service.confirmReceipt(PAYMENT, "u2")).isInstanceOf(PaymentForbiddenException.class);
        verify(repository, never()).markConfirmedReceipt(anyString());
    }

    @Test
    void confirmReceipt_notPaid_isInvalidState() {
        stored(payment("RESERVED", "u1"));
        assertThatThrownBy(() -> service.confirmReceipt(PAYMENT, "u1")).isInstanceOf(InvalidPaymentStateException.class);
        stored(payment("REFUNDED", "u1"));
        assertThatThrownBy(() -> service.confirmReceipt(PAYMENT, "u1")).isInstanceOf(InvalidPaymentStateException.class);
    }

    // ---- release funds (PG3) -------------------------------------------------------------------

    private Payment row(String id, String status, boolean confirmed) {
        Payment p = payment(status, "u-" + id);
        p.setId(id.repeat(32).substring(0, 32));
        p.setConfirmedReceipt(confirmed);
        return p;
    }

    @Test
    void releaseFunds_paysEveryConfirmedHeldPaymentToTheHost_andSumsReleased() {
        Payment a = row("a", "HELD", true);
        Payment b = row("b", "HELD", true);
        Payment left = row("c", "FAILED", false);
        Payment aDone = row("a", "RELEASED", true);
        Payment bDone = row("b", "RELEASED", true);
        when(repository.findByDealId(DEAL)).thenReturn(List.of(a, b, left), List.of(aDone, bDone, left));
        when(gateway.payout(eq("host-1"), any(), anyString()))
                .thenAnswer(i -> new com.harnkan.payment.gateway.PayoutResult("payout-" + i.getArgument(2)));

        ReleaseOutcome outcome = service.releaseFunds(DEAL, "host-1");

        assertThat(outcome.releasedCount()).isEqualTo(2);
        assertThat(outcome.totalAmount()).isEqualByComparingTo("380.00");
        assertThat(outcome.totalAmount().scale()).isEqualTo(2);
        assertThat(outcome.payments()).hasSize(3);
        verify(repository).updateStatus(a.getId(), "PAYOUT_PENDING");
        verify(repository).markReleased(a.getId(), "payout-" + a.getId());
        verify(repository).markReleased(b.getId(), "payout-" + b.getId());
        verify(gateway, never()).payout(anyString(), any(), eq(left.getId()));
        verify(dealClient, never()).updateParticipantPayment(any(), anyString());
    }

    @Test
    void releaseFunds_someoneHasNotConfirmed_isInvalidState_andPaysNothing() {
        when(repository.findByDealId(DEAL)).thenReturn(List.of(row("a", "HELD", true), row("b", "HELD", false)));

        assertThatThrownBy(() -> service.releaseFunds(DEAL, "host-1")).isInstanceOf(InvalidPaymentStateException.class);

        verify(gateway, never()).payout(anyString(), any(), anyString());
        verify(repository, never()).updateStatus(anyString(), anyString());
    }

    @Test
    void releaseFunds_gatewayFailure_leavesPayoutPending_soACallLaterRetriesIt() {
        Payment a = row("a", "HELD", true);
        when(repository.findByDealId(DEAL)).thenReturn(List.of(a));
        when(gateway.payout(anyString(), any(), anyString())).thenThrow(new PaymentGatewayException());

        assertThatThrownBy(() -> service.releaseFunds(DEAL, "host-1")).isInstanceOf(PaymentGatewayException.class);

        verify(repository).updateStatus(a.getId(), "PAYOUT_PENDING");
        verify(repository, never()).markReleased(anyString(), anyString());
    }

    @Test
    void releaseFunds_retry_paysPayoutPending_andSkipsReleased() {
        Payment pending = row("a", "PAYOUT_PENDING", true);
        Payment done = row("b", "RELEASED", true);
        when(repository.findByDealId(DEAL)).thenReturn(List.of(pending, done),
                List.of(row("a", "RELEASED", true), done));
        when(gateway.payout(anyString(), any(), anyString())).thenReturn(new com.harnkan.payment.gateway.PayoutResult("p-1"));

        ReleaseOutcome outcome = service.releaseFunds(DEAL, "host-1");

        assertThat(outcome.releasedCount()).isEqualTo(2);
        verify(gateway, times(1)).payout(anyString(), any(), anyString());
        verify(gateway, never()).payout(anyString(), any(), eq(done.getId()));
    }

    @Test
    void releaseFunds_rejectsBadCreatorId() {
        assertThatThrownBy(() -> service.releaseFunds(DEAL, "x".repeat(65))).isInstanceOf(IllegalArgumentException.class);
    }
}
