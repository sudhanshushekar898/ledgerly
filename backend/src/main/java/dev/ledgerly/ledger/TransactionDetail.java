package dev.ledgerly.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One journal transaction with every leg and the account each leg touched. The ledger returns everything; callers
 * decide what a viewer may see (another customer's balance is private).
 */
public record TransactionDetail(UUID id, TransactionKind kind, String memo, Instant createdAt,
                                UUID reversesTransactionId, UUID reversedByTransactionId, List<Line> lines) {

    public record Line(UUID accountId, AccountType accountType, UUID ownerUserId, Direction direction,
                       BigDecimal amount, BigDecimal balanceAfter, long accountSeq) {
    }

    /** Debits equal credits. Always true for a stored transaction (a deferred DB trigger enforces it). */
    public boolean balanced() {
        BigDecimal net = lines.stream()
                .map(l -> l.direction() == Direction.CREDIT ? l.amount() : l.amount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return net.signum() == 0;
    }
}
