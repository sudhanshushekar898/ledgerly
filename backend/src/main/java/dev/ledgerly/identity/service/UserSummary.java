package dev.ledgerly.identity.service;

import dev.ledgerly.identity.domain.Role;
import dev.ledgerly.identity.domain.User;
import java.util.UUID;

/** The signed-in user as shown to the client. */
public record UserSummary(UUID id, String email, String name, Role role) {

    static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
