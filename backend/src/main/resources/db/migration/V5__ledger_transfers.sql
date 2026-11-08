-- M3: wallet freeze, reversals (ADR-008), transfer notes, and the index the daily-limit query uses.

ALTER TABLE accounts
    ADD COLUMN status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN'));

-- Corrections are new transactions that point at the one they undo. UNIQUE = each transaction is reversed at most
-- once, even if two operators click "reverse" at the same moment.
ALTER TABLE journal_transactions
    ADD COLUMN memo varchar(140),
    ADD COLUMN reverses_transaction_id uuid REFERENCES journal_transactions (id),
    ADD CONSTRAINT uq_journal_transactions_reverses UNIQUE (reverses_transaction_id),
    ADD CONSTRAINT ck_journal_transactions_reversal CHECK ((kind = 'REVERSAL') = (reverses_transaction_id IS NOT NULL));

-- Daily limit: "sum of my outgoing transfers since midnight", always for one account.
CREATE INDEX ix_journal_entries_account_created ON journal_entries (account_id, created_at);

-- Who froze or unfroze a wallet, when and why. Append-only, like the journal.
CREATE TABLE account_status_changes (
    id         uuid PRIMARY KEY,
    account_id uuid         NOT NULL REFERENCES accounts (id),
    status     varchar(10)  NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN')),
    reason     varchar(140) NOT NULL,
    changed_by uuid         NOT NULL,
    created_at timestamptz  NOT NULL
);
CREATE INDEX ix_account_status_changes_account ON account_status_changes (account_id, created_at);
CREATE TRIGGER trg_account_status_changes_append_only BEFORE UPDATE OR DELETE ON account_status_changes
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
