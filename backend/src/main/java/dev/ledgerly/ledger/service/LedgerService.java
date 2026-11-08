package dev.ledgerly.ledger.service;

import dev.ledgerly.ledger.AccountStatus;
import dev.ledgerly.ledger.AccountType;
import dev.ledgerly.ledger.AccountView;
import dev.ledgerly.ledger.DailyFlow;
import dev.ledgerly.ledger.Direction;
import dev.ledgerly.ledger.TransactionDetail;
import dev.ledgerly.ledger.Leg;
import dev.ledgerly.ledger.Ledger;
import dev.ledgerly.ledger.MoneyMoved;
import dev.ledgerly.outbox.Outbox;
import dev.ledgerly.outbox.OutboxEvent;
import dev.ledgerly.shared.persistence.Ids;
import dev.ledgerly.ledger.PostedEntry;
import dev.ledgerly.ledger.PostedTransaction;
import dev.ledgerly.ledger.ReconciliationReport;
import dev.ledgerly.ledger.StatementLine;
import dev.ledgerly.ledger.StatementPage;
import dev.ledgerly.ledger.TransactionKind;
import dev.ledgerly.ledger.domain.Account;
import dev.ledgerly.ledger.domain.JournalEntry;
import dev.ledgerly.ledger.domain.JournalTransaction;
import dev.ledgerly.ledger.repository.AccountRepository;
import dev.ledgerly.ledger.repository.AccountStatusChanges;
import dev.ledgerly.ledger.repository.JournalEntryRepository;
import dev.ledgerly.ledger.repository.JournalTransactionRepository;
import dev.ledgerly.ledger.repository.LedgerQueries;
import dev.ledgerly.shared.error.DomainException;
import dev.ledgerly.shared.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class LedgerService implements Ledger {

    private static final int REPORT_LIMIT = 100;

    private final AccountRepository accounts;
    private final JournalTransactionRepository transactions;
    private final JournalEntryRepository entries;
    private final AccountStatusChanges statusChanges;
    private final LedgerQueries queries;
    private final Clock clock;
    private final Outbox outbox;
    private final MeterRegistry metrics;

    LedgerService(AccountRepository accounts, JournalTransactionRepository transactions, JournalEntryRepository entries,
                  AccountStatusChanges statusChanges, LedgerQueries queries, Clock clock, Outbox outbox,
                  MeterRegistry metrics) {
        this.metrics = metrics;
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
        this.statusChanges = statusChanges;
        this.queries = queries;
        this.clock = clock;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public AccountView openUserWallet(UUID userId) {
        return accounts.findUserWalletView(userId)
                .orElseGet(() -> view(accounts.save(Account.userWallet(userId))));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> findUserWallet(UUID userId) {
        return accounts.findUserWalletView(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> findAccount(UUID accountId) {
        return accounts.findView(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountView systemAccount(AccountType type) {
        return systemAccount(type, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountView systemAccount(AccountType type, int shardKey) {
        List<AccountView> shards = accounts.findSystemAccountViews(type);
        if (shards.isEmpty()) {
            throw new IllegalStateException("System account " + type + " is missing; check migrations");
        }
        return shards.get(Math.floorMod(shardKey, shards.size()));
    }

    @Override
    @Transactional
    public PostedTransaction post(TransactionKind kind, UUID initiatedBy, String memo, List<Leg> legs) {
        if (kind == TransactionKind.REVERSAL) {
            throw new IllegalArgumentException("Reversals are posted through reverse(), which links the original");
        }
        return doPost(kind, initiatedBy, memo, null, legs);
    }

    @Override
    @Transactional
    public PostedTransaction post(TransactionKind kind, UUID initiatedBy, List<Leg> legs) {
        return post(kind, initiatedBy, null, legs); // self-call is fine here: this method already has the transaction
    }

    @Override
    @Transactional
    public PostedTransaction reverse(UUID transactionId, UUID initiatedBy, String reason) {
        JournalTransaction original = transactions.findById(transactionId)
                .orElseThrow(() -> new DomainException(ErrorCode.TRANSACTION_NOT_FOUND, "No such transaction."));
        if (original.getKind() == TransactionKind.REVERSAL) {
            throw new DomainException(ErrorCode.NOT_REVERSIBLE, "A reversal cannot itself be reversed.");
        }
        if (transactions.existsByReversesTransactionId(transactionId)) {
            throw alreadyReversed();
        }
        List<Leg> opposite = entries.findByTransactionId(transactionId).stream()
                .map(e -> new Leg(e.getAccountId(),
                        e.getDirection() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT, e.getAmount()))
                .toList();
        return doPost(TransactionKind.REVERSAL, initiatedBy, reason, transactionId, opposite);
    }

    private PostedTransaction doPost(TransactionKind kind, UUID initiatedBy, String memo, UUID reverses, List<Leg> legs) {
        Postings.validate(legs);
        Set<UUID> ids = legs.stream().map(Leg::accountId).collect(Collectors.toSet());
        // Fresh rows, because read paths never leave Account entities in the persistence context (see AccountRepository).
        Map<UUID, Account> locked = accounts.lockAllInIdOrder(ids).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        if (locked.size() != ids.size()) {
            throw new IllegalArgumentException("Posting references an unknown account");
        }
        if (locked.values().stream().map(Account::getCurrency).distinct().count() > 1) {
            throw new IllegalArgumentException("All legs of a posting must use the same currency");
        }
        // A parallel reversal of the same transaction waited on these locks; now that it has committed we can see it.
        // Checking here (not only before locking) gives the loser ALREADY_REVERSED instead of a confusing
        // INSUFFICIENT_FUNDS. The unique constraint stays as the final guard.
        if (reverses != null && transactions.existsByReversesTransactionId(reverses)) {
            throw alreadyReversed();
        }
        // Checked under the lock, so a freeze that commits first is always seen. Reversals bypass it on purpose.
        if (kind != TransactionKind.REVERSAL && locked.values().stream().anyMatch(Account::isFrozen)) {
            throw new DomainException(ErrorCode.WALLET_FROZEN, "One of the wallets in this payment is frozen.");
        }

        Instant now = clock.instant();
        JournalTransaction transaction = transactions.save(new JournalTransaction(kind, initiatedBy, memo, reverses, now));
        List<PostedEntry> posted = new ArrayList<>(legs.size());
        for (Leg leg : legs) {
            Account account = locked.get(leg.accountId());
            long seq = account.apply(leg.direction(), leg.amount()); // throws INSUFFICIENT_FUNDS before mutating
            entries.save(new JournalEntry(transaction.getId(), account.getId(), leg.direction(), leg.amount(),
                    account.getBalance(), seq, now));
            posted.add(new PostedEntry(account.getId(), leg.direction(), leg.amount(), account.getBalance(), seq));
        }
        try {
            // Write now, not at commit: DB constraint errors surface here, and checks that run later in the caller's
            // transaction (the daily limit) see this posting.
            entries.flush();
        } catch (DataIntegrityViolationException e) {
            if (reverses != null && String.valueOf(e.getMessage()).contains("uq_journal_transactions_reverses")) {
                throw alreadyReversed(); // lost a race with a parallel reversal of the same transaction
            }
            throw e;
        }
        publish(transaction, locked, legs, now);
        countAfterCommit(kind, legs);
        return new PostedTransaction(transaction.getId(), kind, now, List.copyOf(posted));
    }

    /**
     * Business metrics count money that really moved: a posting can still be rolled back by the caller (the daily
     * limit check runs after post()), so the counters only move once the transaction has committed.
     */
    private void countAfterCommit(TransactionKind kind, List<Leg> legs) {
        BigDecimal moved = legs.stream().filter(l -> l.direction() == Direction.DEBIT).map(Leg::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                metrics.counter("ledger.postings", "kind", kind.name()).increment();
                // Metrics are approximate by nature (double); the ledger itself never leaves BigDecimal.
                metrics.counter("ledger.posted.amount", "kind", kind.name()).increment(moved.doubleValue());
            }
        });
    }

    /**
     * One MoneyMoved event per posting, written to the outbox in THIS transaction: committed together with the
     * money, or not at all. Keyed by the payee (or payer), so each user's events arrive in order.
     */
    private void publish(JournalTransaction transaction, Map<UUID, Account> accounts, List<Leg> legs, Instant now) {
        UUID payer = ownerOf(accounts, legs, Direction.DEBIT);
        UUID payee = ownerOf(accounts, legs, Direction.CREDIT);
        BigDecimal amount = legs.stream().filter(l -> l.direction() == Direction.DEBIT).map(Leg::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        UUID eventId = Ids.newId();
        UUID key = payee != null ? payee : payer;
        outbox.record(new OutboxEvent(eventId, MoneyMoved.TOPIC, String.valueOf(key), MoneyMoved.TYPE,
                new MoneyMoved(eventId, transaction.getId(), transaction.getKind(), amount, transaction.getMemo(),
                        payer, payee, now)));
    }

    private static UUID ownerOf(Map<UUID, Account> accounts, List<Leg> legs, Direction direction) {
        return legs.stream().filter(l -> l.direction() == direction)
                .map(l -> accounts.get(l.accountId()))
                .filter(a -> a.getType() == AccountType.USER_WALLET)
                .map(Account::getOwnerUserId)
                .findFirst().orElse(null);
    }

    @Override
    @Transactional
    public AccountView changeStatus(UUID accountId, AccountStatus status, UUID changedBy, String reason) {
        Account account = accounts.lockAllInIdOrder(Set.of(accountId)).stream().findFirst()
                .filter(a -> a.getType() == AccountType.USER_WALLET)
                .orElseThrow(() -> new DomainException(ErrorCode.WALLET_NOT_FOUND, "No such wallet."));
        if (account.getStatus() != status) {
            account.changeStatus(status);
            statusChanges.record(accountId, status, reason, changedBy, clock.instant());
        }
        return view(account);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal debitsSince(UUID accountId, TransactionKind kind, Instant since) {
        return queries.debitsSince(accountId, kind, since);
    }

    @Override
    @Transactional(readOnly = true)
    public StatementPage statement(UUID accountId, Long beforeSeq, int limit) {
        long before = beforeSeq == null ? Long.MAX_VALUE : beforeSeq;
        List<StatementLine> lines = queries.statement(accountId, before, limit + 1);
        if (lines.size() > limit) {
            List<StatementLine> page = lines.subList(0, limit);
            return new StatementPage(List.copyOf(page), page.get(page.size() - 1).seq());
        }
        return new StatementPage(lines, null);
    }

    @Override
    @Transactional(readOnly = true)
    public ReconciliationReport reconcile() {
        List<UUID> unbalanced = queries.unbalancedTransactions(REPORT_LIMIT);
        List<UUID> mismatched = queries.mismatchedAccounts(REPORT_LIMIT);
        BigDecimal total = queries.totalOfAllBalances();
        boolean healthy = unbalanced.isEmpty() && mismatched.isEmpty() && total.signum() == 0;
        return new ReconciliationReport(healthy, queries.accountCount(), total, unbalanced, mismatched);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TransactionDetail> transaction(UUID transactionId) {
        return queries.transaction(transactionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailyFlow> dailyFlows(UUID accountId, ZoneId zone, Instant from) {
        return queries.dailyFlows(accountId, zone, from);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal balanceBefore(UUID accountId, Instant instant) {
        return queries.balanceBefore(accountId, instant);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> recentCounterparties(UUID accountId, TransactionKind kind, int limit) {
        return queries.recentCounterparties(accountId, kind, limit);
    }

    private static DomainException alreadyReversed() {
        return new DomainException(ErrorCode.ALREADY_REVERSED, "This transaction has already been reversed.");
    }

    private static AccountView view(Account a) {
        return new AccountView(a.getId(), a.getOwnerUserId(), a.getType(), a.getCurrency(), a.getBalance(), a.getStatus());
    }
}
