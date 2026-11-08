package dev.ledgerly.identity.api;

import dev.ledgerly.identity.service.UserSummary;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the /auth endpoints. */
final class AuthDtos {

    private AuthDtos() {}

    record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
                           @NotBlank @Size(max = 128) String password,
                           @NotBlank @Size(max = 100) String fullName) {
    }

    record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {
    }

    record LoginResponse(String accessToken, String tokenType, long expiresInSeconds, UserSummary user) {
    }
}
