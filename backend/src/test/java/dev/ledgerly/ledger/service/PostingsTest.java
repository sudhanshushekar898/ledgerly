package dev.ledgerly.ledger.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerly.ledger.Direction;
import dev.ledgerly.ledger.Leg;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingsTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    @Test
    void acceptsBalancedPostingsIncludingDifferentScales() {
        assertThatCode(() -> Postings.validate(List.of(Leg.debit(A, new BigDecimal("10.00")),
                Leg.credit(B, new BigDecimal("10.0000"))))).doesNotThrowAnyException();
        assertThatCode(() -> Postings.validate(List.of(Leg.debit(A, new BigDecimal("10")),
                Leg.credit(B, new BigDecimal("4")), Leg.credit(C, new BigDecimal("6"))))).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnbalancedPostings() {
        assertThatThrownBy(() -> Postings.validate(List.of(Leg.debit(A, new BigDecimal("10.00")),
                Leg.credit(B, new BigDecimal("9.99"))))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not balance");
    }

    @Test
    void rejectsTooFewLegs() {
        assertThatThrownBy(() -> Postings.validate(List.of(Leg.credit(A, BigDecimal.ONE))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Postings.validate(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroNegativeMissingOrTooPreciseAmounts() {
        assertThatThrownBy(() -> Postings.validate(List.of(Leg.debit(A, BigDecimal.ZERO), Leg.credit(B, BigDecimal.ZERO))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Postings.validate(List.of(Leg.debit(A, new BigDecimal("-1")), Leg.credit(B, new BigDecimal("-1")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Postings.validate(List.of(new Leg(A, Direction.DEBIT, null), Leg.credit(B, BigDecimal.ONE))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Postings.validate(List.of(Leg.debit(A, new BigDecimal("0.00001")),
                Leg.credit(B, new BigDecimal("0.00001"))))).isInstanceOf(IllegalArgumentException.class);
    }
}
