package dev.ledgerly.identity.service;

import dev.ledgerly.shared.error.DomainException;
import dev.ledgerly.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Length (NIST 800-63B style), BCrypt's 72-byte limit and a small common-password blocklist. */
@Component
public class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_BYTES = 72;

    private static final Set<String> COMMON = Set.of(
            "password1234", "123456789012", "qwertyuiop12", "letmein12345", "welcome12345",
            "administrator", "passw0rd1234", "iloveyou1234", "changeme1234", "ledgerly1234");

    public void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw invalid("Password must be at least " + MIN_LENGTH + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid("Password must be at most " + MAX_BYTES + " bytes.");
        }
        if (COMMON.contains(password.toLowerCase(Locale.ROOT)) || password.chars().distinct().count() < 4) {
            throw invalid("Password is too common or too simple.");
        }
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }
}
