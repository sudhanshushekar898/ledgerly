package dev.ledgerly.identity.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerly.shared.error.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsALongPassphrase() {
        assertThatCode(() -> policy.validate("Correct-Horse-Battery-9")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"short", "Password1234", "aaaaaaaaaaaaaaaa", "abababababab"})
    void rejectsShortCommonOrSimplePasswords(String password) {
        assertThatThrownBy(() -> policy.validate(password)).isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsPasswordsBeyondBcryptsByteLimit() {
        assertThatThrownBy(() -> policy.validate("ü".repeat(40))).isInstanceOf(DomainException.class);
    }
}
