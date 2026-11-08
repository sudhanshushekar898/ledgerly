package dev.ledgerly.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.shared.error.DomainException;
import dev.ledgerly.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/** The ledger's guarantees, tested at the facade and directly against the database's own guards. */
class LedgerIT extends AbstractIntegrationTest {

    @Autowired
    Ledger ledger;

    private AccountView source;
    private AccountView wallet;

    @BeforeEach
    void accounts() {
        source = ledger.systemAccount(AccountType.SYSTEM_TOPUP_SOURCE);
        wallet = ledger.openUserWallet(UUID.randomUUID());
    }

    private PostedTransaction topUp(String amount) {
        BigDecimal value = new BigDecimal(amount);
        return ledger.post(TransactionKind.TOPUP, wallet.ownerUserId(),
                List.of(Leg.debit(source.id(), value), Leg.credit(wallet.id(), value)));
    }

    private BigDecimal balance(UUID accountId) {
        return jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, accountId);
    }

    @Test
    void theTopUpSourceIsShardedStablyAndShardsStillReconcile() {
        var shards = new java.util.HashSet<UUID>();
        for (int key = 0; key < 64; key++) {
            shards.add(ledger.systemAccount(AccountType.SYSTEM_TOPUP_SOURCE, key).id());
        }
        assertThat(shards).hasSize(8);
        assertThat(ledger.systemAccount(AccountType.SYSTEM_TOPUP_SOURCE, 12345).id())
                .isEqualTo(ledger.systemAccount(AccountType.SYSTEM_TOPUP_SOURCE, 12345).id());
        assertThat(ledger.systemAccount(AccountType.SYSTEM_TOPUP_SOURCE, -7)).isNotNull(); // negative hash codes

        BigDecimal ten = new BigDecimal("10.00");
        for (int key = 0; key < 8; key++) {
            AccountView shard = ledger.systemAccount(AccountType.SYSTEM_TOPUP_SOURCE, key);
            ledger.post(TransactionKind.TOPUP, wallet.ownerUserId(), List.of(Leg.debit(shard.id(), ten), Leg.credit(wallet.id(), ten)));
        }

        assertThat(balance(wallet.id())).isEqualByComparingTo("80.00");
        assertThat(ledger.reconcile().healthy()).isTrue();
        assertThat(ledger.reconcile().totalOfAllBalances()).isZero();
    }

    @Test
    void openUserWalletIsIdempotent() {
        assertThat(ledger.openUserWallet(wallet.ownerUserId()).id()).isEqualTo(wallet.id());
        assertThat(ledger.findUserWallet(wallet.ownerUserId())).isPresent();
        assertThat(ledger.findUserWallet(UUID.randomUUID())).isEmpty();
    }

    @Test
    void postingMovesMoneyWithoutCreatingAnyAndNumbersEntries() {
        topUp("100.00");
        PostedTransaction second = topUp("25.50");

        assertThat(balance(wallet.id())).isEqualByComparingTo("125.50");
        assertThat(balance(source.id())).isEqualByComparingTo("-125.50");
        assertThat(second.entryFor(wallet.id()).accountSeq()).isEqualTo(2);
        assertThat(second.entryFor(wallet.id()).balanceAfter()).isEqualByComparingTo("125.50");
        ReconciliationReport report = ledger.reconcile();
        assertThat(report.healthy()).isTrue();
        assertThat(report.totalOfAllBalances()).isZero();
    }

    @Test
    void overdraftIsRejectedAndLeavesNoTrace() {
        topUp("10.00");
        BigDecimal tooMuch = new BigDecimal("10.01");

        assertThatThrownBy(() -> ledger.post(TransactionKind.TRANSFER, wallet.ownerUserId(),
                List.of(Leg.debit(wallet.id(), tooMuch), Leg.credit(source.id(), tooMuch))))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));

        assertThat(balance(wallet.id())).isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("select count(*) from journal_transactions", Integer.class)).isEqualTo(1);
        assertThat(ledger.reconcile().healthy()).isTrue();
    }

    @Test
    void unknownAccountAndUnbalancedPostingsAreRejectedInJava() {
        assertThatThrownBy(() -> ledger.post(TransactionKind.TOPUP, wallet.ownerUserId(),
                List.of(Leg.debit(source.id(), BigDecimal.ONE), Leg.credit(UUID.randomUUID(), BigDecimal.ONE))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.post(TransactionKind.TOPUP, wallet.ownerUserId(),
                List.of(Leg.debit(source.id(), BigDecimal.TEN), Leg.credit(wallet.id(), BigDecimal.ONE))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reversalsCanOnlyBePostedThroughReverse() {
        assertThatThrownBy(() -> ledger.post(TransactionKind.REVERSAL, wallet.ownerUserId(),
                List.of(Leg.debit(source.id(), BigDecimal.ONE), Leg.credit(wallet.id(), BigDecimal.ONE))))
                .isInstanceOf(IllegalArgumentException.class);
        // and the database refuses a REVERSAL row that does not point at the transaction it undoes
        assertThatThrownBy(() -> jdbc.update("insert into journal_transactions (id, kind, initiated_by, created_at) "
                + "values (?, 'REVERSAL', ?, now())", UUID.randomUUID(), wallet.ownerUserId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_journal_transactions_reversal");
    }

    @Test
    void debitsSinceSumsOnlyThatKindAndWindow() {
        topUp("100.00");
        BigDecimal ten = new BigDecimal("10.00");
        AccountView other = ledger.openUserWallet(UUID.randomUUID());
        ledger.post(TransactionKind.TRANSFER, wallet.ownerUserId(), List.of(Leg.debit(wallet.id(), ten), Leg.credit(other.id(), ten)));
        ledger.post(TransactionKind.TRANSFER, wallet.ownerUserId(), List.of(Leg.debit(wallet.id(), ten), Leg.credit(other.id(), ten)));

        Instant hourAgo = Instant.now().minusSeconds(3600);
        assertThat(ledger.debitsSince(wallet.id(), TransactionKind.TRANSFER, hourAgo)).isEqualByComparingTo("20.00");
        assertThat(ledger.debitsSince(wallet.id(), TransactionKind.TOPUP, hourAgo)).isZero(); // wallet was credited, not debited
        assertThat(ledger.debitsSince(wallet.id(), TransactionKind.TRANSFER, Instant.now().plusSeconds(60))).isZero();
    }

    @Test
    void dailyFlowsCutDaysInTheCustomersTimeZone() {
        // 20:00 UTC on 7 Nov is 01:30 IST on 8 Nov: same moment, different calendar day.
        Instant at = Instant.parse("2026-11-07T20:00:00Z");
        UUID txn = UUID.randomUUID();
        jdbc.update("insert into journal_transactions (id, kind, initiated_by, created_at) values (?, 'TOPUP', ?, ?)",
                txn, wallet.ownerUserId(), Timestamp.from(at));
        jdbc.update("""
                insert into journal_entries (id, transaction_id, account_id, direction, amount, balance_after, account_seq, created_at)
                values (?, ?, ?, 'DEBIT', 100, -100, 1, ?), (?, ?, ?, 'CREDIT', 100, 100, 1, ?)""",
                UUID.randomUUID(), txn, source.id(), Timestamp.from(at), UUID.randomUUID(), txn, wallet.id(), Timestamp.from(at));

        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        List<DailyFlow> india = ledger.dailyFlows(wallet.id(), java.time.ZoneId.of("Asia/Kolkata"), from);
        List<DailyFlow> utc = ledger.dailyFlows(wallet.id(), java.time.ZoneOffset.UTC, from);

        assertThat(india).singleElement().satisfies(d -> {
            assertThat(d.date()).isEqualTo(java.time.LocalDate.of(2026, 11, 8));
            assertThat(d.moneyIn()).isEqualByComparingTo("100");
            assertThat(d.moneyOut()).isZero();
            assertThat(d.closingBalance()).isEqualByComparingTo("100");
        });
        assertThat(utc).singleElement().extracting(DailyFlow::date).isEqualTo(java.time.LocalDate.of(2026, 11, 7));
        assertThat(ledger.balanceBefore(wallet.id(), at.minusSeconds(60))).isZero();
        assertThat(ledger.balanceBefore(wallet.id(), at.plusSeconds(60))).isEqualByComparingTo("100");
    }

    @Test
    void databaseRejectsAnUnbalancedTransactionEvenIfJavaIsBypassed() {
        UUID txn = UUID.randomUUID();
        jdbc.update("insert into journal_transactions (id, kind, initiated_by, created_at) values (?, 'TOPUP', ?, now())",
                txn, wallet.ownerUserId());

        // Autocommit: the deferred constraint trigger runs when this single-leg insert commits.
        assertThatThrownBy(() -> jdbc.update("""
                insert into journal_entries (id, transaction_id, account_id, direction, amount, balance_after, account_seq, created_at)
                values (?, ?, ?, 'CREDIT', 50, 50, 1, ?)""", UUID.randomUUID(), txn, wallet.id(), Timestamp.from(Instant.now())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("does not balance");
    }

    @Test
    void journalIsAppendOnly() {
        topUp("40.00");

        assertThatThrownBy(() -> jdbc.update("update journal_entries set amount = amount + 1"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from journal_entries"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from journal_transactions"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void databaseRejectsANegativeUserWalletBalance() {
        assertThatThrownBy(() -> jdbc.update("update accounts set balance = -0.01 where id = ?", wallet.id()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_accounts_no_overdraft");
    }

    @Test
    void reconciliationCatchesATamperedCachedBalance() {
        topUp("75.00");
        jdbc.update("update accounts set balance = balance + 5 where id = ?", wallet.id()); // money appears from nowhere

        ReconciliationReport report = ledger.reconcile();

        assertThat(report.healthy()).isFalse();
        assertThat(report.mismatchedAccounts()).containsExactly(wallet.id());
        assertThat(report.totalOfAllBalances()).isEqualByComparingTo("5");
        assertThat(report.unbalancedTransactions()).isEmpty();
    }

    @Test
    void statementPagesNewestFirstWithACursor() {
        for (int i = 1; i <= 5; i++) {
            topUp(i + ".00");
        }

        StatementPage first = ledger.statement(wallet.id(), null, 2);
        assertThat(first.lines()).extracting(StatementLine::seq).containsExactly(5L, 4L);
        assertThat(first.lines().get(0).balanceAfter()).isEqualByComparingTo("15.00");
        StatementPage second = ledger.statement(wallet.id(), first.nextBefore(), 2);
        assertThat(second.lines()).extracting(StatementLine::seq).containsExactly(3L, 2L);
        StatementPage last = ledger.statement(wallet.id(), second.nextBefore(), 2);
        assertThat(last.lines()).extracting(StatementLine::seq).containsExactly(1L);
        assertThat(last.nextBefore()).isNull();
        assertThat(last.lines().get(0).kind()).isEqualTo(TransactionKind.TOPUP);
        assertThat(last.lines().get(0).direction()).isEqualTo(Direction.CREDIT);
    }
}
