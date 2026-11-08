package dev.ledgerly.ledger.domain;

import dev.ledgerly.ledger.Direction;
import dev.ledgerly.shared.persistence.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/** One leg of a transaction. Append-only; the source of truth that cached balances are reconciled against. */
@Entity
@Immutable
@Table(name = "journal_entries")
public class JournalEntry implements Persistable<UUID> {

    @Id
    private UUID id = Ids.newId();

    @Column(nullable = false)
    private UUID transactionId;

    @Column(nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Direction direction;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(nullable = false)
    private long accountSeq;

    @Column(nullable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected JournalEntry() {}

    public JournalEntry(UUID transactionId, UUID accountId, Direction direction, BigDecimal amount,
                        BigDecimal balanceAfter, long accountSeq, Instant createdAt) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.accountSeq = accountSeq;
        this.createdAt = createdAt;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public Direction getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
