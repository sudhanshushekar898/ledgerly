package dev.ledgerly.ledger;

import java.math.BigDecimal;
import java.util.UUID;

/** One side of a posting: move {@code amount} into (credit) or out of (debit) an account. */
public record Leg(UUID accountId, Direction direction, BigDecimal amount) {

    public static Leg debit(UUID accountId, BigDecimal amount) {
        return new Leg(accountId, Direction.DEBIT, amount);
    }

    public static Leg credit(UUID accountId, BigDecimal amount) {
        return new Leg(accountId, Direction.CREDIT, amount);
    }
}
