package dev.ledgerly.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerly.shared.error.DomainException;
import dev.ledgerly.shared.error.ErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AmountsTest {

    private static final BigDecimal MAX = new BigDecimal("100000.00");

    @ParameterizedTest
    @CsvSource({"500,500.00", "500.0,500.00", "0.01,0.01", "100000.00,100000.00", "1E+2,100.00"})
    void normalisesValidAmountsToTwoDecimals(String input, String expected) {
        assertThat(Amounts.requireValid(new BigDecimal(input), MAX).toPlainString()).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "0.00", "-1", "0.001", "10.555", "100000.01", "1E+20"})
    void rejectsInvalidAmountsWithAmountInvalid(String input) {
        BigDecimal amount = input == null ? null : new BigDecimal(input);
        assertThatThrownBy(() -> Amounts.requireValid(amount, MAX))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.AMOUNT_INVALID));
    }

    @ParameterizedTest
    @CsvSource({"1500.0000,1500.00", "0.0050,0.00", "0.0150,0.02", "7,7.00"})
    void formatsForTheApiWithBankersRounding(String input, String expected) {
        assertThat(Amounts.format(new BigDecimal(input))).isEqualTo(expected);
    }
}
