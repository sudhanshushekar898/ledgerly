package dev.ledgerly.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.ledger.DailyFlow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class WalletReadServiceTest {

    private static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);

    private static DailyFlow day(int dayOfMonth, String in, String out, String closing) {
        return new DailyFlow(LocalDate.of(2026, 11, dayOfMonth), new BigDecimal(in), new BigDecimal(out), new BigDecimal(closing));
    }

    @Test
    void quietDaysCarryThePreviousClosingBalanceForward() {
        List<DailyFlow> filled = WalletReadService.fillDays(NOV_1, NOV_1.plusDays(4), new BigDecimal("40.00"),
                List.of(day(2, "100.00", "0.00", "140.00"), day(4, "0.00", "30.00", "110.00")));

        assertThat(filled).extracting(DailyFlow::date)
                .containsExactly(NOV_1, NOV_1.plusDays(1), NOV_1.plusDays(2), NOV_1.plusDays(3), NOV_1.plusDays(4));
        assertThat(filled).extracting(d -> d.closingBalance().toPlainString())
                .containsExactly("40.00", "140.00", "140.00", "110.00", "110.00");
        assertThat(filled.get(2).moneyIn()).isZero();
        assertThat(filled.get(3).moneyOut()).isEqualByComparingTo("30.00");
    }

    @Test
    void anEmptyAccountIsFlatAtZero() {
        List<DailyFlow> filled = WalletReadService.fillDays(NOV_1, NOV_1.plusDays(6), BigDecimal.ZERO, List.of());

        assertThat(filled).hasSize(7).allSatisfy(d -> assertThat(d.closingBalance()).isZero());
    }
}
