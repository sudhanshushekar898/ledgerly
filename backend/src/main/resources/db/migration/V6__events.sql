-- M5 (ADR-006): transactional outbox, consumer inbox, and in-app notifications.

-- outbox module. A row is written in the SAME transaction as the ledger posting: if the posting rolls back, the
-- event never existed; if it commits, the event will be published (at least once) even if Kafka is down now.
CREATE TABLE outbox_events (
    id           uuid PRIMARY KEY,               -- also the event id consumers deduplicate on
    topic        varchar(100) NOT NULL,
    event_key    varchar(100) NOT NULL,           -- Kafka key: same key, same partition, ordered
    event_type   varchar(50)  NOT NULL,
    payload      jsonb        NOT NULL,
    created_at   timestamptz  NOT NULL,
    published_at timestamptz,
    attempts     integer      NOT NULL DEFAULT 0,
    last_error   varchar(500)
);
-- The relay only ever scans unpublished rows, oldest first.
CREATE INDEX ix_outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

-- notification module. Kafka delivers at least once; this table makes processing exactly-once in effect.
CREATE TABLE processed_events (
    consumer     varchar(50) NOT NULL,
    event_id     uuid        NOT NULL,
    processed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

CREATE TABLE notifications (
    id                uuid PRIMARY KEY,
    user_id           uuid           NOT NULL,      -- identity user id; no FK, modules stay separable
    kind              varchar(30)    NOT NULL CHECK (kind IN ('MONEY_RECEIVED', 'MONEY_ADDED', 'PAYMENT_RETURNED', 'PAYMENT_REVERSED')),
    amount            numeric(19, 4) NOT NULL CHECK (amount > 0),
    counterparty_name varchar(100),
    memo              varchar(140),
    transaction_id    uuid           NOT NULL,
    created_at        timestamptz    NOT NULL,
    read_at           timestamptz
);
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);
