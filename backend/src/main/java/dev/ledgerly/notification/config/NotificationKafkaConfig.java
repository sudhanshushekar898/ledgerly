package dev.ledgerly.notification.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.ledgerly.ledger.MoneyMoved;
import dev.ledgerly.notification.service.LiveStream;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * ADR-009: a failing message is retried 3 times with exponential backoff (200 ms, 400 ms, 800 ms), then parked on
 * the dead-letter topic with the exception in its headers, so one bad message never blocks the partition.
 * A message that cannot even be parsed skips the retries: retrying cannot fix it.
 */
@Configuration
class NotificationKafkaConfig {

    public static final String DLT = MoneyMoved.TOPIC + ".DLT";

    @Bean
    NewTopic moneyMovedDeadLetterTopic() {
        return TopicBuilder.name(DLT).partitions(1).replicas(1).build();
    }

    /** Spring Boot plugs this into the default listener container factory. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<String, String> kafka, MeterRegistry metrics) {
        var deadLetters = new DeadLetterPublishingRecoverer(kafka, (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));
        // Every parked message is counted: the EventsDeadLettered alert fires on any increase (RUNBOOK.md).
        var deadLettered = metrics.counter("notifications.dead.lettered");
        ConsumerRecordRecoverer recoverer = (record, ex) -> {
            deadLettered.increment();
            deadLetters.accept(record, ex);
        };
        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(200);
        backOff.setMultiplier(2);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(JsonProcessingException.class);
        return handler;
    }

    @Bean
    MeterBinder liveStreamMetrics(LiveStream live) {
        return registry -> Gauge.builder("notifications.live.streams", live, LiveStream::openStreams)
                .description("Browser tabs with an open live-notification stream").register(registry);
    }
}
