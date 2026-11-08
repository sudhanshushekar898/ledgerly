package dev.ledgerly.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.ledger.Ledger;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Real parallel HTTP requests against real PostgreSQL. All threads wait on a latch and start together, so they
 * genuinely contend for the same rows (the wallet and the shared top-up source account).
 */
class TopUpConcurrencyIT extends AbstractIntegrationTest {

    private static final int THREADS = 16;

    @Autowired
    Ledger ledger;

    private String token;

    @BeforeEach
    void signUp() throws Exception {
        token = accessToken(register("load@example.com", STRONG_PASSWORD));
    }

    private List<MvcResult> runTogether(int requests, java.util.function.IntFunction<Callable<MvcResult>> task)
            throws Exception {
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

    @Test
    void fiftyParallelTopUpsAllLandExactlyOnceAndTheLedgerReconciles() throws Exception {
        List<MvcResult> results = runTogether(50, i -> () -> topUp(token, "parallel-key-" + i, "10.00"));

        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
        UUIDs wallet = walletIds();
        assertThat(wallet.balance()).isEqualByComparingTo("500.00");
        // gapless per-account sequence 1..50: no lost update, no double apply
        assertThat(jdbc.queryForList("select account_seq from journal_entries where account_id = ? order by account_seq",
                Long.class, wallet.id())).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 50).boxed().toList());
        assertThat(ledger.reconcile().healthy()).isTrue();
    }

    @Test
    void tenParallelRetriesWithOneKeyMoveMoneyOnceAndAllGetTheSameReceipt() throws Exception {
        List<MvcResult> results = runTogether(10, i -> () -> topUp(token, "same-key-0001", "75.00"));

        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
        List<String> transactionIds = new ArrayList<>();
        for (MvcResult r : results) {
            JsonNode body = json.readTree(r.getResponse().getContentAsString());
            transactionIds.add(body.get("transactionId").asText());
        }
        assertThat(transactionIds).containsOnly(transactionIds.get(0));
        assertThat(results).filteredOn(r -> "false".equals(r.getResponse().getHeader("Idempotent-Replayed"))).hasSize(1);
        assertThat(walletIds().balance()).isEqualByComparingTo("75.00");
        assertThat(jdbc.queryForObject("select count(*) from journal_transactions", Integer.class)).isEqualTo(1);
        assertThat(ledger.reconcile().healthy()).isTrue();
    }

    private record UUIDs(java.util.UUID id, BigDecimal balance) {
    }

    private UUIDs walletIds() {
        return jdbc.queryForObject("select id, balance from accounts where type = 'USER_WALLET'",
                (rs, i) -> new UUIDs(rs.getObject("id", java.util.UUID.class), rs.getBigDecimal("balance")));
    }
}
