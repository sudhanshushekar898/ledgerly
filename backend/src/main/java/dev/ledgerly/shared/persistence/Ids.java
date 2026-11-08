package dev.ledgerly.shared.persistence;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

/** Application-generated UUIDv7 ids: time-ordered (index friendly) and not guessable. */
public final class Ids {

    private Ids() {}

    public static UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
