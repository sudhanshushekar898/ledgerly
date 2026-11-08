package dev.ledgerly.shared.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Demo profile only ({@code ledgerly.demo.seed=true}). Lets the demo seeder post history "as of" past dates through
 * the real ledger code, so every seeded payment goes through the same locks, checks and invariants as a live one.
 * Production wires a plain system clock instead, and nothing outside the seeder can reach this class.
 */
public final class DemoClock extends Clock {

    private final Clock system = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    private volatile Duration offset = Duration.ZERO;

    /** From now on the clock reads {@code at} and keeps ticking from there. */
    public void travelTo(Instant at) {
        offset = Duration.between(system.instant(), at);
    }

    public void backToNow() {
        offset = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        return system.instant().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        // Readers only need instants; a zoned view of the same shifting time.
        DemoClock self = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return self.withZone(other);
            }

            @Override
            public Instant instant() {
                return self.instant();
            }
        };
    }
}
