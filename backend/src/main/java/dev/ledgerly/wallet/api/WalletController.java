package dev.ledgerly.wallet.api;

import dev.ledgerly.identity.CurrentUser;
import dev.ledgerly.wallet.api.WalletDtos.ContactResponse;
import dev.ledgerly.wallet.api.WalletDtos.InsightsResponse;
import dev.ledgerly.wallet.api.WalletDtos.ReceiptResponse;
import dev.ledgerly.wallet.api.WalletDtos.StatementResponse;
import dev.ledgerly.wallet.api.WalletDtos.TopUpRequest;
import dev.ledgerly.wallet.api.WalletDtos.WalletResponse;
import dev.ledgerly.wallet.service.IdempotencyService.Outcome;
import dev.ledgerly.wallet.service.WalletReadService;
import dev.ledgerly.wallet.service.WalletService;
import dev.ledgerly.wallet.service.WalletService.TopUpReceipt;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own wallet only: the account is resolved from the token, never from a path id, so no IDOR. */
@RestController
@RequestMapping("/api/v1/wallet")
class WalletController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final WalletService wallets;
    private final WalletReadService reads;
    private final CurrentUser currentUser;

    WalletController(WalletService wallets, WalletReadService reads, CurrentUser currentUser) {
        this.wallets = wallets;
        this.reads = reads;
        this.currentUser = currentUser;
    }

    @GetMapping
    WalletResponse wallet() {
        return WalletResponse.from(wallets.wallet(currentUser.id()));
    }

    @PostMapping("/topups")
    ResponseEntity<TopUpReceipt> topUp(@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                       @Valid @RequestBody TopUpRequest request) {
        Outcome<TopUpReceipt> outcome = wallets.topUp(currentUser.id(), key, request.amount());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(REPLAYED, String.valueOf(outcome.replayed()))
                .body(outcome.response());
    }

    @GetMapping("/statement")
    StatementResponse statement(@RequestParam(required = false) Long before,
                                @RequestParam(defaultValue = "20") int limit) {
        return StatementResponse.from(wallets.statement(currentUser.id(), before, limit));
    }

    /** The receipt, with the double-entry lines. 404 (not 403) for transactions the caller is not part of. */
    @GetMapping("/transactions/{transactionId}")
    ReceiptResponse receipt(@PathVariable UUID transactionId) {
        return ReceiptResponse.from(reads.receipt(currentUser.id(), transactionId));
    }

    @GetMapping("/insights")
    InsightsResponse insights(@RequestParam(defaultValue = "30") int days) {
        return InsightsResponse.from(reads.insights(currentUser.id(), days));
    }

    @GetMapping("/contacts")
    List<ContactResponse> contacts() {
        return reads.contacts(currentUser.id()).stream().map(ContactResponse::from).toList();
    }
}
