package dev.ledgerly.wallet.api;

import dev.ledgerly.identity.CurrentUser;
import dev.ledgerly.wallet.api.WalletDtos.ContactResponse;
import dev.ledgerly.wallet.service.WalletReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** "Verify before you pay": resolve an email to the account holder's name before money moves. */
@RestController
@RequestMapping("/api/v1/recipients")
class RecipientController {

    private final WalletReadService reads;
    private final CurrentUser currentUser;

    RecipientController(WalletReadService reads, CurrentUser currentUser) {
        this.reads = reads;
        this.currentUser = currentUser;
    }

    @GetMapping
    ContactResponse lookup(@RequestParam String email) {
        return ContactResponse.from(reads.recipient(currentUser.id(), email));
    }
}
