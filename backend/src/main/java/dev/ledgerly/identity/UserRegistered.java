package dev.ledgerly.identity;

import java.util.UUID;

/**
 * Published inside the sign-up transaction. Listeners run synchronously in that same transaction, so a user and
 * their wallet are created together or not at all. (Kafka is for reactions that may happen later.)
 */
public record UserRegistered(UUID userId) {
}
