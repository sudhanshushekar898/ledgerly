package dev.ledgerly.wallet.service;

import dev.ledgerly.identity.UserRegistered;
import dev.ledgerly.ledger.Ledger;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Every new customer gets an empty INR wallet, atomically with their sign-up. */
@Component
class WalletProvisioner {

    private final Ledger ledger;

    WalletProvisioner(Ledger ledger) {
        this.ledger = ledger;
    }

    @EventListener
    void on(UserRegistered event) {
        ledger.openUserWallet(event.userId());
    }
}
