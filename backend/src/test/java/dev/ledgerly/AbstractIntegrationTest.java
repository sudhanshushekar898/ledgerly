package dev.ledgerly;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/** One real PostgreSQL container (never H2) shared by every integration test in the JVM. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** A real single-node Kafka (KRaft), the same image as docker-compose. */
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper json;

    protected static final String STRONG_PASSWORD = "Correct-Horse-Battery-9";

    @BeforeEach
    void cleanDatabase() {
        // TRUNCATE does not fire the journal's row-level append-only triggers, so tests can reset it.
        // The outbox relay and the Kafka consumer keep committing in the background. TRUNCATE locks many tables at
        // once and can deadlock with them; PostgreSQL then aborts one side. Retrying the cleanup is the standard fix.
        for (int attempt = 1; ; attempt++) {
            try {
                jdbc.execute("TRUNCATE users, refresh_tokens, journal_entries, journal_transactions, idempotency_records, "
                        + "account_status_changes, outbox_events, processed_events, notifications CASCADE");
                break;
            } catch (org.springframework.dao.PessimisticLockingFailureException e) {
                if (attempt == 5) {
                    throw e;
                }
            }
        }
        jdbc.update("DELETE FROM accounts WHERE type = 'USER_WALLET'");
        jdbc.update("UPDATE accounts SET balance = 0, last_entry_seq = 0");
    }

    @Autowired
    private ApplicationRunner bootstrapAdminRunner;

    /** Recreates the operator (the per-test cleanup removes it) and logs in. */
    protected String adminToken() throws Exception {
        bootstrapAdminRunner.run(null);
        return accessToken(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"operator@example.com\",\"password\":\"Operator-Passphrase-2026\"}")).andReturn());
    }

    /** POST /transfers; returns the raw result so tests can assert any status. */
    protected MvcResult transfer(String token, String key, String recipientEmail, String amount, String memo)
            throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("recipientEmail", recipientEmail);
        body.put("amount", amount);
        if (memo != null) {
            body.put("memo", memo);
        }
        return mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                .content(json.writeValueAsString(body))).andReturn();
    }

    /** POST /wallet/topups with the given key; returns the raw result so tests can assert any status. */
    protected MvcResult topUp(String token, String key, String amount) throws Exception {
        var request = post("/api/v1/wallet/topups").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", bearer(token))
                .content("{\"amount\":\"" + amount + "\"}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn();
    }

    /** Signs up through the real API and returns the response (access token, cookie, user). */
    protected MvcResult register(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password, "fullName", "Test User"))))
                .andExpect(status().isCreated())
                .andReturn();
    }

    protected static String accessToken(MvcResult result) throws Exception {
        return new ObjectMapper().readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
