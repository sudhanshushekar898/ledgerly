package dev.ledgerly.shared.money;

import dev.ledgerly.shared.error.DomainException;
import dev.ledgerly.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rules for money at the API boundary: INR with at most 2 decimal places (paise), always BigDecimal.
 * The database keeps 4 decimal places so future fee or interest calculations can round once, at the end.
 */
public final class Amounts {

    public static final int API_SCALE = 2;

    private Amounts() {}

    /** Validates a client-supplied amount and returns it at scale 2, so "500", "500.0" and "500.00" are equal. */
    public static BigDecimal requireValid(BigDecimal amount, BigDecimal max) {
        if (amount == null || amount.signum() <= 0) {
            throw invalid("Amount must be greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > API_SCALE) {
            throw invalid("Amount can have at most 2 decimal places.");
        }
        if (amount.compareTo(max) > 0) {
            throw invalid("Amount exceeds the limit of " + format(max) + ".");
        }
        return amount.setScale(API_SCALE, RoundingMode.UNNECESSARY);
    }

    /** API representation: a string such as "1500.00", so JavaScript clients never round it through a double. */
    public static String format(BigDecimal amount) {
        return amount.setScale(API_SCALE, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.AMOUNT_INVALID, message);
    }
}
