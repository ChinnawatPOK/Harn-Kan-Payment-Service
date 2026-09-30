package com.harnkan.payment.repository;

import com.harnkan.payment.exception.PaymentBusyException;
import com.harnkan.payment.model.Payment;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {
    private static final String COLUMNS = """
            id, deal_id, participant_id, amount, payment_gateway_ref, refund_gateway_ref,
            payout_gateway_ref, status, reserved_until, is_confirmed_receipt, created_at, updated_at
            """;
    private final JdbcTemplate template;
    private final PaymentRowMapper mapper = new PaymentRowMapper();
    private final ThreadLocal<JdbcTemplate> lockedTemplate = new ThreadLocal<>();

    public PaymentRepository(JdbcTemplate template) {
        this.template = template;
    }

    /**
     * MySQL session lock serializes mutations for a deal across app instances, including
     * the initial insert (there is deliberately no unique pair index in this phase).
     * Reuse the SAME connection for SQL while locked. Statements remain autocommitted:
     * REFUND_PENDING survives a gateway failure and no DB transaction spans the network.
     */
    public <T> T withDealLock(String dealId, Supplier<T> action) {
        if (lockedTemplate.get() != null) {
            throw new IllegalStateException("Nested payment locks are not supported");
        }
        String lockName = "harnkan-payment:" + UUID.nameUUIDFromBytes(dealId.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return template.execute((ConnectionCallback<T>) connection -> {
            JdbcTemplate session = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            Integer acquired = session.queryForObject("SELECT GET_LOCK(?, 10)", Integer.class, lockName);
            if (!Integer.valueOf(1).equals(acquired)) {
                throw new PaymentBusyException();
            }
            lockedTemplate.set(session);
            try {
                return action.get();
            } finally {
                lockedTemplate.remove();
                // Never return a connection with an advisory lock to the pool.
                try {
                    Integer released = session.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockName);
                    if (!Integer.valueOf(1).equals(released)) {
                        connection.abort(Runnable::run);
                    }
                } catch (RuntimeException failure) {
                    connection.abort(Runnable::run);
                    throw failure;
                }
            }
        });
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate locked = lockedTemplate.get();
        return locked == null ? template : locked;
    }

    public void insert(Payment payment) {
        jdbc().update("INSERT INTO payments (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                payment.getId(), payment.getDealId(), payment.getParticipantId(), payment.getAmount(),
                payment.getPaymentGatewayRef(), payment.getRefundGatewayRef(), payment.getPayoutGatewayRef(),
                payment.getStatus(), payment.getReservedUntil(), payment.getConfirmedReceipt(),
                payment.getCreatedAt(), payment.getUpdatedAt());
    }

    public Optional<Payment> findById(String paymentId) {
        return jdbc().query("SELECT " + COLUMNS + " FROM payments WHERE id = ?", mapper, paymentId).stream().findFirst();
    }

    public List<Payment> findByDealId(String dealId) {
        return jdbc().query("SELECT " + COLUMNS + " FROM payments WHERE deal_id = ? ORDER BY id", mapper, dealId);
    }

    public Optional<Payment> findByGatewayReference(String reference) {
        return jdbc().query("SELECT " + COLUMNS + " FROM payments WHERE payment_gateway_ref = ?", mapper, reference)
                .stream().findFirst();
    }

    public boolean existsActivePayment(String dealId, String participantId) {
        Integer count = jdbc().queryForObject("""
                SELECT COUNT(*) FROM payments WHERE deal_id = ? AND participant_id = ?
                AND status IN ('RESERVED', 'PAYMENT_PENDING', 'HELD', 'REFUND_PENDING')
                """, Integer.class, dealId, participantId);
        return count != null && count > 0;
    }

    public int updateStatus(String paymentId, String newStatus) {
        return jdbc().update("UPDATE payments SET status = ?, updated_at = ? WHERE id = ?", newStatus, LocalDateTime.now(ZoneOffset.UTC), paymentId);
    }

    public int updatePaymentStarted(String paymentId, String gatewayRef, String newStatus) {
        return jdbc().update("""
                UPDATE payments SET payment_gateway_ref = ?, status = ?, updated_at = ?
                WHERE id = ? AND status = 'RESERVED'
                """, gatewayRef, newStatus, LocalDateTime.now(ZoneOffset.UTC), paymentId);
    }

    public int markRefunded(String paymentId, String refundGatewayRef) {
        return jdbc().update("""
                UPDATE payments SET refund_gateway_ref = ?, status = 'REFUNDED', updated_at = ?
                WHERE id = ? AND status = 'REFUND_PENDING'
                """, refundGatewayRef, LocalDateTime.now(ZoneOffset.UTC), paymentId);
    }
}
