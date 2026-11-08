# Ledgerly: event-driven digital wallet

[![CI](https://github.com/sudhanshushekar898/ledgerly/actions/workflows/ci.yml/badge.svg)](https://github.com/sudhanshushekar898/ledgerly/actions/workflows/ci.yml)

A digital wallet that moves money correctly under concurrency and failure. It is a Spring Boot modular monolith with a React frontend.

- **Double-entry ledger:** all balances always sum to zero. The rule is enforced by database constraint triggers and checked continuously by a reconciliation alarm.
- **Safe retries:** idempotent top-ups and transfers. A retried request never moves money twice.
- **No overdrafts or deadlocks under concurrency:** ordered row locks, with daily limits and reversals checked under the lock.
- **Reliable events:** a transactional outbox relays to Kafka, consumed idempotently, with retries, a dead-letter topic, and live "money received" notifications over SSE.
- **Observable:** Prometheus metrics, a Grafana dashboard, 6 alerts and a runbook.

**Tech:** Java 21, Spring Boot 3.5, Spring Security (JWT), PostgreSQL 16, Flyway, Apache Kafka, React 19, TypeScript, Testcontainers, ArchUnit, Gatling, Docker Compose, GitHub Actions.

## Quick start

Requirements: Docker.

```bash
docker compose --profile full up --build
```

Open http://localhost:8091 and sign in as the demo customer `asha@demo.example.com` / `Demo-Wallet-Pass-2026`.
- The account comes with 60 days of synthetic history, all posted through the real ledger.
- Her contacts `ravi@`, `meera@`, `kabir@` and `priya@demo.example.com` use the same password.
- Sign in as Ravi in a second (private) window, pay him from Asha, and watch the notification arrive live.

**What to try:**
- **Resilience Lab:** live concurrency and idempotency experiments against the API. For example, 5 identical payments in flight at once give 1 executed and 4 replayed.
- **Any receipt:** the double-entry lines, debits = credits.
- **API inspector:** every request with its latency, request id, Idempotency-Key and replay flag.
- **Send money:** verify the recipient, review with the Idempotency-Key shown, then pay.
- **Ctrl/⌘+K:** the command palette.

**Also in the stack:**
- **API:** http://localhost:8090.
- **Grafana:** http://localhost:3001, dashboard "Ledgerly · Overview".
- **Prometheus:** http://localhost:9090.
- **Operator account** for `/api/v1/admin`: `operator@example.com` / `Local-Operator-Pass-2026`.

All of these credentials are synthetic and exist only in the local stack.

## Performance (measured)

Measured on a laptop with 12 cores, 50 concurrent users for 60 s, and 0 failed requests. The ledger still summed to 0 afterwards. Method and caveats: [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

| | Throughput | p50 | p99 |
|---|---|---|---|
| Transfers | about 500–600 /s | 80 ms | 249 ms |
| Top-ups, before sharding a hot account row | 136 /s | 349 ms | 657 ms |
| Top-ups, after sharding it 8 ways (ADR-014) | 368–447 /s | 97–120 ms | 325–411 ms |
| Outbox → Kafka relay, before → after pipelining (ADR-015) | 210 → ≥ 4,190 events/s | | |

With Kafka stopped, payments kept succeeding. The queued events were delivered within seconds of the broker coming back.

## Docs

- [docs/DESIGN.md](docs/DESIGN.md): the design narrative (tenets, alternatives, failure modes, open questions).
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): modules, ledger model, events, API.
- [docs/adr](docs/adr/0001-adrs.md): 15 architecture decision records.
- [docs/PERFORMANCE.md](docs/PERFORMANCE.md): the load tests and the two bottlenecks they found.
- [docs/RUNBOOK.md](docs/RUNBOOK.md): what to do when each alert fires.

## Development

Backend on the host, against PostgreSQL in Docker (requires Java 21):

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Frontend dev server with hot reload (http://localhost:5174), against the compose backend:

```bash
cd frontend && npm ci && VITE_API_PROXY=http://localhost:8090 npm run dev
```

## Tests

```bash
cd backend && ./mvnw -B verify   # unit + Testcontainers (PostgreSQL, Kafka) + concurrency + ArchUnit + 80% coverage gate
cd frontend && npm run lint && npm run typecheck && npm test && npm run build
bash loadtest/run.sh transfer 50 60   # Gatling against the running stack: scenario (transfer|topup), users, seconds
```

All data in this repository is synthetic.
