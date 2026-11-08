package dev.ledgerly.wallet.api;

import dev.ledgerly.identity.CurrentUser;
import dev.ledgerly.wallet.api.WalletDtos.TransferRequest;
import dev.ledgerly.wallet.service.IdempotencyService.Outcome;
import dev.ledgerly.wallet.service.WalletService;
import dev.ledgerly.wallet.service.WalletService.TransferReceipt;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The sender is always the caller (from the token); the recipient is chosen by email. */
@RestController
@RequestMapping("/api/v1/transfers")
class TransferController {

    private final WalletService wallets;
    private final CurrentUser currentUser;

    TransferController(WalletService wallets, CurrentUser currentUser) {
        this.wallets = wallets;
        this.currentUser = currentUser;
    }

    @PostMapping
    ResponseEntity<TransferReceipt> transfer(
            @RequestHeader(name = WalletController.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody TransferRequest request) {
        Outcome<TransferReceipt> outcome = wallets.transfer(currentUser.id(), key, request.recipientEmail(),
                request.amount(), request.memo());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(WalletController.REPLAYED, String.valueOf(outcome.replayed()))
                .body(outcome.response());
    }
}
