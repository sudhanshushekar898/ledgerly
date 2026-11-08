package dev.ledgerly.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    private static final Duration LOCK = Duration.ofMinutes(15);

    private final User user = User.registered("asha@example.com", "{bcrypt}hash", "Asha");

    @Test
    void newUsersAreUnlockedCustomers() {
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.isLocked(NOW)).isFalse();
    }

    @Test
    void locksOnTheFifthFailure() {
        for (int i = 0; i < 4; i++) {
            assertThat(user.recordFailedLogin(NOW, 5, LOCK)).isFalse();
        }
        assertThat(user.recordFailedLogin(NOW, 5, LOCK)).isTrue();
        assertThat(user.isLocked(NOW.plusSeconds(60))).isTrue();
        assertThat(user.isLocked(NOW.plus(LOCK))).isFalse();
    }

    @Test
    void expiredLockStartsCountingAgain() {
        for (int i = 0; i < 5; i++) {
            user.recordFailedLogin(NOW, 5, LOCK);
        }
        Instant later = NOW.plus(LOCK).plusSeconds(1);
        assertThat(user.recordFailedLogin(later, 5, LOCK)).isFalse();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getFailedLoginCount()).isEqualTo(1);
    }

    @Test
    void successfulLoginResetsCounters() {
        user.recordFailedLogin(NOW, 5, LOCK);
        user.recordSuccessfulLogin(NOW);
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void adminFactoryCreatesAnAdmin() {
        assertThat(User.admin("op@example.com", "{bcrypt}x").getRole()).isEqualTo(Role.ADMIN);
    }
}
