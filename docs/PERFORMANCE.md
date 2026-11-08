# Performance: measured, not estimated

Every number below comes from a Gatling report on this machine. Two bottlenecks were found and fixed, and each fix has its own before and after measurement.

## Setup

- **Machine:** one laptop, 12 cores; Docker Desktop with 8 GB.
- **What runs in Docker:** the whole compose stack (backend, Postgres 16, Kafka, Prometheus, Grafana, Nginx). Gatling runs on the same machine, so it competes with them for CPU.
- **Load:** closed model with 50 concurrent virtual users for 60 s, each sending requests back to back with no think time.
- **Data:** 100 synthetic users, each funded with ₹10,000 before the run.
- **Each request:** ₹1 with a fresh `Idempotency-Key`.
  - Transfers go to a random other user.
  - Top-ups come from the system "top-up source" account.
- **Pass criterion:** under 1% failed requests. Every run reported 0.
- **Requests hit the backend directly** on port 8090, with the full path: JWT check, row locks, ledger insert, outbox insert and idempotency record.

How to run:

```bash
docker compose --profile full up -d --build
bash loadtest/run.sh transfer 50 60     # or: topup 50 60
```

Reports are written to `loadtest/target/gatling/<run>/index.html`.

## Finding 1: every top-up waited on one row

Every top-up debits the same system account. That row is locked `FOR UPDATE`, so 50 users queue behind it no matter how many cores there are. It was flagged as a likely hot spot at design time. The baseline confirmed it: a top-up does *less* work than a transfer, yet ran 4.4× slower.

**Fix:** split the system account into 8 shards (ADR-014, migration V7).
- Each user's top-ups always use the same shard: `floorMod(userId.hashCode(), 8)`.
- Shards are ledger accounts like any other, so double entry and reconciliation still cover them.

| Top-up, 50 users × 60 s | Requests | req/s | p50 | p95 | p99 | Failures |
|---|---|---|---|---|---|---|
| Before (1 source row) | 8,151 | **136** | 349 ms | 544 ms | 657 ms | 0 |
| After, run 1 (8 shards) | 22,087 | **368** | 120 ms | 276 ms | 411 ms | 0 |
| After, run 2 (8 shards) | 26,848 | **447** | 97 ms | 226 ms | 325 ms | 0 |

Result:
- throughput up **2.7–3.3×**;
- p50 down about 3×;
- p99 down 38–51%.

## Finding 2: events reached Kafka slower than payments were made

While checking the ledger after the runs, the outbox held **36,834 unpublished events**, and the number kept growing.

**Cause:** the relay sent one event, waited for Kafka's acknowledgement, then sent the next, in batches of 100 every 300 ms.

**Measured drain rate, with no load running:** 30,734 → 24,434 pending in 30 s, which is **210 events/s**. The app makes 400–600 payments/s, so under sustained load the backlog could only grow. Notifications would arrive later and later.

**Fix (ADR-015):**
- Hand the whole batch (500) to the producer, then wait for all the acknowledgements.
- Mark the batch published with one JDBC batch update.
- Loop straight away while batches come back full.

Safety is unchanged:
- `acks=all` with the idempotent producer;
- only the unbroken prefix up to the first failure is marked published;
- consumers deduplicate whatever is sent again.

| Relay | Measurement | Result |
|---|---|---|
| Before (one send at a time) | backlog drained with no load | **210 events/s** |
| After (pipelined batch of 500) | Kafka stopped, 15,703 events queued, then Kafka restarted | all drained within 3.75 s of the first event leaving: **≥ 4,190 events/s (~20×)** |
| After, under load | transfer run at 545 req/s, outbox sampled every second | backlog peaked at **759** and returned to **0** (before: grew without limit) |

The drain figure is a lower bound: the backlog was sampled roughly once a second, so the true time is at most 3.75 s.

## Transfers

| Transfer, 50 users × 60 s | Requests | req/s | p50 | p95 | p99 | Notes |
|---|---|---|---|---|---|---|
| Baseline (empty outbox) | 35,836 | 597 | 77 ms | 161 ms | 222 ms | first run of the day |
| Old relay, backlog draining in the background | 23,646 | 394 | 96 ms | 277 ms | 552 ms | |
| Old relay, backlog draining in the background | 28,746 | 479 | 99 ms | 193 ms | 260 ms | |
| Pipelined relay | 32,674 | **545** | 80 ms | 169 ms | 249 ms | backlog peaked at 759, then 0 |

**Honest reading:**
- Sharding does not touch the transfer path, so it does not explain the 394–597 spread.
- The slow runs happened while the old relay was working through a 30k+ backlog, which competes for Postgres. That is plausible, but not proven.
- Laptop noise matters too: Gatling shares the 12 cores with the system under test.
- So the claim is **"about 500–600 transfers/s at p99 around 250 ms on a laptop"**, not any single best run.

## Resilience under load

With Kafka **stopped**, two 30 s transfer runs completed **8,353 and 15,461 requests with 0 failures**. Payments never depend on the broker.
- Events waited in the outbox.
- The relay recorded 29 publish failures, as designed.
- Everything was delivered after Kafka came back.

## Correctness after all runs

- 212,917 journal transactions in the database.
- Sum of all balances: **0.0000**.
- Reconciliation gauge healthy, with 0 mismatched accounts and 0 unbalanced transactions.
- 0 unpublished events once the relay caught up.

## Discarded run

One transfer run reported 9,164 requests at 10 req/s, "completed in 914 seconds" against a 60 s cap: the laptop had gone to sleep mid-run. It was thrown away and re-run. Every other run's req/s equals its request count ÷ 60 (or ÷ 30), which shows it ran for its full window.

## Not measured (yet)

- More than one backend instance.
- A dedicated load generator machine.
- Read endpoints under load (statement, insights).
- Latency through Nginx.
