# Ledgerly: architecture

## 1. What this is

Ledgerly is a digital wallet for a fictional fintech. Users sign up, get a wallet in INR, top up from a simulated payment source, send money to each other, and see their history. The product surface is small on purpose. The focus is **money correctness under concurrency and failure**:

- **Can two parallel transfers overdraw an account?** No: ordered row locks, proven by concurrency tests.
- **Does a retried request move money twice?** No: idempotency keys.
- **Do balances always reconcile?** Yes: a double-entry ledger whose balances sum to zero, checked continuously by a reconciliation job.
- **Can an event be lost when the broker is down, or published for a payment that rolled back?** No: a transactional outbox.

The reasoning, alternatives and failure modes are in [DESIGN.md](DESIGN.md). Each decision has an ADR in [adr/](adr/0001-adrs.md). The measured numbers are in [PERFORMANCE.md](PERFORMANCE.md).

## 2. Modules

It is one Spring Boot application (a modular monolith, ADR-001), in packages `dev.ledgerly.<module>`. ArchUnit enforces the boundaries: a module may use another module's root-package facade only, and only `ledger` may touch balances.

| Module | Responsibility |
|---|---|
| `shared` | errors (RFC 7807 problem+json), money helpers, clock |
| `identity` | users, BCrypt passwords, login lockout, RS256 JWT access tokens, rotating refresh tokens with reuse detection, bootstrap admin |
| `ledger` | accounts, immutable journal, posting engine, reversals, wallet freeze, reconciliation. **The only module that changes balances.** |
| `wallet` | customer operations: top-up, transfer, daily limit, idempotency, statement, receipts, insights, contacts |
| `outbox` | outbox table and the relay to Kafka |
| `notification` | Kafka consumer with inbox dedup, in-app notifications, live stream over SSE |

## 3. Ledger

**Tables:**
- `accounts(id, owner_user_id null, type USER_WALLET|SYSTEM_TOPUP_SOURCE, shard, currency, balance numeric(19,4), status, version, ...)`.
  - `CHECK (type <> 'USER_WALLET' OR balance >= 0)`: the database refuses an overdraft even if the code is wrong.
- `journal_transactions(id, kind TOPUP|TRANSFER|REVERSAL, memo, reverses_transaction_id, created_at)`.
- `journal_entries(id, transaction_id, account_id, direction DEBIT|CREDIT, amount, balance_after, account_seq, created_at)`. Insert-only: triggers reject UPDATE and DELETE.

**Rules:**
- **One sign rule for every account:** `balance = credits - debits`.
  - A top-up debits a `SYSTEM_TOPUP_SOURCE` account, which goes negative, and credits the wallet.
  - So **the sum of all balances is always zero**.
- **Balanced at commit:** a deferred constraint trigger rejects, at COMMIT, any transaction whose entries do not net to zero.
- **Cached balance:** `balance` is a projection updated in the same transaction as the entries, so reads are O(1). The reconciliation job recomputes every balance from the journal and alarms on any difference (ADR-002, ADR-013).
- **Gapless sequence:** each entry gets a gapless per-account `account_seq`, written under the row lock. Statements paginate on it.
- **Sharded top-up source:** there are 8 top-up source accounts, and each user maps to one. This removes a hot row found by the load test (ADR-014).
- **New wallets:** a new user gets a wallet through a synchronous `UserRegistered` event inside the sign-up transaction.

### Transfer (one database transaction)

1. Claim `(user_id, Idempotency-Key)` with `INSERT … ON CONFLICT DO NOTHING`.
   - Already completed with the same request: replay the stored response.
   - Same key, different request: `422 IDEMPOTENCY_KEY_REUSED`.
   - A racing duplicate waits on the unique index (ADR-004).
2. Lock both accounts `FOR UPDATE` in ascending id order, sorted by PostgreSQL. A deadlock is impossible (ADR-003).
3. Check funds and wallet status. Insert the transaction and its two entries, and update both balances.
4. Check the daily limit while still holding the sender's lock.
5. Insert the `MoneyMoved` outbox event.
6. Store the idempotency response, then commit.

## 4. Events

```
ledger post() ──same DB tx──▶ outbox_events
                                  │ OutboxRelay (SKIP LOCKED, batches of 500, acks=all)
                                  ▼
                          Kafka wallet.money-moved (3 partitions, keyed by payee)
                                  │ consumer group "notifications"
                                  │ retries 3× with backoff → wallet.money-moved.DLT
                                  ▼
          processed_events (dedup) + notifications ──after commit──▶ SSE ──▶ toast in the browser
```

- **Delivery** is at least once. The relay may send an event twice; the consumer records each event id in `processed_events` in the same transaction as its work, so a duplicate is a no-op (ADR-006, ADR-009).
- **Relay throughput:** the relay pipelines each batch and drains at least 4,190 events/s (ADR-015). While Kafka is down, payments keep succeeding and events wait in the table.
- **Kafka** runs as a single KRaft node in Compose (`apache/kafka:3.8.0`). The tests use the same image through Testcontainers.

## 5. API (base `/api/v1`)

Every money-moving POST needs an `Idempotency-Key` header (8–64 characters).

