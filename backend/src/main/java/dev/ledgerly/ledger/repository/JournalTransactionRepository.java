package dev.ledgerly.ledger.repository;

import dev.ledgerly.ledger.domain.JournalTransaction;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalTransactionRepository extends JpaRepository<JournalTransaction, UUID> {

    boolean existsByReversesTransactionId(UUID transactionId);
}
