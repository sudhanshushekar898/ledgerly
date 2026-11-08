package dev.ledgerly.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.ledger.MoneyMoved;
import dev.ledgerly.ledger.TransactionKind;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;

/** Ledger posting → outbox → relay → Kafka → consumer → notification, against real PostgreSQL and real Kafka. */
class EventFlowIT extends AbstractIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    KafkaTemplate<String, String> kafka;

    private String asha;
    private String ravi;
    private UUID raviId;

    @BeforeEach
    void setUp() throws Exception {
        asha = accessToken(register("asha@example.com", STRONG_PASSWORD));
        ravi = accessToken(register("ravi@example.com", STRONG_PASSWORD));
        raviId = jdbc.queryForObject("select id from users where email = 'ravi@example.com'", UUID.class);
        topUp(asha, "fund-asha-01", "1000.00");
    }

    private JsonNode inbox(String token) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString());
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String event(UUID eventId, UUID transactionId, String amount) throws Exception {
        return json.writeValueAsString(new MoneyMoved(eventId, transactionId, TransactionKind.TRANSFER, new BigDecimal(amount),
                null, null, raviId, Instant.now()));
    }

    @Test
    void aTransferWritesOneEventInTheSameTransactionAndTheRecipientIsNotified() throws Exception {
        transfer(asha, "send-0001", "ravi@example.com", "250.50", "Dinner");

        assertThat(count("select count(*) from outbox_events")).as("top-up + transfer").isEqualTo(2);
        await().atMost(WAIT).until(() -> count("select count(*) from outbox_events where published_at is null") == 0);
        await().atMost(WAIT).until(() -> inbox(ravi).get("unread").asLong() == 1);

        JsonNode received = inbox(ravi).get("items").get(0);
        assertThat(received.get("kind").asText()).isEqualTo("MONEY_RECEIVED");
        assertThat(received.get("amount").asText()).isEqualTo("250.50");
        assertThat(received.get("counterpartyName").asText()).isEqualTo("Test User");
        assertThat(received.get("memo").asText()).isEqualTo("Dinner");
        await().atMost(WAIT).until(() -> inbox(asha).get("items").size() == 1);
        assertThat(inbox(asha).get("items").get(0).get("kind").asText()).isEqualTo("MONEY_ADDED");
    }

    @Test
    void aRejectedPaymentLeavesNoEventBehind() throws Exception {
        transfer(asha, "send-0002", "ravi@example.com", "5000.00", null); // more than the balance: rolled back

        assertThat(count("select count(*) from outbox_events")).as("only the top-up").isEqualTo(1);
    }

    @Test
    void aRedeliveredEventCreatesOneNotification() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        String payload = event(eventId, transactionId, "42.00");

        kafka.send(MoneyMoved.TOPIC, raviId.toString(), payload);
        kafka.send(MoneyMoved.TOPIC, raviId.toString(), payload);
        // Same key, same partition: once this marker is processed, both copies before it were too.
        UUID marker = UUID.randomUUID();
        kafka.send(MoneyMoved.TOPIC, raviId.toString(), event(marker, UUID.randomUUID(), "1.00"));

        await().atMost(WAIT).until(() -> count("select count(*) from processed_events where event_id = ?", marker) == 1);
        assertThat(count("select count(*) from notifications where transaction_id = ?", transactionId)).isEqualTo(1);
    }

    @Test
    void aPoisonMessageIsParkedOnTheDeadLetterTopicWithoutBlockingTheNext() throws Exception {
        kafka.send(MoneyMoved.TOPIC, raviId.toString(), "this is not json");
        UUID next = UUID.randomUUID();
        kafka.send(MoneyMoved.TOPIC, raviId.toString(), event(next, UUID.randomUUID(), "7.00"));

        await().atMost(WAIT).until(() -> count("select count(*) from processed_events where event_id = ?", next) == 1);

        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(MoneyMoved.TOPIC + ".DLT"));
            await().atMost(WAIT).until(() -> {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if ("this is not json".equals(record.value())) {
                        assertThat(record.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
                        return true;
                    }
                }
                return false;
            });
        }
    }

    @Test
    void aReversalNotifiesBothSides() throws Exception {
        String txn = json.readTree(transfer(asha, "send-0003", "ravi@example.com", "100.00", null)
                .getResponse().getContentAsString()).get("transactionId").asText();
        mvc.perform(post("/api/v1/admin/transactions/" + txn + "/reversal").header("Authorization", bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong person\"}"));

        await().atMost(WAIT).until(() -> inbox(asha).get("items").size() == 2); // money added + payment returned
        assertThat(inbox(asha).get("items").get(0).get("kind").asText()).isEqualTo("PAYMENT_RETURNED");
        await().atMost(WAIT).until(() -> inbox(ravi).get("items").size() == 2); // received + reversed
        assertThat(inbox(ravi).get("items").get(0).get("kind").asText()).isEqualTo("PAYMENT_REVERSED");
    }

    @Test
    void readAllClearsTheUnreadCountAndTheApiIsForCustomers() throws Exception {
        await().atMost(WAIT).until(() -> inbox(asha).get("unread").asLong() == 1);

        mvc.perform(post("/api/v1/notifications/read-all").header("Authorization", bearer(asha)));

        assertThat(inbox(asha).get("unread").asLong()).isZero();
        assertThat(mvc.perform(get("/api/v1/notifications")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(adminToken())))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }
}
