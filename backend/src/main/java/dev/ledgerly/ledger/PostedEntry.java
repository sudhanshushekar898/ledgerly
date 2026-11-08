package dev.ledgerly.ledger;

import java.math.BigDecimal;
import java.util.UUID;

public record PostedEntry(UUID accountId, Direction direction, BigDecimal amount, BigDecimal balanceAfter, long accountSeq) {
}
