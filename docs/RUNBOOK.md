# Ledgerly runbook

One section per Prometheus alert (`ops/prometheus/alerts.yml`).
- **Local stack:**
  - Grafana dashboard "Ledgerly · Overview": http://localhost:3001
  - Prometheus alerts: http://localhost:9090/alerts
- **Rule of thumb:** money correctness first, delivery second, speed third. Never "fix" the ledger with UPDATE or DELETE on the journal. Corrections are new transactions (ADR-008), and the journal triggers reject edits anyway.

Useful commands (local compose):

```bash
# SQL shell
docker compose exec postgres psql -U ledgerly -d ledgerly
# Backend logs (JSON, ids only)
docker compose logs -f backend
# Kafka CLI inside the broker
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic wallet.money-moved.DLT --from-beginning --property print.headers=true
```

---

## LedgerOutOfBalance

**Severity:** critical. Page someone.

**Meaning:** the reconciliation job found that money was created or lost. One of these is true:
- a transaction's debits differ from its credits;
- an account's cached balance differs from the sum of its journal entries;
- the sum of all balances is not 0.

**Confirm:**
1. Grafana: "Ledger reconciliation" is red, and "Sum of all balances" is not ₹0.
2. Backend log line `LEDGER OUT OF BALANCE` lists the account and transaction ids.
3. Find the accounts whose balance disagrees with their journal:

```sql
SELECT a.id, a.type, a.balance AS cached,
       coalesce(sum(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END), 0) AS from_journal
FROM accounts a LEFT JOIN journal_entries e ON e.account_id = a.id
GROUP BY a.id HAVING a.balance <> coalesce(sum(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END), 0);
```

**Act:**
1. **Freeze** the affected wallets (`POST /api/v1/admin/wallets/{id}/freeze`) so the damage cannot spread. Reversals still work on frozen wallets.
2. The journal is the source of truth. A cached balance that disagrees was changed outside `LedgerService` (manual SQL, a bad migration). Restore it from the journal, inside one transaction, with a written incident note:
   `UPDATE accounts SET balance = <from_journal> WHERE id = '<id>'`
3. An unbalanced transaction should be impossible: the deferred trigger rejects it at COMMIT. If one exists, the trigger was disabled. Escalate.
4. The alert clears on the next reconciliation (every 60 s; 15 s locally).

## ReconciliationStale

**Meaning:** no reconciliation has completed for over 5 minutes, so we are flying blind on correctness.

**Confirm:** query `time() - ledger_reconciliation_last_run` in Prometheus, and look for exceptions around `ReconciliationJob` in the backend logs.

**Act:** check database health and long-running locks (`SELECT * FROM pg_stat_activity WHERE state <> 'idle'`), then restart the backend if the scheduler thread is stuck.

## EventsDeadLettered

**Meaning:** a `MoneyMoved` event failed 3 retries, or could not be parsed. It is parked on `wallet.money-moved.DLT`, so its notification was not created. Money is not affected; the ledger is already correct.

**Confirm:** read the DLT with headers (command above). `kafka_dlt-exception-fqcn` and `kafka_dlt-exception-message` say why.

**Act:**
1. **Bad data** (parse error, missing fields): fix the producer. The event can be dropped, or re-created from the ledger: one transaction id gives payer, payee, amount and kind.
2. **Transient failure** (database down during processing): replay the record onto the main topic. The consumer's inbox (`processed_events`) makes a replay harmless:
   `kafka-console-consumer … --topic wallet.money-moved.DLT --max-messages 1 | kafka-console-producer.sh --bootstrap-server localhost:9092 --topic wallet.money-moved`

## OutboxBacklog

**Meaning:** the oldest unpublished outbox event is over a minute old, so events are not reaching Kafka. Payments still succeed; only notifications are delayed. Nothing is lost while the rows wait.

**Confirm:**
1. Grafana: "Outbox: waiting / oldest" is rising.
2. `SELECT id, attempts, last_error FROM outbox_events WHERE published_at IS NULL ORDER BY created_at LIMIT 5;`
3. `outbox_publish_failures_total` is increasing.

**Act:**
- **Kafka down or unreachable** (`last_error` shows timeouts): restore the broker. The relay drains the backlog in order by itself.
  - Payments keep working meanwhile.
  - Measured in the load test: 15,703 queued events cleared in under 4 s once the broker returned.
- **The same event keeps failing while others would succeed:** inspect its payload. The relay stops at the first failure on purpose, to keep order.

## HighServerErrorRate

**Meaning:** more than 2% of API calls return 5xx for 5 minutes. 4xx are business rejections and do not count.

**Act:** check backend logs by request id (`X-Request-Id`, also shown in the app's API inspector). Most likely causes are the database or Kafka; check their health first.

## SlowApi

**Meaning:** p95 latency has been above 500 ms for 10 minutes.

**Act:**
1. Find the slow endpoint (`http_server_requests_seconds` by `uri`).
2. Check lock waits (`pg_locks`). The single `SYSTEM_TOPUP_SOURCE` row is a known hot spot under heavy top-up load; since ADR-014 it is sharded 8 ways, so contention is spread across 8 rows.
