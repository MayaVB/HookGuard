# HookGuard: notes for Claude Code sessions

Read `README.md` first. It covers the architecture, delivery guarantees, verification status and
the **Roadmap** (current state and the Phase 2 plan, with the code each step touches).

## Current state

Phase 1 is complete (vertical slice + live demo page at `/live`). Phase 2 (retries, recovery of
expired claims, re-publication of stranded `PENDING` work) is next and has not been started.

## How work is done here

- Work one phase at a time. Inspect existing code before changing it and preserve what works.
- Verify before claiming: run the relevant tests, and for behaviour changes, run the Compose
  stack. State plainly what was and wasn't verified, and keep the README verification table and
  known-gaps table accurate.
- Prefer reliability over features. Don't add infrastructure (Redis, Kubernetes, new services)
  without a concrete justification.
- Don't commit or push unless asked.

## Design invariants (don't break these without a deliberate decision)

- PostgreSQL is the source of truth; RabbitMQ messages carry only a delivery ID, and the worker
  always reloads state from the database.
- Every delivery state transition is a single conditional `UPDATE` in `DeliveryStateStore`
  (compare-and-set on the expected status), never read-modify-write through JPA.
- Completion is fenced by `claim_token`; a worker that lost its claim records history only.
- No database transaction is open during the outbound HTTP call (claim → send → record).
- Messages are acked only after the attempt is committed.
- Scheduling uses the database clock (`now()`), never the application clock.
- `events.payload` is `json` (not `jsonb`) so the payload is delivered byte-for-byte as submitted.
- The target URL allowlist is checked at registration and again before every attempt; redirects
  are never followed.
- Schema changes go in new Flyway migrations (`V2__...`); never edit `V1__initial_schema.sql`.

## Commands

```bash
./mvnw verify                               # unit + integration tests (Testcontainers; needs Docker)
./mvnw -pl hookguard-service test           # unit tests only, fast
cd frontend && npm ci && npm run build      # strict TypeScript + production build
cp .env.example .env && docker compose up --build -d --wait   # full stack
./scripts/demo.sh                           # demo scenarios against the stack (curl + jq)
```

`./mvnw verify` is memory-heavy. Stop the Compose stack first (`docker compose stop`) on small
machines and run long builds in the background.

Host ports (all bound to 127.0.0.1): API 8080, dashboard 3000, demo receiver 9090,
PostgreSQL 55432, AMQP 5673, RabbitMQ UI 15673.
