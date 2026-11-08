package dev.ledgerly.identity.api;

import dev.ledgerly.identity.CurrentUser;
import dev.ledgerly.identity.api.AuthDtos.LoginRequest;
import dev.ledgerly.identity.api.AuthDtos.LoginResponse;
import dev.ledgerly.identity.api.AuthDtos.RegisterRequest;
import dev.ledgerly.identity.service.AuthResult;
import dev.ledgerly.identity.service.AuthService;
import dev.ledgerly.identity.service.UserSummary;
import dev.ledgerly.shared.error.DomainException;
import dev.ledgerly.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private static final String COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthService auth;
    private final CurrentUser currentUser;

    AuthController(AuthService auth, CurrentUser currentUser) {
        this.auth = auth;
        this.currentUser = currentUser;
    }

    @PostMapping("/register")
    ResponseEntity<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return withSession(HttpStatus.CREATED, auth.register(request.email(), request.password(), request.fullName()));
    }

    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return withSession(HttpStatus.OK, auth.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    ResponseEntity<LoginResponse> refresh(@CookieValue(name = COOKIE, required = false) String refreshToken,
                                          HttpServletRequest http) {
        requireScriptedRequest(http);
        return withSession(HttpStatus.OK, auth.refresh(refreshToken));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String refreshToken,
                                HttpServletRequest http) {
        requireScriptedRequest(http);
        auth.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
    }

    @GetMapping("/me")
    UserSummary me() {
        return auth.me(currentUser.id());
    }

    private static ResponseEntity<LoginResponse> withSession(HttpStatus status, AuthResult result) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie(result.refreshToken(), result.refreshMaxAgeSeconds()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new LoginResponse(result.accessToken(), "Bearer", result.expiresInSeconds(), result.user()));
    }

    /** HttpOnly, Secure, SameSite=Strict and scoped to /auth so the browser never sends it to other endpoints. */
    private static ResponseCookie cookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Strict").path(COOKIE_PATH).maxAge(maxAgeSeconds).build();
    }

    /** Cookie-authenticated endpoints require a header that cross-site forms and simple requests cannot set. */
    private static void requireScriptedRequest(HttpServletRequest request) {
        if (!"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Missing X-Requested-With header.");
        }
    }
}
