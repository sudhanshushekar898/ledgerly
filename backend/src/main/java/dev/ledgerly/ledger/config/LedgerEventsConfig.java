package dev.ledgerly.ledger.config;

import dev.ledgerly.ledger.MoneyMoved;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** The ledger owns its event topic. Keyed by user id, so one user's events stay in order. */
@Configuration
class LedgerEventsConfig {

    @Bean
    NewTopic moneyMovedTopic() {
        return TopicBuilder.name(MoneyMoved.TOPIC).partitions(3).replicas(1).build();
    }
}
