package dev.ledgerly.wallet.service;

import dev.ledgerly.identity.UserDirectory;
import dev.ledgerly.ledger.AccountStatus;
import dev.ledgerly.ledger.AccountView;
import dev.ledgerly.ledger.Ledger;
import dev.ledgerly.ledger.PostedTransaction;
import dev.ledgerly.ledger.ReconciliationReport;
import dev.ledgerly.shared.error.DomainException;
import dev.ledgerly.shared.error.ErrorCode;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operator actions. Every change records who did it; logs carry ids only, never names or emails. */
@Service
public class AdminWalletService {

    private static final Logger log = LoggerFactory.getLogger(AdminWalletService.class);

    public record WalletSummary(UUID accountId, UUID userId, String name, AccountView account) {
    }

    public record ReversalReceipt(UUID reversalId, UUID reversedTransactionId, Instant createdAt) {
    }

    private final Ledger ledger;
    private final UserDirectory directory;

    AdminWalletService(Ledger ledger, UserDirectory directory) {
        this.ledger = ledger;
        this.directory = directory;
    }

    @Transactional(readOnly = true)
    public ReconciliationReport reconcile() {
        return ledger.reconcile();
    }

    @Transactional(readOnly = true)
    public WalletSummary findByEmail(String email) {
        return directory.findCustomerByEmail(email)
                .flatMap(c -> ledger.findUserWallet(c.id()).map(w -> new WalletSummary(w.id(), c.id(), c.name(), w)))
                .orElseThrow(() -> new DomainException(ErrorCode.WALLET_NOT_FOUND, "No wallet for this email."));
    }

    @Transactional
    public AccountView changeStatus(UUID accountId, AccountStatus status, UUID adminId, String reason) {
        AccountView result = ledger.changeStatus(accountId, status, adminId, reason.trim());
        log.info("Wallet {} set to {} by admin {}", accountId, status, adminId);
        return result;
    }

    @Transactional
    public ReversalReceipt reverse(UUID transactionId, UUID adminId, String reason) {
        PostedTransaction reversal = ledger.reverse(transactionId, adminId, reason.trim());
        log.info("Transaction {} reversed by admin {} (reversal {})", transactionId, adminId, reversal.id());
        return new ReversalReceipt(reversal.id(), transactionId, reversal.createdAt());
    }
}
