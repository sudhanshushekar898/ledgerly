package dev.ledgerly.shared.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
class JpaConfig {

    /** Ticks in whole microseconds, the precision PostgreSQL stores, so saved and re-read instants are equal. */
    @Bean
    @ConditionalOnProperty(name = "ledgerly.demo.seed", havingValue = "false", matchIfMissing = true)
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    /** Only when demo seeding is on; see {@link DemoClock}. */
    @Bean
    @ConditionalOnProperty(name = "ledgerly.demo.seed", havingValue = "true")
    DemoClock demoClock() {
        return new DemoClock();
    }

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.<TemporalAccessor>of(Instant.now(clock));
    }
}
