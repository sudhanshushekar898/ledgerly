package dev.ledgerly.notification.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationStore {

    public record Row(UUID id, UUID userId, String kind, BigDecimal amount, String counterpartyName, String memo,
                      UUID transactionId, Instant createdAt, boolean read) {
    }

    private final JdbcTemplate jdbc;

    NotificationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The inbox: true the first time this consumer sees the event id, false for every redelivery. */
    public boolean firstDelivery(String consumer, UUID eventId, Instant at) {
        return jdbc.update("INSERT INTO processed_events (consumer, event_id, processed_at) VALUES (?, ?, ?) "
                + "ON CONFLICT DO NOTHING", consumer, eventId, Timestamp.from(at)) == 1;
    }

    public void insert(Row row) {
        jdbc.update("""
                INSERT INTO notifications (id, user_id, kind, amount, counterparty_name, memo, transaction_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                row.id(), row.userId(), row.kind(), row.amount(), row.counterpartyName(), row.memo(), row.transactionId(),
                Timestamp.from(row.createdAt()));
    }

    public List<Row> latest(UUID userId, int limit) {
        return jdbc.query("""
                SELECT id, user_id, kind, amount, counterparty_name, memo, transaction_id, created_at, read_at
                FROM notifications WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?""",
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("kind"),
                        rs.getBigDecimal("amount"), rs.getString("counterparty_name"), rs.getString("memo"),
                        rs.getObject("transaction_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("read_at") != null),
                userId, limit);
    }

    public long unreadCount(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL", Long.class, userId);
    }

    public int markAllRead(UUID userId, Instant at) {
        return jdbc.update("UPDATE notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL", Timestamp.from(at), userId);
    }
}
