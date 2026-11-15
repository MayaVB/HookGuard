# HookGuard

A reliable webhook delivery service. Applications submit events to HookGuard; HookGuard stores
them durably, delivers them to registered HTTP endpoints in the background, records every attempt,
and makes failures visible in a dashboard.

> **Status: Phase 1 complete (working vertical slice + live demo page); Phase 2 not started.** One
> event targets one endpoint, delivery is attempted once, and the result is recorded. Retries,
> recovery of abandoned work and reliable publication are Phase 2. The
> [known gaps](#known-gaps-in-phase-1) below are real and have been reproduced. See
> [Roadmap](#roadmap) for what comes next and where it plugs into the code.

## Why this exists

When an application notifies another system over HTTP, the receiver may be down, slow, return
errors, or receive the same notification twice. Doing this inline in a request handler couples your
application's availability to the receiver's. HookGuard takes the event, makes it durable, and owns
the delivery: timeouts, failure recording, concurrency between workers and duplicate suppression.

## Architecture

```
                 POST /api/events
  Producer ─────────────────────────►┌──────────────┐  1 tx: INSERT event + delivery (PENDING)
                 202 Accepted        │  API process │──────────────────────────┐
                                     └──────┬───────┘                          ▼
                                            │ 2. after commit:          ┌────────────┐
                                            │    publish delivery ID    │ PostgreSQL │ source of truth
                                            ▼                           └────────────┘
                                     ┌──────────────┐                          ▲
                                     │   RabbitMQ   │ quorum queue + DLQ       │ 3. atomic claim
                                     └──────┬───────┘                          │ 5. record attempt +
                                            │ delivery ID (a hint only)        │    fenced transition
                                            ▼                                  │
                                     ┌──────────────┐──────────────────────────┘
                                     │    Worker    │ 4. HTTP POST (no DB transaction open)
                                     │ (N processes)│───────────────────────────► Webhook receiver
                                     └──────────────┘   X-HookGuard-Delivery-Id
  Dashboard (React) ──► GET /api/deliveries, /api/stats ──► API
```

- **One codebase, two processes.** `hookguard-service` runs as the API (`SPRING_PROFILES_ACTIVE=api`)
  or as a worker (`=worker`). They share the schema and domain model; separate processes let the
  worker crash, restart or scale without affecting event intake.
- **PostgreSQL is the source of truth; RabbitMQ carries only a delivery ID.** The worker always
  reloads state from the database, so a duplicated or stale message cannot cause a wrong action.
- **Atomic claim.** A worker moves a delivery `PENDING → IN_PROGRESS` with a single conditional
  `UPDATE ... WHERE status = 'PENDING'`. Under PostgreSQL's READ COMMITTED isolation, concurrent
  claimers serialize on the row lock and the losers re-check the `WHERE` clause and update nothing.
  Exactly one worker wins; the others drop the message.
- **Fenced completion.** Each claim gets a random token. The final state update requires that token,
  so a worker whose lease expired cannot overwrite the work of a worker that took over.
- **No transaction during HTTP.** Claim (short transaction) → HTTP call (no transaction) → record
  (short transaction). A slow receiver cannot hold database connections or row locks.
- **Ack after commit.** RabbitMQ messages are acknowledged only after the attempt is committed; an
  exception dead-letters the message instead of redelivering it in a tight loop.

## Delivery guarantees

HookGuard targets **at-least-once** delivery, never exactly-once. A receiver can get the same
delivery more than once: if a worker crashes after the receiver accepted the request but before
HookGuard recorded it, HookGuard cannot know the request succeeded. Every request therefore carries
a stable `X-HookGuard-Delivery-Id` header that receivers should use as an idempotency key.

What Phase 1 guarantees:

- A `202 Accepted` response means the event and its delivery are committed to PostgreSQL.
- Concurrent workers and duplicate messages do not cause concurrent or repeated attempts of the same
  delivery (atomic claim; verified with 4 consumers × 10 duplicate messages and 3 worker containers).
- Every attempt is recorded with outcome, HTTP status, a bounded response body, duration and worker.
- The payload is delivered as submitted (key order and decimal precision preserved).

### Known gaps in Phase 1

Both were reproduced against the Docker Compose stack, and both are what Phase 2 fixes.

| Scenario | What happens now | Phase 2 fix |
|---|---|---|
| RabbitMQ is unreachable when an event is submitted (or the API crashes between commit and publish) | Client still gets `202` (after the 5 s connection timeout); the error is logged; the delivery stays `PENDING` forever | A relay that re-publishes due `PENDING` deliveries from the database |
| Worker dies mid-attempt | Delivery stays `IN_PROGRESS` with an expired lease; the redelivered message is correctly refused by the claim | Recovery of expired claims |
| Receiver fails transiently | Delivery is marked `FAILED` after one attempt | Exponential backoff, max attempts |

## Running it

Prerequisites: Docker with Compose v2. (For development outside containers: JDK 21 and Node 22+.)

```bash
cp .env.example .env          # then set real passwords in .env
docker compose up --build -d --wait
open http://localhost:3000/live   # the live demo page (see below)
./scripts/demo.sh             # or the same story from the command line (needs curl + jq)
```

| Service | URL |
|---|---|
| Dashboard | http://localhost:3000 |
| API | http://localhost:8080 |
| Demo receiver | http://localhost:9090 |
| RabbitMQ management | http://localhost:15673 |
| PostgreSQL | localhost:55432 (`HOOKGUARD_DB_HOST_PORT` to change) |

All ports bind to `127.0.0.1` only. Scale workers with `docker compose up -d --scale worker=3`.
Stop with `docker compose down` (add `-v` to delete the data volumes).

### Live demo page

`http://localhost:3000/live` shows the system working, using only live state (PostgreSQL, RabbitMQ
and the demo receiver, polled every second). Nothing is simulated in the browser.

- **Pipeline:** Producer → API → RabbitMQ → Workers → Receiver, with PostgreSQL underneath. Each node
  shows live numbers (ready messages, consumers, dead letters, per-worker in-flight work, expired leases,
  receiver outage); dots travel along the arrows when real state changes.
- **Board:** deliveries move between Pending, In progress (one lane per worker), Delivered and Failed.
  A card whose worker died turns amber ("lease expired").
- **Scenarios:** healthy, 500, timeout, flaky, burst of 30, duplicate storm (1 event + 9 injected
  copies of its message), and a receiver outage switch. Each reports facts read back afterwards,
  e.g. "Messages published: 10 · HTTP requests received: 1".
- **Break it from a terminal:** recipes for killing a worker mid-delivery, stopping the broker and
  scaling workers, which show the Phase 1 gaps on screen.

The duplicate injection uses a demo-only endpoint that exists only with `HOOKGUARD_DEMO_ENABLED=true`
(set in `docker-compose.yml`, off by default).

### Developing outside containers

```bash
docker compose up -d postgres rabbitmq demo-receiver
set -a; source .env; set +a
HOOKGUARD_ALLOWED_HOSTS=localhost:9090 ./mvnw -pl hookguard-service spring-boot:run   # API + worker in one process
cd frontend && npm install && npm run dev                                           # http://localhost:5173
```

Without a profile the service runs both roles in one process, which is convenient for development.

## API

Errors use RFC 9457 problem details (`application/problem+json`); validation errors include an
`errors` array of `{field, message}`.

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/endpoints` | Register a target `{url, description?}` → `201` |
| `GET` | `/api/endpoints`, `/api/endpoints/{id}` | List / get endpoints |
| `POST` | `/api/events` | Submit `{endpointId, eventType, payload}` → `202` with `deliveryId` |
| `GET` | `/api/deliveries?status=&page=&size=` | Paged deliveries, newest first (`size` ≤ 100) |
| `GET` | `/api/deliveries/{id}` | Delivery with event, endpoint and full attempt history |
| `GET` | `/api/stats` | Counts per status |
| `GET` | `/api/overview` | Live snapshot: counts, queue depth/consumers (or broker unreachable), worker activity and expired leases |
| `GET` / `POST` | `/api/demo`, `/api/demo/deliveries/{id}/duplicates` | Demo only (`HOOKGUARD_DEMO_ENABLED=true`): receiver URL; publish extra copies of a delivery's message |

```bash
curl -X POST localhost:8080/api/endpoints -H 'Content-Type: application/json' \
  -d '{"url":"http://demo-receiver:9090/hooks/ok","description":"Orders"}'

curl -X POST localhost:8080/api/events -H 'Content-Type: application/json' \
  -d '{"endpointId":"<id>","eventType":"order.created","payload":{"orderId":42}}'
```

Validation rules: `payload` must be a JSON object (max 256 KiB, `HOOKGUARD_MAX_PAYLOAD_BYTES`);
`eventType` is 1–100 characters of letters, digits, `.`, `_`, `-`.

### Target URL restrictions (SSRF protection)

HookGuard sends requests to user-supplied URLs, so it only calls hosts on an explicit allowlist
(`HOOKGUARD_ALLOWED_HOSTS`, comma-separated `host` or `host:port`; empty = reject all). Only `http`
and `https`, no credentials or fragments in the URL, no wildcard or suffix matching, and redirects
are never followed. The policy is checked at registration and again before every attempt. In the
Compose stack the only allowed target is `demo-receiver:9090`.

### Outgoing webhook request

`POST` to the endpoint URL with the event payload as the JSON body and these headers:

| Header | Meaning |
|---|---|
| `X-HookGuard-Delivery-Id` | Stable for the delivery across attempts. Use it to deduplicate. |
| `X-HookGuard-Event-Id` | The submitted event |
| `X-HookGuard-Event-Type` | e.g. `order.created` |
| `X-HookGuard-Attempt` | 1-based attempt number (informational) |

A `2xx` response means delivered. Anything else, including redirects, is a failure. Timeouts:
connect 2 s, whole exchange (including reading the body) 10 s by default; only the first 4 KiB of
the response body are kept.

## Demo receiver (failure simulator)

| Request | Behaviour |
|---|---|
| `POST /hooks/ok` | 200 |
| `POST /hooks/fail` | 500 |
| `POST /hooks/status/{code}` | Any status 200–599 |
| `POST /hooks/slow?delayMs=8000` | Waits, then 200 (exceeds the 5 s timeout used in Compose) |
| `POST /hooks/flaky?failTimes=2` | 503 for the first N requests of each delivery, then 200 |
| `PUT /control/outage` `{"enabled":true}` | Every `/hooks` request returns 503 until disabled |
| `GET /received` | Recent requests, newest first; repeated successful deliveries are flagged `duplicate` |

## Tests

`./mvnw verify` runs everything (Docker required for the integration tests).

- **Unit tests** (`*Test`): URL allowlist policy (28 cases), HTTP sender against a real local HTTP
  server (success, 5xx with body capture, redirect not followed, slow headers, stalled body after
  fast headers, 1 MB body truncated to the limit, connection refused), configuration validation,
  demo receiver behaviours.
- **Integration tests** (`*IT`, Testcontainers PostgreSQL 17 + RabbitMQ 4.1):
  - 25 rounds × 16 threads claiming the same delivery: exactly one winner each time.
  - A deterministic test that holds claimer A's transaction open and shows claimer B blocks on the
    row lock, then loses once A commits.
  - Fencing: a stale claim cannot overwrite a newer one; completion applies once.
  - Database invariants: `IN_PROGRESS` without a claim and duplicate attempt numbers are rejected.
  - End to end through REST, RabbitMQ and a real HTTP receiver: delivered, 500, timeout, connection
    refused, payload fidelity, 10 duplicate messages → 1 HTTP request, unknown ID dropped, malformed
    message dead-lettered.
  - API validation and problem-detail responses.
  - Overview snapshot, expired-lease detection (list, detail, per worker) and the demo duplicate
    endpoint (10 messages → 1 request).

### Verification status

The checks below were run on 2026-11-11, in the session that built Phase 1.

| Area | How it was verified |
|---|---|
| Unit + integration test suites | `./mvnw verify` on Linux, Docker 29, JDK 21 |
| Full Compose stack (6 services, health checks) | `docker compose up --build --wait` |
| Demo scenarios (ok / 500 / timeout / flaky / outage / SSRF rejection) | `scripts/demo.sh` against Compose |
| 3 worker containers, 60 concurrent events | 60 receiver requests, 60 unique delivery IDs, 0 duplicates |
| Worker SIGKILL mid-attempt; broker stopped during submit | Reproduced the documented gaps |
| Dashboard | Production build compiles (strict TypeScript); served via nginx; list and detail pages rendered with live data in headless Chrome |
| Live demo page | Driven in headless Chrome over the DevTools protocol: real buttons clicked with 3 workers running; duplicate storm 10 messages → 1 request, burst 30/30 split 9/12/9 with 0 duplicates, timeout at 5.04 s; no console errors |
| Not verified yet | Dark mode and narrow-screen layout in a real browser; CI pipeline (Phase 4) |

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `HOOKGUARD_DB_URL` / `_USER` / `_PASSWORD` | `localhost:55432`, `hookguard`, *(required)* | PostgreSQL |
| `HOOKGUARD_RABBIT_HOST` / `_PORT` / `_USER` / `_PASSWORD` | `localhost`, `5673`, `hookguard`, *(required)* | RabbitMQ |
| `HOOKGUARD_ALLOWED_HOSTS` | *(empty: reject all)* | Webhook target allowlist |
| `HOOKGUARD_CONNECT_TIMEOUT` / `HOOKGUARD_REQUEST_TIMEOUT` | `2s` / `10s` | Outbound HTTP limits |
| `HOOKGUARD_LEASE_DURATION` | `60s` | Claim lease; must be ≥ 2 × (connect + request timeout) or startup fails |
| `HOOKGUARD_WORKER_CONCURRENCY` | `4` | Consumers per worker process |
| `HOOKGUARD_WORKER_ID` | hostname-pid | Shown on claims and attempts |
| `HOOKGUARD_MAX_PAYLOAD_BYTES` | `262144` | Event payload limit |

Passwords have no defaults: the service refuses to start without them. `.env` is git-ignored.

## Project layout

```
hookguard-service/   Spring Boot 4 (Java 21): REST API + delivery worker
  api/               error handling (problem details)
  endpoint/, event/  registration and event intake
  delivery/          entities, read API, worker (claim/send/record), HTTP sender
  messaging/         RabbitMQ topology and after-commit publisher
  security/          target URL allowlist
  resources/db/migration/   Flyway migrations
demo-receiver/       Spring Boot failure simulator (shares no code with the service)
frontend/            React 19 + TypeScript + Vite dashboard (nginx in Docker)
scripts/demo.sh      reproducible demo against the Compose stack
```

## Roadmap

The project is built in phases, each finished (tested and verified against the Compose stack)
before the next starts. Reliability comes before features; no extra infrastructure (Redis,
Kubernetes, additional services) without a concrete reason.

| Phase | Scope | State |
|---|---|---|
| 1. Vertical slice | Intake, durable storage, one attempt per delivery, atomic claim, fencing, attempt history, SSRF allowlist, dashboard, live demo page | **Done** |
| 2. Reliability | Retries with backoff, recovery of expired claims, re-publication of stranded `PENDING` work | Next |
| 3. Testing and observability | Metrics (Actuator is already on the classpath, health only), structured logs, failure-injection and load tests | Planned |
| 4. Polish | CI (GitHub Actions: `./mvnw verify` + frontend build), request signing (HMAC), docs | Planned |

### Phase 2 plan

The Phase 1 code has hooks for these features. The design below is a starting point; the
open questions are still to be evaluated and decided.

1. **Retries with exponential backoff.** On a retryable failure, move `IN_PROGRESS → PENDING` with
   `next_attempt_at = now() + backoff(attempt_count)` instead of `FAILED`; `FAILED` only after the
   maximum number of attempts or on a non-retryable outcome. Use the same fenced update (claim token
   in the `WHERE` clause) as `finish()`.
   - Code: `DeliveryStateStore.finish()` / `markFailed()`, `DeliveryProcessor.process()`;
     `HookGuardProperties` for max attempts, base delay, cap and jitter.
   - Already in place: `deliveries.next_attempt_at` (DB clock), the `(status, next_attempt_at)`
     index, and `tryClaim()` already requires `next_attempt_at <= now()`.
   - To decide: which outcomes are retryable (5xx, 408, 429, timeout and connection errors are the
     usual candidates; other 4xx and `REJECTED` probably not).
2. **Recovery of expired claims.** A periodic job moves `IN_PROGRESS` rows whose
   `claim_expires_at < now()` back to `PENDING` (or to `FAILED` once attempts are exhausted). This is
   safe because of fencing: a slow worker that comes back later fails its `claim_token` check, and
   its attempt is recorded as history only (`ProcessingOutcome.CLAIM_LOST`).
3. **Re-publication of stranded work (dual-write gap).** A relay periodically selects due `PENDING`
   deliveries and publishes their IDs to RabbitMQ (`DeliveryPublisher` documents the gap). This also
   delivers scheduled retries, so one mechanism covers 1 and 3. To evaluate first: whether the
   `deliveries` table can serve as the durable queue (poll with `FOR UPDATE SKIP LOCKED`, plus a
   grace period or a `last_published_at` column so work already in the queue isn't re-published on
   every tick) before adding a separate outbox table. Duplicate publishes are harmless (the atomic
   claim drops them) but add noise.
4. **Tests:** an integration test for each feature: a retry schedule ending in `DELIVERED` and in
   `FAILED`, recovery after a lease is forced to expire, relay re-publication after a failed publish,
   and a stale worker losing to a reclaimer. Then re-run the "break it" recipes against Compose
   (SIGKILL a worker, stop the broker) and confirm the [known gaps](#known-gaps-in-phase-1) are closed.
5. **Docs and UI:** update the known-gaps table, delivery guarantees and configuration table; show
   attempt count and next attempt time on the dashboard and the live page.

### Development notes

- Long builds are memory-heavy: `./mvnw verify` starts Testcontainers (PostgreSQL + RabbitMQ).
  Stop the Compose stack (`docker compose stop`) before running it on a small machine.
- Claims in this README are backed by tests or reproduced runs. When behaviour changes, update the
  verification table with how it was checked, and list what wasn't checked.

## Limitations (beyond Phase 2 scope)

- No authentication; local demo only.
- One endpoint per event (no subscriptions or fan-out yet).
- The allowlist trusts DNS for allowlisted names; a multi-tenant deployment would need IP-range
  checks after resolution.
- The payload size check runs after the request body is parsed.
- No request signing (HMAC) yet.
