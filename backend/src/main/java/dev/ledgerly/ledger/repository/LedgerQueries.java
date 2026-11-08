package dev.ledgerly.ledger.repository;

import dev.ledgerly.ledger.AccountType;
import dev.ledgerly.ledger.DailyFlow;
import dev.ledgerly.ledger.Direction;
import dev.ledgerly.ledger.StatementLine;
import dev.ledgerly.ledger.TransactionDetail;
import dev.ledgerly.ledger.TransactionKind;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-side SQL kept as plain SQL so it is easy to read, explain and tune. */
@Repository
public class LedgerQueries {

    private static final String SIGNED = "CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END";

    private final JdbcTemplate jdbc;

    LedgerQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Keyset pagination on the per-account sequence: stable under concurrent inserts, O(limit) at any depth. */
    public List<StatementLine> statement(UUID accountId, long beforeSeq, int limit) {
        return jdbc.query("""
                SELECT e.account_seq, e.transaction_id, t.kind, e.direction, e.amount, e.balance_after, t.memo,
                       e.created_at,
                       (SELECT a.owner_user_id FROM journal_entries o JOIN accounts a ON a.id = o.account_id
                        WHERE o.transaction_id = e.transaction_id AND o.account_id <> e.account_id
                        LIMIT 1) AS counterparty_user_id
                FROM journal_entries e JOIN journal_transactions t ON t.id = e.transaction_id
                WHERE e.account_id = ? AND e.account_seq < ?
                ORDER BY e.account_seq DESC
                LIMIT ?""",
                (rs, i) -> new StatementLine(
                        rs.getLong("account_seq"),
                        rs.getObject("transaction_id", UUID.class),
                        TransactionKind.valueOf(rs.getString("kind")),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("balance_after"),
                        rs.getString("memo"),
                        rs.getObject("counterparty_user_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                accountId, beforeSeq, limit);
    }

    /** Uses ix_journal_entries_account_created. Sees this transaction's own flushed rows too (same connection). */
    public BigDecimal debitsSince(UUID accountId, TransactionKind kind, Instant since) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(e.amount), 0) FROM journal_entries e
                JOIN journal_transactions t ON t.id = e.transaction_id
                WHERE e.account_id = ? AND e.direction = 'DEBIT' AND t.kind = ? AND e.created_at >= ?""",
                BigDecimal.class, accountId, kind.name(), Timestamp.from(since));
    }

    /** Transactions whose legs do not sum to zero. The deferred trigger should make this always empty. */
    public List<UUID> unbalancedTransactions(int limit) {
        return jdbc.queryForList("SELECT e.transaction_id FROM journal_entries e GROUP BY e.transaction_id HAVING sum("
                + SIGNED + ") <> 0 LIMIT ?", UUID.class, limit);
    }

    /** Accounts whose cached balance or entry counter disagrees with their journal entries. */
    public List<UUID> mismatchedAccounts(int limit) {
        return jdbc.queryForList("""
                SELECT a.id FROM accounts a
                LEFT JOIN (SELECT e.account_id, sum(%s) AS net, count(*) AS n, max(e.account_seq) AS max_seq
                           FROM journal_entries e GROUP BY e.account_id) j ON j.account_id = a.id
                WHERE a.balance <> coalesce(j.net, 0)
                   OR a.last_entry_seq <> coalesce(j.n, 0)
                   OR coalesce(j.max_seq, 0) <> coalesce(j.n, 0)
                ORDER BY a.id LIMIT ?""".formatted(SIGNED), UUID.class, limit);
    }

    public BigDecimal totalOfAllBalances() {
        return jdbc.queryForObject("SELECT coalesce(sum(balance), 0) FROM accounts", BigDecimal.class);
    }

    public long accountCount() {
        return jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class);
    }

    public Optional<TransactionDetail> transaction(UUID id) {
        List<TransactionDetail.Line> lines = jdbc.query("""
                SELECT e.account_id, a.type, a.owner_user_id, e.direction, e.amount, e.balance_after, e.account_seq
                FROM journal_entries e JOIN accounts a ON a.id = e.account_id
                WHERE e.transaction_id = ?
                ORDER BY CASE e.direction WHEN 'DEBIT' THEN 0 ELSE 1 END, e.account_id""",
                (rs, i) -> new TransactionDetail.Line(
                        rs.getObject("account_id", UUID.class),
                        AccountType.valueOf(rs.getString("type")),
                        rs.getObject("owner_user_id", UUID.class),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("balance_after"),
                        rs.getLong("account_seq")),
                id);
        return jdbc.query("""
                SELECT t.id, t.kind, t.memo, t.created_at, t.reverses_transaction_id,
                       (SELECT r.id FROM journal_transactions r WHERE r.reverses_transaction_id = t.id) AS reversed_by
                FROM journal_transactions t WHERE t.id = ?""",
                (rs, i) -> new TransactionDetail(
                        rs.getObject("id", UUID.class),
                        TransactionKind.valueOf(rs.getString("kind")),
                        rs.getString("memo"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("reverses_transaction_id", UUID.class),
                        rs.getObject("reversed_by", UUID.class),
                        lines),
                id).stream().findFirst();
    }

    /**
     * Calendar days are cut in the customer's zone, not UTC: a payment at 01:30 IST is "today" in India although it
     * is still yesterday in UTC. GROUP BY 1 avoids repeating the parameterised expression.
     */
    public List<DailyFlow> dailyFlows(UUID accountId, ZoneId zone, Instant from) {
        return jdbc.query("""
                SELECT d.day, d.money_in, d.money_out, c.balance_after AS closing_balance
                FROM (SELECT (e.created_at AT TIME ZONE ?)::date AS day,
                             sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE 0 END) AS money_in,
                             sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE 0 END) AS money_out,
                             max(e.account_seq) AS last_seq
                      FROM journal_entries e
                      WHERE e.account_id = ? AND e.created_at >= ?
                      GROUP BY 1) d
                JOIN journal_entries c ON c.account_id = ? AND c.account_seq = d.last_seq
                ORDER BY d.day""",
                (rs, i) -> new DailyFlow(
                        rs.getObject("day", LocalDate.class),
                        rs.getBigDecimal("money_in"),
                        rs.getBigDecimal("money_out"),
                        rs.getBigDecimal("closing_balance")),
                zone.getId(), accountId, Timestamp.from(from), accountId);
    }

    public BigDecimal balanceBefore(UUID accountId, Instant instant) {
        return jdbc.query("""
                SELECT e.balance_after FROM journal_entries e
                WHERE e.account_id = ? AND e.created_at < ?
                ORDER BY e.account_seq DESC LIMIT 1""",
                (rs, i) -> rs.getBigDecimal(1), accountId, Timestamp.from(instant))
                .stream().findFirst().orElse(BigDecimal.ZERO);
    }

    public List<UUID> recentCounterparties(UUID accountId, TransactionKind kind, int limit) {
        return jdbc.queryForList("""
                SELECT a.owner_user_id
                FROM journal_entries e
                JOIN journal_transactions t ON t.id = e.transaction_id
                JOIN journal_entries o ON o.transaction_id = e.transaction_id AND o.account_id <> e.account_id
                JOIN accounts a ON a.id = o.account_id
                WHERE e.account_id = ? AND t.kind = ? AND a.owner_user_id IS NOT NULL
                GROUP BY a.owner_user_id
                ORDER BY max(e.created_at) DESC
                LIMIT ?""", UUID.class, accountId, kind.name(), limit);
    }
}
