package dev.ledgerly.outbox.config;

import dev.ledgerly.outbox.repository.OutboxStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The relay and the live-stream heartbeat run on Spring's scheduler. */
@Configuration
@EnableScheduling
class OutboxConfig {

    /** Backlog gauges, read on each scrape: a growing backlog or age means events are not reaching Kafka. */
    @Bean
    MeterBinder outboxMetrics(OutboxStore store) {
        return registry -> {
            Gauge.builder("outbox.pending", store, OutboxStore::pendingCount)
                    .description("Outbox events not yet published to Kafka").register(registry);
            Gauge.builder("outbox.oldest.age", store, OutboxStore::oldestPendingAgeSeconds)
                    .baseUnit("seconds").description("Age of the oldest unpublished outbox event").register(registry);
        };
    }
}
