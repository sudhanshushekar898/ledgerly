package dev.ledgerly.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Proof that no money was created or lost. Healthy when every transaction balances, every account's cached balance
 * and entry count match its journal entries, and the sum of all balances is zero.
 */
public record ReconciliationReport(boolean healthy, long accountsChecked, BigDecimal totalOfAllBalances,
                                   List<UUID> unbalancedTransactions, List<UUID> mismatchedAccounts) {
}
