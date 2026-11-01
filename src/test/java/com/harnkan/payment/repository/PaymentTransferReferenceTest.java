package com.harnkan.payment.repository;

import com.harnkan.payment.model.Payment;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class PaymentTransferReferenceTest {
    @Test void migrationAndReleasePreserveEveryOldReferenceAndRoundTripNewField() {
        var dataSource=new DriverManagerDataSource("jdbc:h2:mem:transfer_refs;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__create_payments.sql")).execute(dataSource);
        jdbc.update("INSERT INTO payments(id,deal_id,participant_id,amount,payment_gateway_ref,refund_gateway_ref,payout_gateway_ref,status) VALUES ('old','d1','u1',189,'pi_old','re_old','tr_legacy','RELEASED')");
        jdbc.update("INSERT INTO payments(id,deal_id,participant_id,amount,payment_gateway_ref,refund_gateway_ref,payout_gateway_ref,status) VALUES ('new','d2','u2',189,'pi_charge','re_keep','legacy_payout_ref','PAYOUT_PENDING')");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__add_payment_transfer_gateway_ref.sql")).execute(dataSource);
        var repository=new PaymentRepository(jdbc);
        var old=repository.findById("old").orElseThrow();
        assertThat(old.getPayoutGatewayRef()).isEqualTo("tr_legacy");
        assertThat(old.getPaymentTransferGatewayRef()).isEqualTo("tr_legacy");
        assertThat(repository.markReleased("new","tr_combined")).isEqualTo(1);
        var released=repository.findById("new").orElseThrow();
        assertThat(released.getPaymentGatewayRef()).isEqualTo("pi_charge");
        assertThat(released.getRefundGatewayRef()).isEqualTo("re_keep");
        assertThat(released.getPayoutGatewayRef()).isEqualTo("legacy_payout_ref");
        assertThat(released.getPaymentTransferGatewayRef()).isEqualTo("tr_combined");
        assertThat(released.getStatus()).isEqualTo("RELEASED");
        jdbc.update("UPDATE payments SET status='PAYOUT_PENDING' WHERE id='new'");
        assertThat(repository.markReleased("new","tr_different")).isZero();
        assertThat(repository.findById("new").orElseThrow().getPaymentTransferGatewayRef()).isEqualTo("tr_combined");
        Payment inserted=new Payment();inserted.setId("inserted");inserted.setDealId("d3");inserted.setParticipantId("u3");
        inserted.setAmount(new BigDecimal("189.00"));inserted.setStatus("RELEASED");inserted.setConfirmedReceipt(true);
        inserted.setPaymentGatewayRef("pi_inserted");inserted.setPayoutGatewayRef("payout_keep");inserted.setPaymentTransferGatewayRef("tr_inserted");
        inserted.setCreatedAt(java.time.LocalDateTime.now());inserted.setUpdatedAt(java.time.LocalDateTime.now());
        repository.insert(inserted);
        assertThat(repository.findById("inserted").orElseThrow().getPaymentTransferGatewayRef()).isEqualTo("tr_inserted");
        assertThat(repository.findById("inserted").orElseThrow().getPayoutGatewayRef()).isEqualTo("payout_keep");
    }
}
