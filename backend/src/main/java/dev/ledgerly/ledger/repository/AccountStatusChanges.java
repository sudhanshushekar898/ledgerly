package dev.ledgerly.ledger.repository;

import dev.ledgerly.ledger.AccountStatus;
import dev.ledgerly.shared.persistence.Ids;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only audit of wallet freezes and unfreezes. */
@Repository
public class AccountStatusChanges {

    private final JdbcTemplate jdbc;

    AccountStatusChanges(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID accountId, AccountStatus status, String reason, UUID changedBy, Instant at) {
        jdbc.update("INSERT INTO account_status_changes (id, account_id, status, reason, changed_by, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", Ids.newId(), accountId, status.name(), reason, changedBy, Timestamp.from(at));
    }
}
