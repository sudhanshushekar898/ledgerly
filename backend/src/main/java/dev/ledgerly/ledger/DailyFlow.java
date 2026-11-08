package dev.ledgerly.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Money in, money out and the closing balance of one account on one calendar day. */
public record DailyFlow(LocalDate date, BigDecimal moneyIn, BigDecimal moneyOut, BigDecimal closingBalance) {
}