| Endpoint | Who | Notes |
|---|---|---|
| `POST /auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `GET /auth/me` | anyone / signed in | refresh and logout use the HttpOnly cookie and need `X-Requested-With` |
| `GET /wallet` | USER | balance and status of the caller's own wallet (never chosen by id, so no IDOR) |
| `POST /wallet/topups` | USER | `{amount}`; at most 100,000.00 per top-up |
| `GET /wallet/statement?before=&limit=` | USER | newest first; keyset cursor on the per-account sequence; at most 100 per page |
| `POST /transfers` | USER | `{recipientEmail, amount, memo?}`; at most 25,000.00 per transfer and 50,000.00 per calendar day (Asia/Kolkata) |
| `GET /wallet/transactions/{id}` | USER | receipt with every double-entry line; other wallets' balances are never returned; 404 (not 403) if you are not a party |
| `GET /wallet/insights?days=30` | USER | per-day money in and out with closing balance, totals, today's limit usage |
| `GET /wallet/contacts` | USER | up to 8 people you recently paid or were paid by |
| `GET /recipients?email=` | USER | "verify before you pay": the account holder's name |
| `GET /notifications`, `POST /notifications/read-all` | USER | latest 20 and the unread count |
| `GET /notifications/stream` | USER | server-sent events: `ready`, then `notification` events, with a keep-alive every 20 s |
| `GET /admin/reconciliation` | ADMIN | healthy flag, total of all balances, mismatched accounts |
| `GET /admin/wallets?email=` | ADMIN | find a customer's wallet |
| `POST /admin/wallets/{id}/freeze`, `/unfreeze` | ADMIN | `{reason}`; audited in `account_status_changes` |
| `POST /admin/transactions/{id}/reversal` | ADMIN | `{reason}`; at most once per transaction; also works on frozen wallets |

Errors are `problem+json` with a stable `code` and the request id:
- **Money:** `AMOUNT_INVALID`, `INSUFFICIENT_FUNDS`, `SAME_ACCOUNT`, `RECIPIENT_NOT_FOUND`, `DAILY_LIMIT_EXCEEDED`, `WALLET_FROZEN`, `WALLET_NOT_FOUND`.
- **Idempotency:** `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_REUSED`.
- **Reversals:** `TRANSACTION_NOT_FOUND`, `ALREADY_REVERSED`, `NOT_REVERSIBLE`.
- **Auth:** `UNAUTHENTICATED`, `FORBIDDEN`, `ACCOUNT_LOCKED`, `EMAIL_TAKEN`.
- **General:** `VALIDATION_FAILED`, `BAD_REQUEST`, `RESOURCE_NOT_FOUND`, `INTERNAL_ERROR`.

## 6. Frontend

**Stack:** React 19, TypeScript (strict), Vite, Tailwind, TanStack Query, React Hook Form with Zod, and Vitest with Testing Library and MSW.

**Session:**
- The access token lives only in memory; the HttpOnly refresh cookie restores the session on reload.
- Concurrent 401s share one refresh, because the server rotates the cookie and treats reuse as theft.

**Idempotency in the UI (ADR-010):**
- Each payment *attempt* gets one key.
- Retries and the 401-refresh replay resend the same key.
- The key is replaced when the user edits a field or the payment succeeds.

**Money:** amounts stay decimal strings end to end. Formatting and limit checks use `BigInt` paise, so a JavaScript `number` never holds money.

**Screens:**
- **Overview:** a balance card, a daily-limit ring, a 30-day balance chart, quick-send contacts, and recent activity.
- **Send money in three steps:** verify the recipient, review (with the Idempotency-Key shown), done.
- **Activity:** filters, search, keyset paging, and CSV export. The export neutralises spreadsheet-formula injection.
- **Receipt:** the double-entry lines with a Balanced badge.

**Engineering console (ADR-011):**
- **API inspector:** each call with latency, request id, Idempotency-Key and replay flag, plus p50/p95. It stores metadata only.
- **Resilience Lab:** live experiments against the real API.
  - **Double-click storm:** 1 executed, 4 replayed.
  - **Overdraw race:** 1 success, 5 `INSUFFICIENT_FUNDS`.
  - **Lost response:** the retry is replayed.
- **Command palette:** Ctrl/⌘+K.

**Serving:**
- Nginx serves the static build and proxies `/api` on the same origin, so there is no CORS and the SameSite=Strict cookie works.
- Headers: a strict CSP with no `unsafe-eval` (Zod runs in `jitless` mode), `X-Frame-Options: DENY`, and no-cache on `index.html`.
- The landing, receipt and engineering pages are lazy-loaded.

**API types** are written by hand. Generating them from the OpenAPI spec is a possible improvement.

## 7. Non-functional

**Money:**
- `BigDecimal` with `RoundingMode.HALF_EVEN`; `numeric(19,4)` in the database; scale 2 at the API; never `double` (an ArchUnit rule checks this).
- INR only: ADR-005.

**Security:**
- Deny by default, and owner-only access to wallet data.
- No PII or tokens in logs.
- Login lockout: 5 failures lock the account for 15 minutes.
- Refresh tokens stored only as SHA-256 hashes.
- gitleaks runs in CI.

**Testing:**
- Unit tests, Testcontainers integration tests (PostgreSQL 16, Kafka) and concurrency tests.
- ArchUnit, an 80% coverage gate, and Gatling load tests.

**Operations:**
- Prometheus metrics and a Grafana dashboard.
- 6 alert rules, each with a [runbook](RUNBOOK.md) section.

**Known limits** (also in DESIGN.md §8):
- Single instance: SSE streams and limits live in one process. Scaling out needs Redis.
- No scheduled purge of expired idempotency records.
- Top-ups come from a simulated payment source.
