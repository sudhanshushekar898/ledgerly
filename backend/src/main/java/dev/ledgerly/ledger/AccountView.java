package dev.ledgerly.ledger;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountView(UUID id, UUID ownerUserId, AccountType type, String currency, BigDecimal balance,
                          AccountStatus status) {
}
