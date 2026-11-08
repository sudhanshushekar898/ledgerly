package dev.ledgerly.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerly.ledger.Direction;
import dev.ledgerly.shared.error.DomainException;
import dev.ledgerly.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

    private final Account wallet = Account.userWallet(UUID.randomUUID());

    @Test
    void creditsAndDebitsMoveTheBalanceAndNumberEntries() {
        assertThat(wallet.apply(Direction.CREDIT, new BigDecimal("100.00"))).isEqualTo(1);
        assertThat(wallet.apply(Direction.DEBIT, new BigDecimal("30.50"))).isEqualTo(2);
        assertThat(wallet.getBalance()).isEqualByComparingTo("69.50");
        assertThat(wallet.getLastEntrySeq()).isEqualTo(2);
    }

    @Test
    void userWalletCanBeEmptiedExactlyButNeverOverdrawn() {
        wallet.apply(Direction.CREDIT, new BigDecimal("10.00"));
        wallet.apply(Direction.DEBIT, new BigDecimal("10.00"));
        assertThat(wallet.getBalance()).isEqualByComparingTo("0");

        assertThatThrownBy(() -> wallet.apply(Direction.DEBIT, new BigDecimal("0.01")))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));
        // rejected before mutating: balance and sequence are unchanged
        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
        assertThat(wallet.getLastEntrySeq()).isEqualTo(2);
    }

    @Test
    void freezeAndUnfreeze() {
        assertThat(wallet.isFrozen()).isFalse();
        wallet.changeStatus(dev.ledgerly.ledger.AccountStatus.FROZEN);
        assertThat(wallet.isFrozen()).isTrue();
        wallet.changeStatus(dev.ledgerly.ledger.AccountStatus.ACTIVE);
        assertThat(wallet.getStatus()).isEqualTo(dev.ledgerly.ledger.AccountStatus.ACTIVE);
    }

    @Test
    void newWalletIsEmptyInr() {
        assertThat(wallet.getBalance()).isZero();
        assertThat(wallet.getCurrency()).isEqualTo("INR");
    }
}
