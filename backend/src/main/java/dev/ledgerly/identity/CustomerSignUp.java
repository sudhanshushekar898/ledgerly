package dev.ledgerly.identity;

import dev.ledgerly.identity.repository.UserRepository;
import dev.ledgerly.identity.service.AuthService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Public facade for creating customers from other modules (the demo seeder), with the same rules as sign-up. */
@Component
public class CustomerSignUp {

    private final AuthService auth;
    private final UserRepository users;

    CustomerSignUp(AuthService auth, UserRepository users) {
        this.auth = auth;
        this.users = users;
    }

    public boolean exists(String email) {
        return users.existsByEmail(email.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Registers a customer (opening the wallet in the same transaction) and returns the user id. */
    public UUID register(String email, String password, String fullName) {
        return auth.register(email, password, fullName).user().id();
    }
}
