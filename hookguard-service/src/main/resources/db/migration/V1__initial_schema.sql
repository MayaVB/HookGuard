-- Registered webhook targets.
CREATE TABLE endpoints (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    url         varchar(2048) NOT NULL,
    description varchar(500),
    created_at  timestamptz   NOT NULL DEFAULT now()
);

-- Events accepted from producers. An event is immutable once stored.
-- Deliveries (not events) reference endpoints, so fan-out to several
-- endpoints can be added later without changing this table.
--
-- payload is `json`, not `jsonb`, on purpose: jsonb re-orders keys and normalises the text,
-- so receivers would get a different document than the producer submitted. `json` validates
-- and stores the text verbatim. We never query inside payloads, so jsonb's indexing buys nothing.
CREATE TABLE events (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type varchar(100) NOT NULL,
    payload    json         NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now()
);

-- One row per (event, endpoint): the durable state of a delivery.
-- All state transitions are conditional UPDATEs (compare-and-set), see DeliveryStateStore.
CREATE TABLE deliveries (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id         uuid        NOT NULL REFERENCES events (id),
    endpoint_id      uuid        NOT NULL REFERENCES endpoints (id),
    status           varchar(20) NOT NULL,
    attempt_count    integer     NOT NULL DEFAULT 0,
    -- Scheduling uses the database clock only (now()), never an application clock,
    -- so clock skew between API/worker hosts cannot make work early or late.
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    -- Claim (lease) held by the worker currently performing an attempt.
    claim_token      uuid,
    claimed_by       varchar(200),
    claim_expires_at timestamptz,
    last_http_status integer,
    last_error       varchar(1000),
    completed_at     timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT deliveries_status_valid
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DELIVERED', 'FAILED')),
    CONSTRAINT deliveries_attempt_count_non_negative
        CHECK (attempt_count >= 0),
    -- Invariant: a delivery holds a claim if and only if it is IN_PROGRESS.
    CONSTRAINT deliveries_claim_matches_status
        CHECK ((status = 'IN_PROGRESS') = (claim_token IS NOT NULL AND claimed_by IS NOT NULL AND claim_expires_at IS NOT NULL))
);

CREATE INDEX deliveries_status_next_attempt_idx ON deliveries (status, next_attempt_at);
CREATE INDEX deliveries_created_at_idx ON deliveries (created_at DESC);
CREATE INDEX deliveries_event_id_idx ON deliveries (event_id);

-- Append-only history: one row per HTTP attempt (or rejected attempt).
CREATE TABLE delivery_attempts (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id        uuid         NOT NULL REFERENCES deliveries (id),
    attempt_number     integer      NOT NULL,
    worker_id          varchar(200) NOT NULL,
    started_at         timestamptz  NOT NULL,
    finished_at        timestamptz  NOT NULL,
    duration_ms        bigint       NOT NULL,
    outcome            varchar(20)  NOT NULL,
    http_status        integer,
    response_body      text,
    response_truncated boolean      NOT NULL DEFAULT false,
    error_message      varchar(1000),

    CONSTRAINT delivery_attempts_outcome_valid
        CHECK (outcome IN ('SUCCESS', 'HTTP_ERROR', 'TIMEOUT', 'CONNECTION_ERROR', 'REJECTED')),
    -- Second line of defence against double processing: an attempt number can only be recorded once.
    CONSTRAINT delivery_attempts_unique_number UNIQUE (delivery_id, attempt_number)
);
