package dev.ledgerly.ledger.repository;

import dev.ledgerly.ledger.domain.JournalEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    List<JournalEntry> findByTransactionId(UUID transactionId);
}
