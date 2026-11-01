package com.harnkan.payment.controller;

import com.harnkan.payment.exception.GlobalExceptionHandler;
import com.harnkan.payment.gateway.StripeTransferService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StripeTestTransferControllerTest {
    @Test void dealEndpointUsesServerSideTotalsAndPersistsViaReleaseFlow() throws Exception {
        var payouts = mock(com.harnkan.payment.service.DealPayoutService.class);
        when(payouts.releaseFunds("deal1", "creator1")).thenReturn(new com.harnkan.payment.service.ReleaseOutcome(
                10, new BigDecimal("1890.00"), java.util.List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new StripeTestTransferController(payouts)).build();
        mvc.perform(post("/internal/dev/stripe/transfers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dealId\":\"deal1\",\"creatorId\":\"creator1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.releasedCount").value(10))
                .andExpect(jsonPath("$.totalAmount").value(1890.00));
        verify(payouts).releaseFunds("deal1", "creator1");
    }

}
