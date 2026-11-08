package dev.ledgerly.outbox;

import java.util.UUID;

/**
 * An event to publish. {@code id} is the event id consumers deduplicate on; {@code key} picks the Kafka partition,
 * so events with the same key are delivered in order.
 */
public record OutboxEvent(UUID id, String topic, String key, String type, Object payload) {
}
