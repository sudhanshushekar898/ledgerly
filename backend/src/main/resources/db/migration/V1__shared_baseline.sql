-- Baseline: database-wide settings shared by every module.
-- Module tables arrive with their milestones (identity in M1, ledger in M2, ...).

-- Case-insensitive text for emails (identity, M1).
CREATE EXTENSION IF NOT EXISTS citext;
