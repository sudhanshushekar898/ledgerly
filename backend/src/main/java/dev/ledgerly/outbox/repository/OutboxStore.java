package dev.ledgerly.outbox.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxStore {

    public record Pending(UUID id, String topic, String key, String payload) {
    }

    private final JdbcTemplate jdbc;

    OutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String topic, String key, String type, String payloadJson, Instant at) {
        jdbc.update("""
                INSERT INTO outbox_events (id, topic, event_key, event_type, payload, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)""", id, topic, key, type, payloadJson, Timestamp.from(at));
    }

    /**
     * Oldest unpublished events, row-locked. SKIP LOCKED lets several relay instances run side by side: each takes
     * a different batch instead of waiting for, or double-sending, the same rows.
     */
    public List<Pending> lockBatch(int limit) {
        return jdbc.query("""
                SELECT id, topic, event_key, payload::text AS payload FROM outbox_events
                WHERE published_at IS NULL
                ORDER BY created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED""",
                (rs, i) -> new Pending(rs.getObject("id", UUID.class), rs.getString("topic"), rs.getString("event_key"),
                        rs.getString("payload")),
                limit);
    }

    /** One JDBC batch for the whole set of confirmed events. */
    public void markPublished(List<UUID> ids, Instant at) {
        if (ids.isEmpty()) {
            return;
        }
        Timestamp when = Timestamp.from(at);
        jdbc.batchUpdate("UPDATE outbox_events SET published_at = ?, attempts = attempts + 1, last_error = NULL WHERE id = ?",
                ids.stream().map(id -> new Object[] {when, id}).toList());
    }

    /** Uses the partial index on unpublished rows: cheap enough to run on every Prometheus scrape. */
    public long pendingCount() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
    }

    /** Age of the oldest unpublished event in seconds; 0 when nothing is waiting. Growing = the relay is stuck. */
    public double oldestPendingAgeSeconds() {
        Double age = jdbc.queryForObject("""
                SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0)
                FROM outbox_events WHERE published_at IS NULL""", Double.class);
        return age == null ? 0 : age;
    }

    public void markFailed(UUID id, String error) {
        jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE id = ?", error, id);
    }
}
