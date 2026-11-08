package dev.ledgerly.ledger;

public enum AccountType {
    /** A customer's wallet. Can never go below zero. */
    USER_WALLET,
    /** Where top-up money enters from the (simulated) outside world. Goes negative by design. */
    SYSTEM_TOPUP_SOURCE
}
