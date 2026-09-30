package com.harnkan.payment.repository;

import com.harnkan.payment.model.Payment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

public class PaymentRowMapper implements RowMapper<Payment> {
    @Override
    public Payment mapRow(ResultSet rs, int rowNum) throws SQLException {
        Payment payment = new Payment();
        payment.setId(rs.getString("id"));
        payment.setDealId(rs.getString("deal_id"));
        payment.setParticipantId(rs.getString("participant_id"));
        payment.setAmount(rs.getBigDecimal("amount"));
        payment.setPaymentGatewayRef(rs.getString("payment_gateway_ref"));
        payment.setRefundGatewayRef(rs.getString("refund_gateway_ref"));
        payment.setPayoutGatewayRef(rs.getString("payout_gateway_ref"));
        payment.setStatus(rs.getString("status"));
        payment.setReservedUntil(dateTime(rs, "reserved_until"));
        payment.setConfirmedReceipt(rs.getBoolean("is_confirmed_receipt"));
        payment.setCreatedAt(dateTime(rs, "created_at"));
        payment.setUpdatedAt(dateTime(rs, "updated_at"));
        return payment;
    }

    private LocalDateTime dateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}
