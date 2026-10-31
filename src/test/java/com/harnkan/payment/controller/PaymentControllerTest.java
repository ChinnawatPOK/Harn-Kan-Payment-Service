package com.harnkan.payment.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harnkan.payment.auth.CurrentUserArgumentResolver;
import com.harnkan.payment.exception.GlobalExceptionHandler;
import com.harnkan.payment.exception.InvalidPaymentStateException;
import com.harnkan.payment.exception.PaymentExpiredException;
import com.harnkan.payment.exception.PaymentForbiddenException;
import com.harnkan.payment.gateway.StartPaymentResult;
import com.harnkan.payment.model.Payment;
import com.harnkan.payment.service.PaymentService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {
    @Mock private PaymentService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PaymentController(service), new DevPaymentController(service))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Payment payment(String status) {
        Payment p = new Payment();
        p.setId("p1");
        p.setDealId("d1");
        p.setParticipantId("u1");
        p.setAmount(new BigDecimal("190.00"));
        p.setStatus(status);
        p.setReservedUntil(LocalDateTime.of(2026, 10, 28, 9, 5, 0));
        p.setConfirmedReceipt(false);
        return p;
    }

    @Test
    void pay_usesTheGatewayUserId() throws Exception {
        when(service.startPayment("p1", "u1")).thenReturn(new StartPaymentResult("ref", "secret"));

        mvc.perform(post("/api/payments/p1/pay").header("X-User-Id", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value("p1"))
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.clientSecret").value("secret"));
    }

    @Test
    void pay_withoutUserHeader_is401() throws Exception {
        mvc.perform(post("/api/payments/p1/pay"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void pay_notTheOwner_is403() throws Exception {
        when(service.startPayment("p1", "u2")).thenThrow(new PaymentForbiddenException());

        mvc.perform(post("/api/payments/p1/pay").header("X-User-Id", "u2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void pay_expired_is409() throws Exception {
        when(service.startPayment("p1", "u1")).thenThrow(new PaymentExpiredException());

        mvc.perform(post("/api/payments/p1/pay").header("X-User-Id", "u1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_EXPIRED"));
    }

    @Test
    void get_returnsReservedUntilWithZ() throws Exception {
        when(service.getOwnPayment("p1", "u1")).thenReturn(payment("RESERVED"));

        mvc.perform(get("/api/payments/p1").header("X-User-Id", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservedUntil").value("2026-10-28T09:05:00Z"))
                .andExpect(jsonPath("$.amount").value("190.00"));
    }

    @Test
    void simulateSuccess_needsNoUser_andReturnsHeld() throws Exception {
        when(service.simulateSuccess("p1")).thenReturn(payment("HELD"));

        mvc.perform(post("/internal/dev/payments/p1/simulate-success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value("p1"))
                .andExpect(jsonPath("$.status").value("HELD"));
    }

    @Test
    void simulateSuccess_wrongState_is409() throws Exception {
        when(service.simulateSuccess("p1")).thenThrow(new InvalidPaymentStateException());

        mvc.perform(post("/internal/dev/payments/p1/simulate-success"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATE"));
    }
}
