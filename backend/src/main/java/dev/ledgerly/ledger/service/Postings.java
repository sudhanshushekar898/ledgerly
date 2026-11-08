package dev.ledgerly.ledger.service;

import dev.ledgerly.ledger.Direction;
import dev.ledgerly.ledger.Leg;
import java.math.BigDecimal;
import java.util.List;

/** Structural rules every posting must satisfy before any row is locked. Violations are programming errors. */
final class Postings {

    static final int MAX_SCALE = 4;

    private Postings() {}

    static void validate(List<Leg> legs) {
        if (legs == null || legs.size() < 2) {
            throw new IllegalArgumentException("A posting needs at least two legs");
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (Leg leg : legs) {
            if (leg.accountId() == null || leg.direction() == null || leg.amount() == null) {
                throw new IllegalArgumentException("Every leg needs an account, a direction and an amount");
            }
            if (leg.amount().signum() <= 0) {
                throw new IllegalArgumentException("Leg amounts must be positive");
            }
            if (leg.amount().stripTrailingZeros().scale() > MAX_SCALE) {
                throw new IllegalArgumentException("Leg amounts can have at most " + MAX_SCALE + " decimal places");
            }
            if (leg.direction() == Direction.DEBIT) {
                debits = debits.add(leg.amount());
            } else {
                credits = credits.add(leg.amount());
            }
        }
        // compareTo, not equals: 10.00 and 10.0000 are the same amount but not equal() BigDecimals.
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException("Posting does not balance: debits " + debits + " != credits " + credits);
        }
    }
}
