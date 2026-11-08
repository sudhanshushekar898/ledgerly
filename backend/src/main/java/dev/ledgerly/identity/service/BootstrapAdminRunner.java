package dev.ledgerly.identity.service;

import dev.ledgerly.identity.domain.Role;
import dev.ledgerly.identity.domain.User;
import dev.ledgerly.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the operator (ADMIN) account from configuration when no admin exists yet. Admins cannot sign up. */
@Component
@ConditionalOnProperty("ledgerly.bootstrap.admin-email")
class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;
    private final String email;
    private final String password;

    BootstrapAdminRunner(UserRepository users, PasswordEncoder encoder, PasswordPolicy passwordPolicy,
                         @Value("${ledgerly.bootstrap.admin-email}") String email,
                         @Value("${ledgerly.bootstrap.admin-password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.passwordPolicy = passwordPolicy;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.existsByRole(Role.ADMIN)) {
            return;
        }
        passwordPolicy.validate(password);
        User admin = users.save(User.admin(Emails.normalize(email), encoder.encode(password)));
        log.info("Bootstrap admin account created (id {})", admin.getId());
    }
}
