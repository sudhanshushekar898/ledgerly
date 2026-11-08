package dev.ledgerly.ledger.domain;

import dev.ledgerly.ledger.TransactionKind;
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
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/** Header of one balanced posting. Append-only (a DB trigger rejects UPDATE and DELETE). */
@Entity
@Immutable
@Table(name = "journal_transactions")
public class JournalTransaction implements Persistable<UUID> {

    @Id
    private UUID id = Ids.newId();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionKind kind;

    @Column(nullable = false)
    private UUID initiatedBy;

    @Column(length = 140)
    private String memo;

    private UUID reversesTransactionId;

    @Column(nullable = false)
    private Instant createdAt;

    /** The id is assigned in Java, so tell Spring Data this is new: persist, never a select-then-merge. */
    @Transient
    private boolean isNew = true;

    protected JournalTransaction() {}

    public JournalTransaction(TransactionKind kind, UUID initiatedBy, String memo, UUID reversesTransactionId,
                              Instant createdAt) {
        this.kind = kind;
        this.initiatedBy = initiatedBy;
        this.memo = memo;
        this.reversesTransactionId = reversesTransactionId;
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

    public TransactionKind getKind() {
        return kind;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getMemo() {
        return memo;
    }

    public UUID getReversesTransactionId() {
        return reversesTransactionId;
    }
}
