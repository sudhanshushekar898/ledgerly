package dev.ledgerly.wallet.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL because the core of the design is one PostgreSQL feature: {@code INSERT ... ON CONFLICT DO NOTHING}.
 * When two requests race with the same key, the second INSERT waits for the first transaction to finish, then
 * either sees its committed record (replay) or, if it rolled back, claims the key itself.
 */
@Repository
public class IdempotencyStore {

    public record StoredRecord(String requestHash, String responseBody) {
    }

    private final JdbcTemplate jdbc;

    IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns true if this request now owns the key. Expired records for the key are removed first. */
    public boolean tryClaim(UUID userId, String key, String requestHash, Instant now, Instant expiresAt) {
        jdbc.update("DELETE FROM idempotency_records WHERE user_id = ? AND idempotency_key = ? AND expires_at <= ?",
                userId, key, Timestamp.from(now));
        return jdbc.update("""
                INSERT INTO idempotency_records (user_id, idempotency_key, request_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id, idempotency_key) DO NOTHING""",
                userId, key, requestHash, Timestamp.from(now), Timestamp.from(expiresAt)) == 1;
    }

    public Optional<StoredRecord> find(UUID userId, String key) {
        return jdbc.query("SELECT request_hash, response_body::text AS body FROM idempotency_records "
                        + "WHERE user_id = ? AND idempotency_key = ?",
                (rs, i) -> new StoredRecord(rs.getString("request_hash"), rs.getString("body")),
                userId, key).stream().findFirst();
    }

    public void complete(UUID userId, String key, String responseJson) {
        jdbc.update("UPDATE idempotency_records SET response_body = ?::jsonb WHERE user_id = ? AND idempotency_key = ?",
                responseJson, userId, key);
    }
}
