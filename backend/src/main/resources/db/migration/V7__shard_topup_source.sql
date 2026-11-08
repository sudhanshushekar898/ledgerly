-- M7 / ADR-014: the single SYSTEM_TOPUP_SOURCE row was a measured hot spot. Every top-up locks it, so all top-ups
-- in the system queued behind one row lock. Split it into 8 shards; a user's top-ups always use the same shard.
-- Nothing else changes: the shards are ordinary accounts, so "all balances sum to 0" still holds.

ALTER TABLE accounts ADD COLUMN shard smallint NOT NULL DEFAULT 0 CHECK (shard >= 0);

DROP INDEX uq_accounts_system_type;
CREATE UNIQUE INDEX uq_accounts_system_type_shard ON accounts (type, shard) WHERE owner_user_id IS NULL;

-- Shard 0 is the existing account (it keeps its id, balance and history). Shards 1..7 get fixed ids ...0002..0008.
INSERT INTO accounts (id, owner_user_id, type, currency, shard)
SELECT ('00000000-0000-7000-8000-00000000000' || (s + 1))::uuid, NULL, 'SYSTEM_TOPUP_SOURCE', 'INR', s
FROM generate_series(1, 7) AS s;
