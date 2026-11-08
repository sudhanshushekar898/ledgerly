-- identity module. Emails are stored lower-cased by the application; uniqueness is case-insensitive (citext).
CREATE TABLE users (
    id                 uuid PRIMARY KEY,
    email              citext       NOT NULL,
    password_hash      varchar(100) NOT NULL,
    full_name          varchar(100) NOT NULL,
    role               varchar(20)  NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    enabled            boolean      NOT NULL DEFAULT true,
    failed_login_count integer      NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until       timestamptz,
    last_login_at      timestamptz,
    version            bigint       NOT NULL DEFAULT 0,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  varchar(64)  NOT NULL,           -- SHA-256 hex; the raw token is never stored
    family_id   uuid         NOT NULL,           -- all rotations of one login share a family (reuse detection)
    expires_at  timestamptz  NOT NULL,
    revoked_at  timestamptz,
    replaced_by uuid,
    user_agent  varchar(255),
    ip          varchar(45),
    version     bigint       NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
