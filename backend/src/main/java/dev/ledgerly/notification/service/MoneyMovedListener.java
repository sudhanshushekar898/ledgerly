package dev.ledgerly.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.ledgerly.ledger.MoneyMoved;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes ledger events. Parsing is done here, not by a Kafka deserializer, so a malformed message surfaces as a
 * JsonProcessingException that the error handler sends straight to the dead-letter topic.
 */
@Component
class MoneyMovedListener {

    private final ObjectReader reader;
    private final NotificationService notifications;

    MoneyMovedListener(ObjectMapper mapper, NotificationService notifications) {
        // Tolerant reader: fields added by a newer producer are ignored instead of poisoning the consumer.
        this.reader = mapper.readerFor(MoneyMoved.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.notifications = notifications;
    }

    @KafkaListener(topics = MoneyMoved.TOPIC, groupId = NotificationService.CONSUMER)
    void on(String payload) throws JsonProcessingException {
        MoneyMoved event = reader.readValue(payload);
        if (event.eventId() == null || event.kind() == null || event.amount() == null) {
            throw new MalformedEventException("MoneyMoved without eventId, kind or amount");
        }
        notifications.handle(event);
    }

    /** A JsonProcessingException, so it is classified as not retryable, like a parse failure. */
    static class MalformedEventException extends JsonProcessingException {
        MalformedEventException(String message) {
            super(message);
        }
    }
}
