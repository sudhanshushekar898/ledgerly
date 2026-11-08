-- wallet module: Idempotency-Key records (ADR-004).
-- A record is claimed and completed inside the SAME database transaction as the money movement, so a committed
-- record always has its response, and a rolled-back request leaves no record (a retry simply runs again).
-- Keys are scoped per user: one user can never replay or block another user's request.
CREATE TABLE idempotency_records (
    user_id         uuid        NOT NULL,
    idempotency_key varchar(64) NOT NULL,
    request_hash    varchar(64) NOT NULL,   -- SHA-256 of the operation and its normalised body
    response_body   jsonb,
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
);
CREATE INDEX ix_idempotency_records_expires ON idempotency_records (expires_at);
