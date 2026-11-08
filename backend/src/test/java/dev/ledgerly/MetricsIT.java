package dev.ledgerly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ledgerly.ledger.service.ReconciliationJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

/**
 * What Prometheus scrapes: the business metrics move only with real, committed activity.
 * Spring Boot turns metric exporters off in tests by default; this test opts back in.
 */
@AutoConfigureObservability
class MetricsIT extends AbstractIntegrationTest {

    @Autowired
    ReconciliationJob reconciliation;

    private String asha;

    @BeforeEach
    void setUp() throws Exception {
        asha = accessToken(register("asha@example.com", STRONG_PASSWORD));
        accessToken(register("ravi@example.com", STRONG_PASSWORD));
    }

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
    }

    /** Sum of every sample whose line starts with the metric name and contains all the given label fragments. */
    private double sample(String name, String... labels) throws Exception {
        double sum = 0;
        for (String line : scrape().split("\n")) {
            if (line.startsWith(name + "{") || line.startsWith(name + " ")) {
                boolean match = true;
                for (String label : labels) {
                    match &= line.contains(label);
                }
                if (match) {
                    sum += Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
                }
            }
        }
        return sum;
    }

    @Test
    void prometheusCanScrapeWithoutATokenAndSeesApplicationMetrics() throws Exception {
        String body = scrape();

        assertThat(body).contains("application=\"ledgerly\"");
        assertThat(body).contains("http_server_requests_seconds_bucket");
        assertThat(body).contains("outbox_pending");
        assertThat(body).contains("notifications_live_streams");
    }

    @Test
    void moneyCountersMoveOnlyForCommittedPostings() throws Exception {
        double transfers = sample("ledger_postings_total", "kind=\"TRANSFER\"");
        double moved = sample("ledger_posted_amount_total", "kind=\"TRANSFER\"");
        double insufficient = sample("app_rejections_total", "code=\"INSUFFICIENT_FUNDS\"");
        topUp(asha, "fund-asha-01", "100.00");

        transfer(asha, "send-ok-0001", "ravi@example.com", "40.00", null);
        transfer(asha, "send-no-0001", "ravi@example.com", "999.00", null); // rejected, rolled back

        assertThat(sample("ledger_postings_total", "kind=\"TRANSFER\"")).isEqualTo(transfers + 1);
        assertThat(sample("ledger_posted_amount_total", "kind=\"TRANSFER\"")).isEqualTo(moved + 40.0);
        assertThat(sample("app_rejections_total", "code=\"INSUFFICIENT_FUNDS\"")).isEqualTo(insufficient + 1);
    }

    @Test
    void anIdempotentReplayIsCounted() throws Exception {
        double replays = sample("idempotency_replays_total");

        topUp(asha, "replay-key-01", "10.00");
        topUp(asha, "replay-key-01", "10.00");

        assertThat(sample("idempotency_replays_total")).isEqualTo(replays + 1);
    }

    @Test
    void theReconciliationGaugeTurnsRedWhenTheLedgerIsTamperedWith() throws Exception {
        topUp(asha, "fund-asha-02", "75.00");
        reconciliation.run();
        assertThat(sample("ledger_reconciliation_healthy")).isEqualTo(1.0);
        assertThat(sample("ledger_total_balance")).isEqualTo(0.0);

        jdbc.update("update accounts set balance = balance + 5 where type = 'USER_WALLET'"); // money from nowhere
        reconciliation.run();

        assertThat(sample("ledger_reconciliation_healthy")).isEqualTo(0.0);
        assertThat(sample("ledger_reconciliation_mismatched_accounts")).isGreaterThanOrEqualTo(1.0);
        assertThat(sample("ledger_total_balance")).isGreaterThan(0.0);
        assertThat(sample("ledger_reconciliation_last_run")).isGreaterThan(0.0);

        jdbc.update("update accounts set balance = balance - 5 where type = 'USER_WALLET'");
        reconciliation.run();
        assertThat(sample("ledger_reconciliation_healthy")).isEqualTo(1.0);
    }
}
