package dev.ledgerly.identity;

import dev.ledgerly.identity.domain.Role;
import dev.ledgerly.identity.domain.User;
import dev.ledgerly.identity.repository.UserRepository;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Public facade: look up customers without exposing the User entity to other modules. */
@Component
public class UserDirectory {

    public record Customer(UUID id, String name, String email) {
    }

    private final UserRepository users;

    UserDirectory(UserRepository users) {
        this.users = users;
    }

    /** Only enabled USER accounts can receive money; operators and disabled users are "not found". */
    @Transactional(readOnly = true)
    public Optional<Customer> findCustomerByEmail(String email) {
        return users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).filter(UserDirectory::isCustomer).map(UserDirectory::toCustomer);
    }

    /** Enabled customers among these ids (others are left out). */
    @Transactional(readOnly = true)
    public Map<UUID, Customer> customersByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(ids).stream().filter(UserDirectory::isCustomer)
                .collect(Collectors.toMap(User::getId, UserDirectory::toCustomer));
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> namesOf(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, User::getFullName));
    }

    private static boolean isCustomer(User user) {
        return user.isEnabled() && user.getRole() == Role.USER;
    }

    private static Customer toCustomer(User user) {
        return new Customer(user.getId(), user.getFullName(), user.getEmail());
    }
}
