package dev.ledgerly.wallet.api;

import dev.ledgerly.identity.CurrentUser;
import dev.ledgerly.ledger.AccountStatus;
import dev.ledgerly.ledger.ReconciliationReport;
import dev.ledgerly.shared.money.Amounts;
import dev.ledgerly.wallet.service.AdminWalletService;
import dev.ledgerly.wallet.service.AdminWalletService.ReversalReceipt;
import dev.ledgerly.wallet.service.AdminWalletService.WalletSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Operator endpoints. ADMIN-only is enforced in SecurityConfig for the whole /api/v1/admin/** prefix. */
@RestController
@RequestMapping("/api/v1/admin")
class AdminController {

    record ReasonRequest(@NotBlank @Size(max = 140) String reason) {
    }

    record ReconciliationResponse(boolean healthy, long accountsChecked, String totalOfAllBalances,
                                  List<UUID> unbalancedTransactions, List<UUID> mismatchedAccounts) {
        static ReconciliationResponse from(ReconciliationReport r) {
            return new ReconciliationResponse(r.healthy(), r.accountsChecked(), Amounts.format(r.totalOfAllBalances()),
                    r.unbalancedTransactions(), r.mismatchedAccounts());
        }
    }

    record AdminWalletResponse(UUID accountId, UUID userId, String name, String balance, AccountStatus status) {
        static AdminWalletResponse from(WalletSummary s) {
            return new AdminWalletResponse(s.accountId(), s.userId(), s.name(), Amounts.format(s.account().balance()),
                    s.account().status());
        }
    }

    record StatusResponse(UUID accountId, AccountStatus status) {
    }

    private final AdminWalletService admin;
    private final CurrentUser currentUser;

    AdminController(AdminWalletService admin, CurrentUser currentUser) {
        this.admin = admin;
        this.currentUser = currentUser;
    }

    @GetMapping("/reconciliation")
    ReconciliationResponse reconciliation() {
        return ReconciliationResponse.from(admin.reconcile());
    }

    @GetMapping("/wallets")
    AdminWalletResponse findWallet(@RequestParam String email) {
        return AdminWalletResponse.from(admin.findByEmail(email));
    }

    @PostMapping("/wallets/{accountId}/freeze")
    StatusResponse freeze(@PathVariable UUID accountId, @Valid @RequestBody ReasonRequest request) {
        var view = admin.changeStatus(accountId, AccountStatus.FROZEN, currentUser.id(), request.reason());
        return new StatusResponse(view.id(), view.status());
    }

    @PostMapping("/wallets/{accountId}/unfreeze")
    StatusResponse unfreeze(@PathVariable UUID accountId, @Valid @RequestBody ReasonRequest request) {
        var view = admin.changeStatus(accountId, AccountStatus.ACTIVE, currentUser.id(), request.reason());
        return new StatusResponse(view.id(), view.status());
    }

    @PostMapping("/transactions/{transactionId}/reversal")
    @ResponseStatus(HttpStatus.CREATED)
    ReversalReceipt reverse(@PathVariable UUID transactionId, @Valid @RequestBody ReasonRequest request) {
        return admin.reverse(transactionId, currentUser.id(), request.reason());
    }
}
