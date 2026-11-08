-- ledger module: double-entry bookkeeping (ADR-002). The only module that changes balances.
--
-- Sign convention, the same for every account: balance = sum(credits) - sum(debits).
-- Money is never created: a top-up debits SYSTEM_TOPUP_SOURCE (which goes negative by exactly the money that
-- entered from outside) and credits a user wallet, so the sum of ALL balances is always zero.

CREATE TABLE accounts (
    id             uuid PRIMARY KEY,
    owner_user_id  uuid,                         -- identity user id; no FK so modules stay separable (ADR-001)
    type           varchar(30)    NOT NULL CHECK (type IN ('USER_WALLET', 'SYSTEM_TOPUP_SOURCE')),
    currency       varchar(3)     NOT NULL CHECK (currency = 'INR'),          -- ADR-005
    balance        numeric(19, 4) NOT NULL DEFAULT 0,
    last_entry_seq bigint         NOT NULL DEFAULT 0 CHECK (last_entry_seq >= 0),
    version        bigint         NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    -- Backstop: even if application code is wrong, a user wallet can never be overdrawn.
    CONSTRAINT ck_accounts_no_overdraft CHECK (type <> 'USER_WALLET' OR balance >= 0),
    CONSTRAINT ck_accounts_owner CHECK ((type = 'USER_WALLET') = (owner_user_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_accounts_user_wallet ON accounts (owner_user_id) WHERE type = 'USER_WALLET';
CREATE UNIQUE INDEX uq_accounts_system_type ON accounts (type) WHERE owner_user_id IS NULL;

CREATE TABLE journal_transactions (
    id           uuid PRIMARY KEY,
    kind         varchar(20) NOT NULL CHECK (kind IN ('TOPUP', 'TRANSFER', 'REVERSAL')),
    initiated_by uuid        NOT NULL,
    created_at   timestamptz NOT NULL
);

CREATE TABLE journal_entries (
    id             uuid PRIMARY KEY,
    transaction_id uuid           NOT NULL REFERENCES journal_transactions (id),
    account_id     uuid           NOT NULL REFERENCES accounts (id),
    direction      varchar(6)     NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount         numeric(19, 4) NOT NULL CHECK (amount > 0),
    balance_after  numeric(19, 4) NOT NULL,
    account_seq    bigint         NOT NULL CHECK (account_seq > 0), -- 1, 2, 3... per account, written under the row lock
    created_at     timestamptz    NOT NULL,
    CONSTRAINT uq_journal_entries_account_seq UNIQUE (account_id, account_seq)
);
CREATE INDEX ix_journal_entries_transaction ON journal_entries (transaction_id);

-- The journal is append-only: corrections are new REVERSAL transactions (ADR-008), never edits.
CREATE FUNCTION ledger_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger journal is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entries_append_only BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER trg_journal_transactions_append_only BEFORE UPDATE OR DELETE ON journal_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

-- Every transaction must balance (debits = credits). Checked at COMMIT (deferred), once all legs are inserted.
CREATE FUNCTION ledger_check_transaction_balanced() RETURNS trigger AS $$
DECLARE
    net numeric;
BEGIN
    SELECT coalesce(sum(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0) INTO net
    FROM journal_entries WHERE transaction_id = NEW.transaction_id;
    IF net <> 0 THEN
        RAISE EXCEPTION 'journal transaction % does not balance (net %)', NEW.transaction_id, net
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_entries_balanced AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_transaction_balanced();

-- The one system account M2 needs. Its balance is minus the total money ever topped up.
INSERT INTO accounts (id, owner_user_id, type, currency)
VALUES ('00000000-0000-7000-8000-000000000001', NULL, 'SYSTEM_TOPUP_SOURCE', 'INR');
