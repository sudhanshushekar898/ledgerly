package dev.ledgerly.ledger.domain;

import dev.ledgerly.ledger.AccountStatus;
import dev.ledgerly.ledger.AccountType;
import dev.ledgerly.ledger.Direction;
import dev.ledgerly.shared.error.DomainException;
import dev.ledgerly.shared.error.ErrorCode;
import dev.ledgerly.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * An account with a cached balance. The balance is a projection of its journal entries, changed only by
 * {@link #apply} while the row is locked, in the same transaction that inserts the entry.
 */
@Entity
@Table(name = "accounts")
public class Account extends BaseEntity {

    public static final String CURRENCY = "INR";

    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AccountType type;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(nullable = false)
    private long lastEntrySeq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountStatus status = AccountStatus.ACTIVE;

    /** 0 for every wallet; system accounts can be split into shards to spread lock contention (ADR-014). */
    @Column(nullable = false)
    private short shard;

    protected Account() {}

    private Account(UUID ownerUserId, AccountType type) {
        this.ownerUserId = ownerUserId;
        this.type = type;
        this.currency = CURRENCY;
    }

    public static Account userWallet(UUID ownerUserId) {
        return new Account(ownerUserId, AccountType.USER_WALLET);
    }

    /**
     * Applies one leg and returns this account's next entry sequence number (1, 2, 3...). Rejects the change before
     * mutating anything if it would overdraw a user wallet.
     */
    public long apply(Direction direction, BigDecimal amount) {
        BigDecimal next = direction == Direction.CREDIT ? balance.add(amount) : balance.subtract(amount);
        if (type == AccountType.USER_WALLET && next.signum() < 0) {
            throw new DomainException(ErrorCode.INSUFFICIENT_FUNDS, "Insufficient balance for this payment.");
        }
        balance = next;
        return ++lastEntrySeq;
    }

    public boolean isFrozen() {
        return status == AccountStatus.FROZEN;
    }

    public void changeStatus(AccountStatus status) {
        this.status = status;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public long getLastEntrySeq() {
        return lastEntrySeq;
    }
}
