package dev.ledgerly.wallet.service;

import dev.ledgerly.identity.UserDirectory;
import dev.ledgerly.identity.UserDirectory.Customer;
import dev.ledgerly.ledger.AccountType;
import dev.ledgerly.ledger.AccountView;
import dev.ledgerly.ledger.Leg;
import dev.ledgerly.ledger.Ledger;
import dev.ledgerly.ledger.PostedTransaction;
import dev.ledgerly.ledger.StatementLine;
import dev.ledgerly.ledger.StatementPage;
import dev.ledgerly.ledger.TransactionKind;
import dev.ledgerly.shared.error.DomainException;
import dev.ledgerly.shared.error.ErrorCode;
import dev.ledgerly.shared.money.Amounts;
import dev.ledgerly.wallet.config.WalletProperties;
import dev.ledgerly.wallet.service.IdempotencyService.Outcome;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer-facing wallet use cases. All money movement goes through the ledger facade. */
@Service
public class WalletService {

    public static final int MAX_PAGE_SIZE = 100;

    /** Response of a top-up; also what is stored for idempotent replays, so keep it backwards compatible. */
    public record TopUpReceipt(UUID transactionId, String amount, String balanceAfter, Instant createdAt) {
    }

    /** Response of a transfer; stored for replays like {@link TopUpReceipt}. */
    public record TransferReceipt(UUID transactionId, String amount, String recipientName, String memo,
                                  String balanceAfter, Instant createdAt) {
    }

    /** A statement line plus the other party's display name (null for top-ups from the system account). */
    public record StatementEntry(StatementLine line, String counterpartyName) {
    }

    public record Statement(List<StatementEntry> entries, Long nextBefore) {
    }

    private final Ledger ledger;
    private final UserDirectory directory;
    private final IdempotencyService idempotency;
    private final WalletProperties properties;
    private final Clock clock;

    WalletService(Ledger ledger, UserDirectory directory, IdempotencyService idempotency, WalletProperties properties,
                  Clock clock) {
        this.ledger = ledger;
        this.directory = directory;
        this.idempotency = idempotency;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AccountView wallet(UUID userId) {
        return ledger.findUserWallet(userId).orElseThrow(WalletService::walletNotFound);
    }

    /**
     * Adds money from the simulated payment source. One database transaction covers the idempotency claim, the
     * ledger posting and the stored response: a retry with the same key returns the first receipt.
     */
    @Transactional
    public Outcome<TopUpReceipt> topUp(UUID userId, String idempotencyKey, BigDecimal requestedAmount) {
        IdempotencyService.requireValidKey(idempotencyKey);
        BigDecimal amount = Amounts.requireValid(requestedAmount, properties.maxTopUp());
        String fingerprint = IdempotencyService.fingerprint("TOPUP", amount.toPlainString());
        return idempotency.execute(userId, idempotencyKey, fingerprint, TopUpReceipt.class, () -> {
            AccountView wallet = wallet(userId);
            // Sharded by user (ADR-014): different users' top-ups no longer queue on one system row lock.
            AccountView source = ledger.systemAccount(AccountType.SYSTEM_TOPUP_SOURCE, userId.hashCode());
            PostedTransaction posted = ledger.post(TransactionKind.TOPUP, userId,
                    List.of(Leg.debit(source.id(), amount), Leg.credit(wallet.id(), amount)));
            return new TopUpReceipt(posted.id(), Amounts.format(amount),
                    Amounts.format(posted.entryFor(wallet.id()).balanceAfter()), posted.createdAt());
        });
    }

    /**
     * Sends money to another customer. The daily limit is checked AFTER posting, inside the same transaction:
     * post() holds the sender's row lock until commit, so a parallel transfer from the same wallet waits, and when
     * it runs it sees this one. That makes "check, then act" race-free without a separate counter.
     */
    @Transactional
    public Outcome<TransferReceipt> transfer(UUID senderId, String idempotencyKey, String recipientEmail,
                                             BigDecimal requestedAmount, String memo) {
        IdempotencyService.requireValidKey(idempotencyKey);
        BigDecimal amount = Amounts.requireValid(requestedAmount, properties.maxTransfer());
        String email = recipientEmail.trim().toLowerCase(Locale.ROOT);
        String note = memo == null || memo.isBlank() ? null : memo.trim();
        String fingerprint = IdempotencyService.fingerprint("TRANSFER", email, amount.toPlainString(),
                Objects.requireNonNullElse(note, ""));
        return idempotency.execute(senderId, idempotencyKey, fingerprint, TransferReceipt.class, () -> {
            Customer recipient = directory.findCustomerByEmail(email).orElseThrow(WalletService::recipientNotFound);
            if (recipient.id().equals(senderId)) {
                throw new DomainException(ErrorCode.SAME_ACCOUNT, "You cannot send money to yourself.");
            }
            AccountView from = wallet(senderId);
            AccountView to = ledger.findUserWallet(recipient.id()).orElseThrow(WalletService::recipientNotFound);

            PostedTransaction posted = ledger.post(TransactionKind.TRANSFER, senderId, note,
                    List.of(Leg.debit(from.id(), amount), Leg.credit(to.id(), amount)));

            BigDecimal sentToday = ledger.debitsSince(from.id(), TransactionKind.TRANSFER, startOfToday());
            if (sentToday.compareTo(properties.dailyTransferLimit()) > 0) {
                BigDecimal remaining = properties.dailyTransferLimit().subtract(sentToday.subtract(amount)).max(BigDecimal.ZERO);
                throw new DomainException(ErrorCode.DAILY_LIMIT_EXCEEDED, "Daily transfer limit of "
                        + Amounts.format(properties.dailyTransferLimit()) + " reached. You can still send "
                        + Amounts.format(remaining) + " today.");
            }
            return new TransferReceipt(posted.id(), Amounts.format(amount), recipient.name(), note,
                    Amounts.format(posted.entryFor(from.id()).balanceAfter()), posted.createdAt());
        });
    }

    @Transactional(readOnly = true)
    public Statement statement(UUID userId, Long before, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        StatementPage page = ledger.statement(wallet(userId).id(), before, size);
        Map<UUID, String> names = directory.namesOf(page.lines().stream().map(StatementLine::counterpartyUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return new Statement(page.lines().stream()
                .map(l -> new StatementEntry(l, l.counterpartyUserId() == null ? null : names.get(l.counterpartyUserId())))
                .toList(), page.nextBefore());
    }

    private Instant startOfToday() {
        return LocalDate.now(clock.withZone(properties.limitZone())).atStartOfDay(properties.limitZone()).toInstant();
    }

    private static DomainException walletNotFound() {
        return new DomainException(ErrorCode.WALLET_NOT_FOUND, "No wallet exists for this account.");
    }

    private static DomainException recipientNotFound() {
        return new DomainException(ErrorCode.RECIPIENT_NOT_FOUND, "No customer with this email can receive money.");
    }
}
