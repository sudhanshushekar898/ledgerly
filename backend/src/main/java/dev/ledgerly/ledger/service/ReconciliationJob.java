package dev.ledgerly.ledger.service;

import dev.ledgerly.ledger.Ledger;
import dev.ledgerly.ledger.ReconciliationReport;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Proves the ledger continuously instead of only when an operator asks: every interval it reconciles the whole
 * ledger and publishes the result as gauges. Prometheus alerts on {@code ledger_reconciliation_healthy == 0}
 * (LedgerOutOfBalance) and on a stale {@code ledger_reconciliation_last_run} (the job itself stopped).
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final Ledger ledger;
    private final Clock clock;
    private final AtomicReference<Snapshot> last = new AtomicReference<>(new Snapshot(1, 0, 0, BigDecimal.ZERO, 0));

    /**
     * healthy is 1 or 0; until the first run it reads as healthy (no evidence of a problem yet). The total stays a
     * BigDecimal here; it becomes a double only when handed to the metrics library, which has no decimal type.
     */
    private record Snapshot(int healthy, int mismatchedAccounts, int unbalancedTransactions, BigDecimal totalOfAllBalances,
                            long lastRunEpochSeconds) {
    }

    ReconciliationJob(Ledger ledger, Clock clock, MeterRegistry metrics) {
        this.ledger = ledger;
        this.clock = clock;
        Gauge.builder("ledger.reconciliation.healthy", last, s -> s.get().healthy())
                .description("1 if every transaction balances, every cached balance matches its entries and all balances sum to 0")
                .register(metrics);
        Gauge.builder("ledger.reconciliation.mismatched.accounts", last, s -> s.get().mismatchedAccounts()).register(metrics);
        Gauge.builder("ledger.reconciliation.unbalanced.transactions", last, s -> s.get().unbalancedTransactions()).register(metrics);
        Gauge.builder("ledger.total.balance", last, s -> s.get().totalOfAllBalances().doubleValue())
                .description("Sum of every account balance in INR; must always be exactly 0").register(metrics);
        Gauge.builder("ledger.reconciliation.last.run", last, s -> s.get().lastRunEpochSeconds())
                .description("Unix time of the last completed reconciliation").register(metrics);
    }

    @Scheduled(fixedDelayString = "${ledgerly.reconciliation.interval:60s}", initialDelayString = "${ledgerly.reconciliation.initial-delay:5s}")
    public void tick() {
        run();
    }

    public ReconciliationReport run() {
        ReconciliationReport report = ledger.reconcile();
        last.set(new Snapshot(report.healthy() ? 1 : 0, report.mismatchedAccounts().size(),
                report.unbalancedTransactions().size(), report.totalOfAllBalances(),
                clock.instant().getEpochSecond()));
        if (!report.healthy()) {
            // Ids only, never names or amounts per person. RUNBOOK.md, "LedgerOutOfBalance", explains what to do.
            log.error("LEDGER OUT OF BALANCE: total={} mismatchedAccounts={} unbalancedTransactions={}",
                    report.totalOfAllBalances(), report.mismatchedAccounts(), report.unbalancedTransactions());
        }
        return report;
    }
}
