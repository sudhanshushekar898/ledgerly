package dev.ledgerly.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.ledger.Ledger;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The flagship test: many real HTTP transfers at once against real PostgreSQL. Threads start together on a latch,
 * so they genuinely contend for the same wallet rows. Whatever the interleaving, money is conserved, no wallet goes
 * negative, nothing deadlocks, and the ledger reconciles.
 */
class TransferConcurrencyIT extends AbstractIntegrationTest {

    private static final int THREADS = 16;

    @Autowired
    Ledger ledger;

    private List<MvcResult> runTogether(int requests, IntFunction<Callable<MvcResult>> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < requests; i++) {
                Callable<MvcResult> call = task.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }

    private String code(MvcResult r) throws Exception {
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        return body.has("code") ? body.get("code").asText() : "OK";
    }

    private BigDecimal sumOfUserWallets() {
        return jdbc.queryForObject("select coalesce(sum(balance), 0) from accounts where type = 'USER_WALLET'",
                BigDecimal.class);
    }

    @Test
    void twoHundredRandomParallelTransfersConserveMoneyAndNeverOverdraw() throws Exception {
        int users = 10;
        List<String> emails = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (int u = 0; u < users; u++) {
            String email = "user" + u + "@example.com";
            emails.add(email);
            String token = accessToken(register(email, STRONG_PASSWORD));
            tokens.add(token);
            topUp(token, "fund-user-" + u, "1000.00");
        }
        Random random = new Random(42); // fixed seed: reproducible mix, still contended
        record Plan(int from, int to, String amount) {
        }
        List<Plan> plans = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int from = random.nextInt(users);
            int to = (from + 1 + random.nextInt(users - 1)) % users;
            plans.add(new Plan(from, to, (1 + random.nextInt(300)) + ".00"));
        }

        List<MvcResult> results = runTogether(plans.size(), i -> () -> transfer(tokens.get(plans.get(i).from()),
                "parallel-transfer-" + i, emails.get(plans.get(i).to()), plans.get(i).amount(), null));

        Map<Integer, Long> statuses = results.stream()
                .collect(Collectors.groupingBy(r -> r.getResponse().getStatus(), Collectors.counting()));
        assertThat(statuses.keySet()).as("only success or a clean business rejection, never 500/409")
                .isSubsetOf(201, 422);
        for (MvcResult r : results) {
            if (r.getResponse().getStatus() == 422) {
                assertThat(code(r)).isEqualTo("INSUFFICIENT_FUNDS");
            }
        }
        long succeeded = statuses.getOrDefault(201, 0L);
        assertThat(succeeded).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from journal_transactions where kind = 'TRANSFER'", Long.class))
                .isEqualTo(succeeded);
        assertThat(sumOfUserWallets()).isEqualByComparingTo("10000.00");
        assertThat(jdbc.queryForObject("select count(*) from accounts where balance < 0 and type = 'USER_WALLET'",
                Integer.class)).isZero();
        assertThat(ledger.reconcile().healthy()).isTrue();
    }

    @Test
    void opposingTransfersBetweenTheSamePairNeverDeadlock() throws Exception {
        String a = accessToken(register("a@example.com", STRONG_PASSWORD));
        String b = accessToken(register("b@example.com", STRONG_PASSWORD));
        topUp(a, "fund-a-00001", "1000.00");
        topUp(b, "fund-b-00001", "1000.00");

        // A->B and B->A interleaved: without a fixed lock order this is the classic deadlock.
        List<MvcResult> results = runTogether(100, i -> () -> i % 2 == 0
                ? transfer(a, "a-to-b-" + i + "-key", "b@example.com", "1.00", null)
                : transfer(b, "b-to-a-" + i + "-key", "a@example.com", "1.00", null));

        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
        assertThat(sumOfUserWallets()).isEqualByComparingTo("2000.00");
        assertThat(ledger.reconcile().healthy()).isTrue();
    }

    @Test
    void parallelTransfersCannotExceedTheDailyLimit() throws Exception {
        String sender = accessToken(register("rich@example.com", STRONG_PASSWORD));
        accessToken(register("payee@example.com", STRONG_PASSWORD));
        topUp(sender, "fund-rich-001", "100000.00");

        // 10 x 10,000 at once against a 50,000 daily limit: exactly 5 may succeed, whatever the interleaving.
        List<MvcResult> results = runTogether(10, i -> () ->
                transfer(sender, "limit-race-" + i + "-key", "payee@example.com", "10000.00", null));

        assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 201).hasSize(5);
        for (MvcResult r : results) {
            if (r.getResponse().getStatus() != 201) {
                assertThat(r.getResponse().getStatus()).isEqualTo(422);
                assertThat(code(r)).isEqualTo("DAILY_LIMIT_EXCEEDED");
            }
        }
        // sender 100,000 - 50,000 and payee 0 + 50,000: both wallets end at exactly 50,000
        assertThat(jdbc.queryForList("select balance from accounts where type = 'USER_WALLET'", BigDecimal.class))
                .hasSize(2).allSatisfy(b -> assertThat(b).isEqualByComparingTo("50000.00"));
    }

    @Test
    void twoOperatorsReversingTheSameTransactionAtOnceReverseItOnce() throws Exception {
        String asha = accessToken(register("asha@example.com", STRONG_PASSWORD));
        accessToken(register("ravi@example.com", STRONG_PASSWORD));
        topUp(asha, "fund-asha-001", "500.00");
        String txn = json.readTree(transfer(asha, "send-once-001", "ravi@example.com", "200.00", null)
                .getResponse().getContentAsString()).get("transactionId").asText();
        String admin = adminToken();

        List<MvcResult> results = runTogether(6, i -> () -> mvc.perform(post("/api/v1/admin/transactions/" + txn + "/reversal")
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"duplicate click " + i + "\"}")).andReturn());

        assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 201).hasSize(1);
        assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 409).hasSize(5);
        assertThat(jdbc.queryForObject("select count(*) from journal_transactions where kind = 'REVERSAL'", Integer.class))
                .isEqualTo(1);
        assertThat(ledger.reconcile().healthy()).isTrue();
    }
}
