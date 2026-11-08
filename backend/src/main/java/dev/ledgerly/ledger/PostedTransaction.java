package dev.ledgerly.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PostedTransaction(UUID id, TransactionKind kind, Instant createdAt, List<PostedEntry> entries) {

    public PostedEntry entryFor(UUID accountId) {
        return entries.stream().filter(e -> e.accountId().equals(accountId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account " + accountId + " is not part of transaction " + id));
    }
}
