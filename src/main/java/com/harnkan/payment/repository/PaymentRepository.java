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
     * Reuse the SAME connection for SQL while locked. Gateway progress is autocommitted:
     * REFUND_PENDING survives a gateway failure and no DB transaction spans the network.
     */
    public <T> T withDealLock(String dealId, Supplier<T> action) {
        return withLock("harnkan-payment:", dealId, action);
    }

    /** Independent lock namespace: callbacks never block Deal-initiated payment mutations. */
    public <T> T withDeliveryLock(String paymentId, Supplier<T> action) {
        return withLock("harnkan-delivery:", paymentId, action);
    }

    private <T> T withLock(String prefix, String dealId, Supplier<T> action) {
        if (lockedTemplate.get() != null) {
            throw new IllegalStateException("Nested payment locks are not supported");
        }
        String lockName = prefix + UUID.nameUUIDFromBytes(dealId.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
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

    public List<String> paymentsToReconcile() {
        return jdbc().query("""
                SELECT id FROM payments WHERE payment_gateway_ref IS NOT NULL AND
                ((status = 'PAYMENT_PENDING' AND updated_at < UTC_TIMESTAMP() - INTERVAL 10 MINUTE)
                OR status = 'REFUND_PENDING') ORDER BY updated_at LIMIT 100
                """, (rs, row) -> rs.getString(1));
    }

    public boolean existsActivePayment(String dealId, String participantId) {
        Integer count = jdbc().queryForObject("""
                SELECT COUNT(*) FROM payments WHERE deal_id = ? AND participant_id = ?
                AND status IN ('RESERVED', 'PAYMENT_PENDING', 'HELD', 'REFUND_PENDING', 'PAYOUT_PENDING', 'RELEASED')
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

    /** Atomic database-only work on the connection holding the deal advisory lock. */
    public <T> T atomic(Supplier<T> action) {
        if (lockedTemplate.get() == null) throw new IllegalStateException("Deal lock required");
        return jdbc().execute((ConnectionCallback<T>) connection -> {
            connection.setAutoCommit(false);
            try {
                T result = action.get();
                connection.commit();
                return result;
            } catch (java.sql.SQLException | RuntimeException | Error failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    public void enqueueDealUpdate(String paymentId, String status) {
        jdbc().update("DELETE FROM payment_deal_updates WHERE payment_id = ?", paymentId);
        jdbc().update("INSERT INTO payment_deal_updates (payment_id, status) VALUES (?, ?)", paymentId, status);
    }

    public record DealUpdate(String paymentId, String status) {}

    public List<DealUpdate> pendingDealUpdates() {
        return jdbc().query("SELECT payment_id, status FROM payment_deal_updates LIMIT 100",
                (rs, row) -> new DealUpdate(rs.getString(1), rs.getString(2)));
    }

    public Optional<DealUpdate> pendingDealUpdate(String paymentId) {
        return jdbc().query("SELECT payment_id, status FROM payment_deal_updates WHERE payment_id = ?",
                (rs, row) -> new DealUpdate(rs.getString(1), rs.getString(2)), paymentId).stream().findFirst();
    }

    public void clearDealUpdate(String paymentId) {
        jdbc().update("DELETE FROM payment_deal_updates WHERE payment_id = ?", paymentId);
    }

    public void acknowledgeDealUpdate(DealUpdate update) {
        jdbc().update("DELETE FROM payment_deal_updates WHERE payment_id = ? AND status = ?",
                update.paymentId(), update.status());
    }

    public void confirmReceipt(String paymentId) {
        jdbc().update("UPDATE payments SET is_confirmed_receipt = TRUE, updated_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC), paymentId);
    }

    public void markReleased(String paymentId, String reference) {
        jdbc().update("""
                UPDATE payments SET status = 'RELEASED', payout_gateway_ref = ?, updated_at = ?
                WHERE id = ? AND status = 'PAYOUT_PENDING'
                """, reference, LocalDateTime.now(ZoneOffset.UTC), paymentId);
    }

    public Optional<String> payoutCreator(String dealId) {
        return jdbc().query("SELECT creator_id FROM payment_payouts WHERE deal_id = ?",
                (rs, row) -> rs.getString(1), dealId).stream().findFirst();
    }

    public void bindPayoutCreator(String dealId, String creatorId) {
        jdbc().update("INSERT INTO payment_payouts (deal_id, creator_id) VALUES (?, ?)", dealId, creatorId);
    }

    public record PendingEvent(String eventId, String routingKey, String payload) {}

    public void enqueueEvent(String eventId, String routingKey, String payload) {
        Integer count = jdbc().queryForObject("SELECT COUNT(*) FROM payment_events WHERE event_id = ?",
                Integer.class, eventId);
        if (count != null && count == 0) {
            jdbc().update("INSERT INTO payment_events (event_id, routing_key, payload) VALUES (?, ?, ?)",
                    eventId, routingKey, payload);
        }
    }

    public List<PendingEvent> pendingEvents() {
        return jdbc().query("SELECT event_id, routing_key, payload FROM payment_events WHERE delivered = FALSE LIMIT 100",
                (rs, row) -> new PendingEvent(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    public void acknowledgeEvent(String eventId) {
        jdbc().update("UPDATE payment_events SET delivered = TRUE WHERE event_id = ?", eventId);
    }

}
