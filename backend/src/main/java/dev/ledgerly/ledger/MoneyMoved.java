package dev.ledgerly.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published once per ledger posting, through the outbox, in the same database transaction (ADR-006).
 * {@code payerUserId} owns the debited wallet and {@code payeeUserId} the credited one; either is null when that
 * side is a system account (a top-up has no customer payer).
 */
public record MoneyMoved(UUID eventId, UUID transactionId, TransactionKind kind, BigDecimal amount, String memo,
                         UUID payerUserId, UUID payeeUserId, Instant occurredAt) {

    public static final String TOPIC = "wallet.money-moved";
    public static final String TYPE = "MoneyMoved";
}
