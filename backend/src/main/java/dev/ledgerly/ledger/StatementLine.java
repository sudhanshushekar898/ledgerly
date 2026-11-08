package dev.ledgerly.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code counterpartyUserId} is the owner of the other side's wallet, or null when it is a system account. */
public record StatementLine(long seq, UUID transactionId, TransactionKind kind, Direction direction, BigDecimal amount,
                            BigDecimal balanceAfter, String memo, UUID counterpartyUserId, Instant createdAt) {
}
