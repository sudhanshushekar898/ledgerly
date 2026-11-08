package dev.ledgerly.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.ledger.Ledger;
import dev.ledgerly.shared.persistence.DemoClock;
import dev.ledgerly.wallet.demo.DemoDataSeeder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** The demo seeder posts weeks of history through the real ledger, keeps the books balanced, and runs only once. */
@TestPropertySource(properties = {"ledgerly.demo.seed=true", "ledgerly.demo.password=Correct-Horse-Battery-9"})
class DemoDataSeederIT extends AbstractIntegrationTest {

    @Autowired
    private DemoDataSeeder seeder;

    @Autowired
    private DemoClock clock;

    @Autowired
    private Ledger ledger;

    private JsonNode getAsDemo(String path) throws Exception {
        String token = accessToken(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + DemoDataSeeder.DEMO_EMAIL + "\",\"password\":\"" + STRONG_PASSWORD + "\"}"))
                .andReturn());
        return json.readTree(mvc.perform(get(path).header("Authorization", bearer(token))).andReturn()
                .getResponse().getContentAsString());
    }

    @Test
    void seedsWeeksOfBalancedHistoryThroughTheLedgerAndOnlyOnce() throws Exception {
        seeder.run(null);

        JsonNode insights = getAsDemo("/api/v1/wallet/insights?days=60");
        long activeDays = StreamSupport.stream(insights.get("series").spliterator(), false)
                .filter(d -> new BigDecimal(d.get("moneyIn").asText()).signum() > 0
                        || new BigDecimal(d.get("moneyOut").asText()).signum() > 0) // money is sent as strings
                .count();
        assertThat(activeDays).isGreaterThan(30);
        assertThat(getAsDemo("/api/v1/wallet/contacts").size()).isEqualTo(4);

        assertThat(ledger.reconcile().healthy()).isTrue();
        Integer payments = jdbc.queryForObject("select count(*) from journal_transactions", Integer.class);
        Instant oldest = jdbc.queryForObject("select min(created_at) from journal_transactions", java.sql.Timestamp.class)
                .toInstant();
        assertThat(oldest).isBefore(Instant.now().minus(55, ChronoUnit.DAYS));
        assertThat(jdbc.queryForObject("select count(*) from journal_transactions where created_at > now()", Integer.class))
                .isZero();
        assertThat(clock.instant().toEpochMilli()).isCloseTo(Instant.now().toEpochMilli(), within(5_000L));

        seeder.run(null); // second start: nothing new
        assertThat(jdbc.queryForObject("select count(*) from journal_transactions", Integer.class)).isEqualTo(payments);
    }
}
