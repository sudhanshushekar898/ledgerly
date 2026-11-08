package dev.ledgerly.wallet.service;

import dev.ledgerly.identity.UserDirectory;
import dev.ledgerly.identity.UserDirectory.Customer;
import dev.ledgerly.ledger.AccountType;
import dev.ledgerly.ledger.AccountView;
import dev.ledgerly.ledger.DailyFlow;
import dev.ledgerly.ledger.Direction;
import dev.ledgerly.ledger.Ledger;
import dev.ledgerly.ledger.TransactionDetail;
import dev.ledgerly.ledger.TransactionKind;
import dev.ledgerly.shared.error.DomainException;
import dev.ledgerly.shared.error.ErrorCode;
import dev.ledgerly.wallet.config.WalletProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read models for the customer's screens: receipts, insights, contacts and recipient verification. */
@Service
public class WalletReadService {

    public static final int MIN_DAYS = 7;
    public static final int MAX_DAYS = 90;
    private static final int CONTACTS = 8;

    /** How a ledger line is presented to the viewer. */
    public enum AccountRole { YOUR_WALLET, CUSTOMER_WALLET, TOPUP_SOURCE }

    /** balanceAfter is only ever filled for the viewer's own wallet: other people's balances are private. */
    public record ReceiptLine(AccountRole account, String label, Direction direction, BigDecimal amount,
                              BigDecimal balanceAfter) {
    }

    public record Receipt(UUID transactionId, TransactionKind kind, Direction direction, BigDecimal amount, String memo,
                          String counterpartyName, Instant createdAt, UUID reverses, UUID reversedBy,
                          List<ReceiptLine> lines, boolean balanced) {
    }

    public record Limits(BigDecimal perTransfer, BigDecimal daily, BigDecimal sentToday, BigDecimal remainingToday) {
    }

    public record Insights(List<DailyFlow> series, BigDecimal totalIn, BigDecimal totalOut, Limits limits) {
    }

    public record Contact(String name, String email) {
    }

    private final Ledger ledger;
    private final UserDirectory directory;
    private final WalletProperties properties;
    private final Clock clock;

