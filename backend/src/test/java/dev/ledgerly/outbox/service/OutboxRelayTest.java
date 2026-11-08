package dev.ledgerly.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.ledgerly.outbox.repository.OutboxStore;
import dev.ledgerly.outbox.repository.OutboxStore.Pending;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionOperations;

/** The relay's failure behaviour, with the broker simulated: "Kafka is down" must never lose or reorder events. */
class OutboxRelayTest {

    @SuppressWarnings("unchecked")
    private final KafkaOperations<String, String> kafka = mock(KafkaOperations.class);
    private final OutboxStore store = mock(OutboxStore.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final OutboxRelay relay = new OutboxRelay(store, kafka, TransactionOperations.withoutTransaction(), Clock.systemUTC(),
            metrics);

    private final Pending first = new Pending(UUID.randomUUID(), "t", "k", "{\"n\":1}");
    private final Pending second = new Pending(UUID.randomUUID(), "t", "k", "{\"n\":2}");
    private final Pending third = new Pending(UUID.randomUUID(), "t", "k", "{\"n\":3}");

    private static CompletableFuture<SendResult<String, String>> acked() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, String>> brokerDown() {
        return CompletableFuture.failedFuture(new TimeoutException("Topic not present in metadata after 5000 ms"));
    }

    @Test
    void sendsTheWholeBatchThenMarksEveryAcknowledgedEventPublished() {
        when(store.lockBatch(anyInt())).thenReturn(List.of(first, second));
        when(kafka.send("t", "k", "{\"n\":1}")).thenReturn(acked());
        when(kafka.send("t", "k", "{\"n\":2}")).thenReturn(acked());

        assertThat(relay.relayOnce()).isEqualTo(2);

        verify(store).markPublished(eq(List.of(first.id(), second.id())), any());
    }

    @Test
    void whenTheBrokerIsDownNothingIsMarkedPublished() {
        when(store.lockBatch(anyInt())).thenReturn(List.of(first, second));
        when(kafka.send(any(), any(), any())).thenReturn(brokerDown());

        assertThat(relay.relayOnce()).isZero();

        verify(store).markFailed(eq(first.id()), any());
        verify(store).markPublished(eq(List.of()), any());
        assertThat(metrics.counter("outbox.publish.failures").count()).isEqualTo(1);
    }

    @Test
    void nothingAfterTheFirstFailureIsMarkedPublishedSoItIsResentInOrder() {
        when(store.lockBatch(anyInt())).thenReturn(List.of(first, second, third));
        when(kafka.send("t", "k", "{\"n\":1}")).thenReturn(acked());
        when(kafka.send("t", "k", "{\"n\":2}")).thenReturn(brokerDown());
        when(kafka.send("t", "k", "{\"n\":3}")).thenReturn(acked()); // delivered, but after a gap

        assertThat(relay.relayOnce()).isEqualTo(1);

        verify(store).markPublished(eq(List.of(first.id())), any()); // third will be resent; the consumer dedupes it
        verify(store).markFailed(eq(second.id()), any());
        verify(store, never()).markFailed(eq(third.id()), any());
    }

    @Test
    void aSendThatThrowsSynchronouslyCountsAsAFailureNotACrash() {
        when(store.lockBatch(anyInt())).thenReturn(List.of(first));
        when(kafka.send(any(), any(), any())).thenThrow(new IllegalStateException("producer closed"));

        assertThat(relay.relayOnce()).isZero();

        verify(store).markFailed(eq(first.id()), any());
    }

    @Test
    void theNextTickAfterTheBrokerRecoversPublishesTheWaitingEvents() {
        when(store.lockBatch(anyInt())).thenReturn(List.of(first));
        when(kafka.send(any(), any(), any())).thenReturn(brokerDown()).thenReturn(acked());

        assertThat(relay.relayOnce()).isZero();
        assertThat(relay.relayOnce()).isEqualTo(1);

        verify(store).markPublished(eq(List.of(first.id())), any());
    }
}
