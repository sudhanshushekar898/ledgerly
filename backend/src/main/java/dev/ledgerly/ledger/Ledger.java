package dev.ledgerly.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public facade of the ledger module: the only way any other module reads or moves money.
 * Mutating methods join the caller's transaction, so a caller can commit a posting atomically with its own writes
 * (an idempotency record) and run checks under the locks the posting took (the daily limit).
 */
public interface Ledger {

    /** Idempotent: returns the existing wallet if the user already has one. */
    AccountView openUserWallet(UUID userId);

    Optional<AccountView> findUserWallet(UUID userId);

    Optional<AccountView> findAccount(UUID accountId);

    /** The first shard of a system account. */
    AccountView systemAccount(AccountType type);

    /**
     * One shard of a sharded system account (ADR-014). The same key always maps to the same shard; different keys
     * spread over all shards, so parallel postings stop queueing on one row lock.
     */
    AccountView systemAccount(AccountType type, int shardKey);

    /**
     * Posts one balanced transaction. Locks every involved account in a fixed order (so concurrent postings cannot
     * deadlock), rejects frozen wallets (except for reversals), applies all legs, fails with INSUFFICIENT_FUNDS if a
     * user wallet would go below zero, and flushes, so the posting is visible to later SQL in the same transaction.
     */
    PostedTransaction post(TransactionKind kind, UUID initiatedBy, String memo, List<Leg> legs);

    /**
     * Same as above without a memo. Deliberately not a {@code default} method: a default method has no
     * {@code @Transactional} and its call to the other overload bypasses the Spring proxy (self-invocation), so it
     * would run with no transaction at all when the caller has none.
     */
    PostedTransaction post(TransactionKind kind, UUID initiatedBy, List<Leg> legs);

    /** Posts the exact opposite of a transaction. Each transaction can be reversed at most once. */
    PostedTransaction reverse(UUID transactionId, UUID initiatedBy, String reason);

    /** Freezes or unfreezes a user wallet and records who did it and why. No-op if already in that state. */
    AccountView changeStatus(UUID accountId, AccountStatus status, UUID changedBy, String reason);

    /** Sum of an account's debits of one kind since an instant (for example, transfers sent since midnight). */
    BigDecimal debitsSince(UUID accountId, TransactionKind kind, Instant since);

    StatementPage statement(UUID accountId, Long beforeSeq, int limit);

    ReconciliationReport reconcile();

    Optional<TransactionDetail> transaction(UUID transactionId);

    /** Days (in {@code zone}) with activity on the account since {@code from}, oldest first. Quiet days are absent. */
    List<DailyFlow> dailyFlows(UUID accountId, ZoneId zone, Instant from);

    /** The account's balance just before {@code instant}; zero if it had no entries yet. */
    BigDecimal balanceBefore(UUID accountId, Instant instant);

    /** Owners of the other side of this account's recent transactions of one kind: distinct, most recent first. */
    List<UUID> recentCounterparties(UUID accountId, TransactionKind kind, int limit);
}