    WalletReadService(Ledger ledger, UserDirectory directory, WalletProperties properties, Clock clock) {
        this.ledger = ledger;
        this.directory = directory;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * A receipt for one transaction, from the viewer's side. Someone who is not a party to the transaction gets
     * TRANSACTION_NOT_FOUND, never FORBIDDEN, so ids cannot be probed for existence.
     */
    @Transactional(readOnly = true)
    public Receipt receipt(UUID userId, UUID transactionId) {
        AccountView wallet = walletOf(userId);
        TransactionDetail detail = ledger.transaction(transactionId)
                .filter(t -> t.lines().stream().anyMatch(l -> l.accountId().equals(wallet.id())))
                .orElseThrow(() -> new DomainException(ErrorCode.TRANSACTION_NOT_FOUND, "No such transaction."));

        Set<UUID> others = detail.lines().stream().filter(l -> !l.accountId().equals(wallet.id()))
                .map(TransactionDetail.Line::ownerUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> names = directory.namesOf(others);

        TransactionDetail.Line mine = detail.lines().stream().filter(l -> l.accountId().equals(wallet.id()))
                .findFirst().orElseThrow();
        String counterparty = detail.lines().stream().filter(l -> !l.accountId().equals(wallet.id()))
                .map(l -> l.ownerUserId() == null ? null : names.get(l.ownerUserId())).filter(Objects::nonNull)
                .findFirst().orElse(null);
        List<ReceiptLine> lines = detail.lines().stream().map(l -> {
            if (l.accountId().equals(wallet.id())) {
                return new ReceiptLine(AccountRole.YOUR_WALLET, "Your wallet", l.direction(), l.amount(), l.balanceAfter());
            }
            if (l.accountType() == AccountType.SYSTEM_TOPUP_SOURCE) {
                return new ReceiptLine(AccountRole.TOPUP_SOURCE, "Ledgerly top-up source", l.direction(), l.amount(), null);
            }
            String owner = names.getOrDefault(l.ownerUserId(), "Another customer");
            return new ReceiptLine(AccountRole.CUSTOMER_WALLET, owner + "'s wallet", l.direction(), l.amount(), null);
        }).toList();

        return new Receipt(detail.id(), detail.kind(), mine.direction(), mine.amount(), detail.memo(), counterparty,
                detail.createdAt(), detail.reversesTransactionId(), detail.reversedByTransactionId(), lines,
                detail.balanced());
    }

    /** Daily money in/out and closing balance for the last {@code days} days (clamped to 7-90), plus today's limit. */
    @Transactional(readOnly = true)
    public Insights insights(UUID userId, int days) {
        int window = Math.max(MIN_DAYS, Math.min(days, MAX_DAYS));
        AccountView wallet = walletOf(userId);
        ZoneId zone = properties.limitZone();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate from = today.minusDays(window - 1L);
        Instant fromInstant = from.atStartOfDay(zone).toInstant();

        List<DailyFlow> series = fillDays(from, today, ledger.balanceBefore(wallet.id(), fromInstant),
                ledger.dailyFlows(wallet.id(), zone, fromInstant));
        BigDecimal totalIn = series.stream().map(DailyFlow::moneyIn).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalOut = series.stream().map(DailyFlow::moneyOut).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal sentToday = ledger.debitsSince(wallet.id(), TransactionKind.TRANSFER, today.atStartOfDay(zone).toInstant());
        BigDecimal daily = properties.dailyTransferLimit();
        Limits limits = new Limits(properties.maxTransfer(), daily, sentToday, daily.subtract(sentToday).max(BigDecimal.ZERO));
        return new Insights(series, totalIn, totalOut, limits);
    }

    /** One entry per calendar day; quiet days carry the previous closing balance forward. */
    static List<DailyFlow> fillDays(LocalDate from, LocalDate to, BigDecimal opening, List<DailyFlow> activeDays) {
        Map<LocalDate, DailyFlow> byDay = activeDays.stream().collect(Collectors.toMap(DailyFlow::date, Function.identity()));
        List<DailyFlow> result = new ArrayList<>();
        BigDecimal closing = opening;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            DailyFlow active = byDay.get(day);
            if (active != null) {
                closing = active.closingBalance();
                result.add(active);
            } else {
                result.add(new DailyFlow(day, BigDecimal.ZERO, BigDecimal.ZERO, closing));
            }
        }
        return result;
    }

    /** People the customer recently paid or was paid by, for one-tap "send again". */
    @Transactional(readOnly = true)
    public List<Contact> contacts(UUID userId) {
        List<UUID> ids = ledger.recentCounterparties(walletOf(userId).id(), TransactionKind.TRANSFER, CONTACTS);
        Map<UUID, Customer> customers = directory.customersByIds(ids);
        return ids.stream().map(customers::get).filter(Objects::nonNull)
                .map(c -> new Contact(c.name(), c.email())).toList();
    }

    /**
     * "Verify before you pay": shows whose wallet an email belongs to, like UPI showing the account holder's name.
     * It reveals no more than a transfer attempt already would; rate limiting would slow enumeration (not built yet).
     */
    @Transactional(readOnly = true)
    public Contact recipient(UUID userId, String email) {
        Customer customer = directory.findCustomerByEmail(email)
                .orElseThrow(() -> new DomainException(ErrorCode.RECIPIENT_NOT_FOUND, "No customer with this email can receive money."));
        if (customer.id().equals(userId)) {
            throw new DomainException(ErrorCode.SAME_ACCOUNT, "You cannot send money to yourself.");
        }
        return new Contact(customer.name(), customer.email());
    }

    private AccountView walletOf(UUID userId) {
        return ledger.findUserWallet(userId)
                .orElseThrow(() -> new DomainException(ErrorCode.WALLET_NOT_FOUND, "No wallet exists for this account."));
    }
}
