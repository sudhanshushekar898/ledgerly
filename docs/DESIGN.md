# Ledgerly: design narrative

*Written as a prose narrative. The diagrams and API live in [ARCHITECTURE.md](ARCHITECTURE.md), the individual decisions in [adr/](adr/0001-adrs.md), and the measured numbers in [PERFORMANCE.md](PERFORMANCE.md).*

## 1. Purpose

Ledgerly is a digital wallet: customers add money, send it to each other and see their history. The product surface is deliberately small. The engineering goal is not: **a wallet must never create, lose or double-move money, even when requests race, clients retry, processes crash or the message broker is down.** This document explains how the design meets that goal, what it costs, and what is still open.

## 2. Tenets

These are the rules we used when two good options conflicted.

1. **Correct before fast.** We take a slower design that we can prove correct over a faster one we would have to trust. Every performance change was measured, and it kept every invariant.
2. **The database is the last line of defence.** Application code checks the rules first. The schema enforces them again with constraints and triggers, so a bug in Java cannot silently corrupt the books.
3. **Every retry is safe.** Networks fail mid-response. Any money-moving request can be sent twice and still take effect once.
4. **One process until a measurement says otherwise.** A modular monolith with enforced boundaries, not microservices that would turn one ACID transaction into a saga.
5. **If it can break, it is observable.** Each failure mode we know of has a metric, an alert and a runbook entry.

## 3. The problem in detail

A naïve wallet keeps one `balance` column and runs `UPDATE balance = balance - x`. That design fails in five ways:

1. **Race:** two parallel transfers both read ₹1,000 and both spend ₹800.
2. **Deadlock:** a transfer A→B and a transfer B→A each lock one account and wait for the other.
3. **Lost response:** the server commits, but the response is lost; the client retries and the payment happens twice.
4. **Partial work:** the payment commits, then the notification step crashes, so the recipient is never told. Or the notification is sent for a payment that was rolled back.
5. **No proof:** a balance column says what the balance is, but not why. Nothing can show that it is right.

## 4. The design

### 4.1 A double-entry ledger is the source of truth

Every money movement is one journal transaction with two or more entries whose debits equal their credits. Customers' wallets and the system accounts (the top-up source) are all ordinary accounts, so **the sum of every balance in the system is always exactly zero**.

The rules are enforced in layers:

- **In Java:** the posting engine checks balance before it writes.
- **At commit:** a deferred constraint trigger in PostgreSQL rejects any transaction whose entries do not net to zero.
- **Append-only:** triggers forbid updating or deleting journal entries. A mistake is corrected with a reversal, a new transaction that mirrors the old one (ADR-008), never with an edit.
- **No overdraft:** a `CHECK` constraint stops a customer wallet going below zero.

The `balance` column on each account is a cache, updated in the same transaction. A reconciliation job recomputes every balance from the journal every 60 seconds and raises an alarm on any difference (ADR-013).

Money is `numeric(19,4)` in the database and `BigDecimal` in Java. ArchUnit fails the build if a money field is ever a `double`.

### 4.2 Concurrency: ordered pessimistic locks

A posting locks every account it touches with `SELECT … FOR UPDATE`, **in ascending id order, sorted by PostgreSQL**. Every transaction takes locks in the same global order, so a deadlock is impossible, not just unlikely (ADR-003).

We chose pessimistic locks over optimistic retries because a popular account would make optimistic writers retry each other in a storm.

Rules that depend on the current state are checked **after the lock is held**:

- **Daily limit:** the posting holds the sender's row lock until commit. A parallel transfer from the same wallet waits, then sees the first one when it runs.
- **Reversals:** the check that a transaction has not already been reversed also runs under the lock.

These rules are tested with real parallelism against real PostgreSQL:

- 200 random parallel transfers: money is conserved and no wallet goes negative.
- 100 transfers A↔B in both directions at once: no deadlock.
- 10 parallel transfers of ₹10,000 against a ₹50,000 daily limit: exactly 5 succeed.
- 6 parallel reversals of one transaction: exactly 1 succeeds.

### 4.3 Idempotency: every money-moving POST takes a key

The client sends an `Idempotency-Key` header. In the **same database transaction** as the posting, the server inserts `(user, key, request fingerprint)` with `ON CONFLICT DO NOTHING`.

- **A racing duplicate** blocks on the unique index. When the first request commits, the duplicate replays the stored response. If the first one rolled back, the duplicate runs itself.
- **The same key with a different body** is a client bug and gets `422`, never a silent replay.
- **One key per attempt:** the browser creates one key per payment attempt and reuses it on retries (ADR-004, ADR-010).

