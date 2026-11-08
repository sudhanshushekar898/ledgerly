package dev.ledgerly.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerly.outbox.repository.OutboxStore;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public facade of the outbox module (ADR-006). Writing the event is part of the caller's business transaction:
 * MANDATORY makes it impossible to record an event outside one. Publishing happens later, in OutboxRelay.
 */
@Component
public class Outbox {

    private final OutboxStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    Outbox(OutboxStore store, ObjectMapper mapper, Clock clock) {
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(OutboxEvent event) {
        try {
            store.insert(event.id(), event.topic(), event.key(), event.type(), mapper.writeValueAsString(event.payload()),
                    clock.instant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise outbox event " + event.type(), e);
        }
    }
}
