package dev.ledgerly.ledger;

/** FROZEN wallets cannot send, receive or top up. Reversals still apply, so an operator can claw back fraud. */
public enum AccountStatus {
    ACTIVE,
    FROZEN
}