### 4.4 Events: transactional outbox → Kafka → idempotent consumer

The posting writes a `MoneyMoved` row to `outbox_events` **inside its own transaction**. That gives two guarantees:

- A committed payment always has an event.
- A rolled-back payment never has one.

**The relay** locks a batch with `FOR UPDATE SKIP LOCKED`, publishes it to Kafka with `acks=all` and an idempotent producer, and marks only the unbroken prefix of acknowledged events as published (ADR-006, ADR-015).

**The notification consumer** records each event id in `processed_events` in the same transaction as its own writes. A redelivery is therefore a no-op. A message that cannot be processed is retried 3 times with backoff, then parked on a dead-letter topic, which raises an alert.

**The browser** receives "money received" over server-sent events, about one second after the sender pays (ADR-012).

**Delivery is at least once, and duplicates are harmless.** Exactly-once delivery across a database and a broker is not something we claim.

### 4.5 Module boundaries

The modules are `identity`, `ledger`, `wallet`, `outbox`, `notification` and `shared`. Each one exposes only its root-package facade. ArchUnit fails the build:

- if a module reaches into another module's internals;
- if anything but `ledger` touches the ledger's repositories;
- if a module dependency cycle appears.

This is what would make a later split into services mechanical rather than a rewrite.

## 5. Alternatives we rejected

| Option | Why not |
|---|---|
| **Microservices** (wallet, ledger, notification) | A transfer would span services and need a saga with compensations, for a single developer and one domain. The modules and the outbox already give the seams (ADR-001). |
| **Balance column only** | No audit trail, and no way to prove the books are right (ADR-002). |
| **Optimistic locking for balances** | Retry storms on hot accounts. `@Version` stays only as a second line of defence. |
| **Publishing to Kafka inside the request** | The payment and the publish would succeed or fail independently: the dual-write problem. The outbox makes them atomic. |
| **Exactly-once delivery** | The guarantee stops at Kafka's edge; our consumer writes to PostgreSQL. Idempotent consumers are simpler and honest. |
| **WebSockets for notifications** | Traffic is one-way. SSE is plain HTTP and works through the existing auth and proxy. |

## 6. Failure modes and what happens

| Failure | Behaviour | Proof |
|---|---|---|
| Two transfers race on one wallet | The second waits for the lock, then sees the first one's balance | 200-transfer concurrency test |
| Client retries after a lost response | The stored receipt is replayed; money moves once | integration tests; Resilience Lab in the UI |
| Kafka down | Payments keep succeeding; events wait in the outbox; delivered in order on recovery | load test: 0 failed payments with the broker stopped; 15,703 events drained in under 4 s |
| Poison message | Retried, then parked on the DLT; later messages still flow; alert fires | EventFlowIT |
| A bug corrupts a balance | Reconciliation turns red within one interval and `LedgerOutOfBalance` fires | tampered +₹5 by SQL, alert firing in under 1 minute |
| Backend crashes mid-relay | Unmarked events are resent; consumers deduplicate | relay and consumer tests |

## 7. Performance (measured)

On a laptop, with 50 concurrent users for 60 s and 0 failed requests:

- **Transfers:** about 500–600 per second, p99 around 250 ms.
- **Hot row:** the load test confirmed a hot spot predicted at design time. Every top-up queued on one system account row: 136 top-ups/s. Sharding it 8 ways raised that to 368–447/s (ADR-014).
- **Relay:** it showed the outbox relay sent one event at a time (210 events/s), slower than the payment rate. Pipelining raised it to at least 4,190 events/s (ADR-015).

After about 213,000 payments, the ledger still summed to 0.0000. The caveats (shared machine, variance between runs) are in PERFORMANCE.md.

## 8. Open questions and next steps

1. **More than one instance.** Rate limiting is in-process today, and SSE streams live in one instance's memory. Two instances need Redis (rate limits, pub/sub for streams). Not built.
2. **Retention.** Expired idempotency records are deleted only when their key is reused. A scheduled purge is needed before real traffic.
3. **Daily limit and reversals.** A reversed transfer still counts toward the sender's daily limit. That is conservative and documented, but customers may see it as unfair.
4. **Reversal after the money is spent.** A reversal fails if the recipient already spent the money. That case is left to manual operations; a real product needs a recovery flow.
5. **Real money in.** Top-ups come from a simulated source. A real one needs a payment-gateway callback, which is idempotent by the same pattern.
