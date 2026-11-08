package dev.ledgerly.outbox.service;

import dev.ledgerly.outbox.repository.OutboxStore;
import dev.ledgerly.outbox.repository.OutboxStore.Pending;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Publishes committed outbox events to Kafka, at least once.
 * <ul>
 *   <li>Pipelined: the whole batch is handed to the producer, then all acknowledgements are awaited. The first
 *       version waited for each event before sending the next and drained only ~210 events/s, below the ~400-600
 *       payments/s the app sustains, so the backlog grew under load. The idempotent producer keeps per-partition
 *       order, and acks=all means an acknowledged event is durable.</li>
 *   <li>Only the unbroken prefix up to the first failure is marked published. Anything after it is sent again
 *       later; consumers deduplicate on the event id, so a resend is harmless and nothing is lost.</li>
 *   <li>While Kafka is down, events simply wait in the table.</li>
 * </ul>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    static final int BATCH = 500;
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxStore store;
    private final KafkaOperations<String, String> kafka;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final Counter failures;

    OutboxRelay(OutboxStore store, KafkaOperations<String, String> kafka, TransactionOperations transactions, Clock clock,
                MeterRegistry metrics) {
        this.failures = metrics.counter("outbox.publish.failures");
        this.store = store;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Keeps draining while batches come back full, instead of idling between ticks during a backlog. */
    @Scheduled(fixedDelayString = "${ledgerly.outbox.poll-interval:300ms}")
    public void tick() {
        while (relayOnce() == BATCH) {
            // a full batch means more is waiting
        }
    }

    /** Publishes one batch; returns how many events were confirmed and marked published. */
    public int relayOnce() {
        Integer published = transactions.execute(status -> {
            List<Pending> batch = store.lockBatch(BATCH);
            List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(batch.size());
            for (Pending event : batch) {
                sends.add(send(event));
            }
            List<UUID> confirmed = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                Pending event = batch.get(i);
                try {
                    sends.get(i).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    confirmed.add(event.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    store.markFailed(event.id(), "interrupted");
                    break;
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    String reason = String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                    store.markFailed(event.id(), reason.length() > 500 ? reason.substring(0, 500) : reason);
                    failures.increment();
                    log.warn("Outbox event {} not published yet, will retry: {}", event.id(), e.getClass().getSimpleName());
                    break; // keep order: nothing after a failure is marked published
                }
            }
            store.markPublished(confirmed, clock.instant());
            return confirmed.size();
        });
        return published == null ? 0 : published;
    }

    private CompletableFuture<SendResult<String, String>> send(Pending event) {
        try {
            return kafka.send(event.topic(), event.key(), event.payload());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e); // e.g. metadata timeout thrown synchronously
        }
    }
}
