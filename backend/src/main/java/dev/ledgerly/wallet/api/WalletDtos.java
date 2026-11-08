package dev.ledgerly.wallet.api;

import dev.ledgerly.ledger.AccountStatus;
import dev.ledgerly.ledger.AccountView;
import dev.ledgerly.ledger.DailyFlow;
import dev.ledgerly.ledger.Direction;
import dev.ledgerly.ledger.TransactionKind;
import dev.ledgerly.shared.money.Amounts;
import dev.ledgerly.wallet.service.WalletReadService.AccountRole;
import dev.ledgerly.wallet.service.WalletReadService.Contact;
import dev.ledgerly.wallet.service.WalletReadService.Insights;
import dev.ledgerly.wallet.service.WalletReadService.Receipt;
import dev.ledgerly.wallet.service.WalletReadService.ReceiptLine;
import dev.ledgerly.wallet.service.WalletService.Statement;
import dev.ledgerly.wallet.service.WalletService.StatementEntry;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of /wallet and /transfers. Amounts go out as strings ("1500.00"), never JSON numbers. */
final class WalletDtos {

    private WalletDtos() {}

    record TopUpRequest(@NotNull BigDecimal amount) {
    }

    record TransferRequest(@NotBlank @Email @Size(max = 254) String recipientEmail,
                           @NotNull BigDecimal amount,
                           @Size(max = 140) @Pattern(regexp = "[^\\p{Cntrl}]*", message = "must not contain control characters")
                           String memo) {
    }

    record WalletResponse(UUID accountId, String currency, String balance, AccountStatus status) {
        static WalletResponse from(AccountView a) {
            return new WalletResponse(a.id(), a.currency(), Amounts.format(a.balance()), a.status());
        }
    }

    record StatementItem(long seq, UUID transactionId, TransactionKind kind, Direction direction, String amount,
                         String balanceAfter, String memo, String counterpartyName, Instant createdAt) {
        static StatementItem from(StatementEntry e) {
            var l = e.line();
            return new StatementItem(l.seq(), l.transactionId(), l.kind(), l.direction(), Amounts.format(l.amount()),
                    Amounts.format(l.balanceAfter()), l.memo(), e.counterpartyName(), l.createdAt());
        }
    }

    record StatementResponse(List<StatementItem> items, Long nextBefore) {
        static StatementResponse from(Statement statement) {
            return new StatementResponse(statement.entries().stream().map(StatementItem::from).toList(),
                    statement.nextBefore());
        }
    }

    /** balanceAfter is present only on the caller's own line; other wallets' balances are never returned. */
    record ReceiptLineResponse(AccountRole account, String label, Direction direction, String amount, String balanceAfter) {
        static ReceiptLineResponse from(ReceiptLine l) {
            return new ReceiptLineResponse(l.account(), l.label(), l.direction(), Amounts.format(l.amount()),
                    l.balanceAfter() == null ? null : Amounts.format(l.balanceAfter()));
        }
    }

    record ReceiptResponse(UUID transactionId, TransactionKind kind, Direction direction, String amount, String memo,
                           String counterpartyName, Instant createdAt, UUID reverses, UUID reversedBy,
                           List<ReceiptLineResponse> lines, boolean balanced) {
        static ReceiptResponse from(Receipt r) {
            return new ReceiptResponse(r.transactionId(), r.kind(), r.direction(), Amounts.format(r.amount()), r.memo(),
                    r.counterpartyName(), r.createdAt(), r.reverses(), r.reversedBy(),
                    r.lines().stream().map(ReceiptLineResponse::from).toList(), r.balanced());
        }
    }

    record DayFlowResponse(LocalDate date, String moneyIn, String moneyOut, String closingBalance) {
        static DayFlowResponse from(DailyFlow d) {
            return new DayFlowResponse(d.date(), Amounts.format(d.moneyIn()), Amounts.format(d.moneyOut()),
                    Amounts.format(d.closingBalance()));
        }
    }

    record LimitsResponse(String perTransfer, String daily, String sentToday, String remainingToday) {
    }

    record InsightsResponse(List<DayFlowResponse> series, String totalIn, String totalOut, LimitsResponse limits) {
        static InsightsResponse from(Insights i) {
            var l = i.limits();
            return new InsightsResponse(i.series().stream().map(DayFlowResponse::from).toList(),
                    Amounts.format(i.totalIn()), Amounts.format(i.totalOut()),
                    new LimitsResponse(Amounts.format(l.perTransfer()), Amounts.format(l.daily()),
                            Amounts.format(l.sentToday()), Amounts.format(l.remainingToday())));
        }
    }

    record ContactResponse(String name, String email) {
        static ContactResponse from(Contact c) {
            return new ContactResponse(c.name(), c.email());
        }
    }
}
