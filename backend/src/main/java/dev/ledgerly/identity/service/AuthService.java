package dev.ledgerly.identity.service;

import dev.ledgerly.identity.UserRegistered;
import dev.ledgerly.identity.config.SecurityProperties;
import dev.ledgerly.identity.domain.RefreshToken;
import dev.ledgerly.identity.domain.User;
import dev.ledgerly.identity.repository.RefreshTokenRepository;
import dev.ledgerly.identity.repository.UserRepository;
import dev.ledgerly.shared.error.DomainException;
import dev.ledgerly.shared.error.ErrorCode;
import dev.ledgerly.shared.web.ClientInfo;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-up, login with lockout, refresh-token rotation with reuse detection, and logout.
 * Methods that reject a request use noRollbackFor so lockout counters and revocations still commit.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;
    private final JwtService jwt;
    private final SecurityProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    /** Compared against when the account is unknown, so response time does not reveal which emails exist. */
    private final String dummyHash;

    AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder encoder,
                PasswordPolicy passwordPolicy, JwtService jwt, SecurityProperties properties, Clock clock,
                ApplicationEventPublisher events) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.passwordPolicy = passwordPolicy;
        this.jwt = jwt;
        this.properties = properties;
        this.clock = clock;
        this.events = events;
        this.dummyHash = encoder.encode("not-a-real-password-" + UUID.randomUUID());
    }

    /**
     * Consumer sign-up. Saying "email taken" does reveal that an account exists; that is the usual trade-off for
     * self-service sign-up; rate limiting would slow enumeration (not built yet).
     */
    @Transactional
    public AuthResult register(String email, String password, String fullName) {
        String normalized = Emails.normalize(email);
        passwordPolicy.validate(password);
        if (users.existsByEmail(normalized)) {
            throw emailTaken();
        }
        User user;
        try {
            user = users.saveAndFlush(User.registered(normalized, encoder.encode(password), fullName.trim()));
        } catch (DataIntegrityViolationException e) {
            throw emailTaken(); // lost a race with a parallel sign-up; the unique constraint is the real guard
        }
        events.publishEvent(new UserRegistered(user.getId())); // opens the wallet in this same transaction
        log.info("User registered (id {})", user.getId());
        return startSession(user, clock.instant());
    }

    @Transactional(noRollbackFor = DomainException.class)
    public AuthResult login(String email, String password) {
        Instant now = clock.instant();
        User user = users.findByEmail(Emails.normalize(email)).orElse(null);
        if (user == null) {
            encoder.matches(password, dummyHash);
            throw badCredentials();
        }
        if (user.isLocked(now)) {
            throw new DomainException(ErrorCode.ACCOUNT_LOCKED, "Too many failed attempts. Try again later.");
        }
        if (!encoder.matches(password, user.getPasswordHash()) || !user.isEnabled()) {
            if (user.isEnabled() && user.recordFailedLogin(now, properties.maxFailedLogins(), properties.lockDuration())) {
                log.warn("Account locked after repeated failed logins (id {})", user.getId());
            }
            throw badCredentials();
        }
        user.recordSuccessfulLogin(now);
        return startSession(user, now);
    }

    /** Exchanges a refresh token for a new pair. Presenting an already-used token revokes the whole family. */
    @Transactional(noRollbackFor = DomainException.class)
    public AuthResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw unauthenticated();
        }
        Instant now = clock.instant();
        RefreshToken current = refreshTokens.findForUpdateByHash(Tokens.hash(rawRefreshToken))
                .orElseThrow(AuthService::unauthenticated);
        UUID familyId = current.getFamilyId();
        if (current.isRevoked()) {
            refreshTokens.revokeFamily(familyId, now);
            log.warn("Refresh token reuse detected; session family revoked (user {})", current.getUserId());
            throw unauthenticated();
        }
        if (current.isExpired(now)) {
            throw unauthenticated();
        }
        User user = users.findById(current.getUserId()).filter(User::isEnabled).orElse(null);
        if (user == null) {
            refreshTokens.revokeFamily(familyId, now);
            throw unauthenticated();
        }
        String raw = Tokens.generate();
        RefreshToken next = newRefreshToken(user, familyId, raw, now);
        current.rotate(now, next.getId());
        return result(user, raw);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokens.findForUpdateByHash(Tokens.hash(rawRefreshToken))
                .ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    @Transactional(readOnly = true)
    public UserSummary me(UUID userId) {
        return users.findById(userId).filter(User::isEnabled).map(UserSummary::from)
                .orElseThrow(AuthService::unauthenticated);
    }

    private AuthResult startSession(User user, Instant now) {
        String raw = Tokens.generate();
        newRefreshToken(user, UUID.randomUUID(), raw, now);
        return result(user, raw);
    }

    private RefreshToken newRefreshToken(User user, UUID familyId, String raw, Instant now) {
        ClientInfo client = ClientInfo.current();
        return refreshTokens.save(new RefreshToken(user.getId(), Tokens.hash(raw), familyId,
                now.plus(properties.refreshTokenTtl()), client.userAgent(), client.ip()));
    }

    private AuthResult result(User user, String rawRefreshToken) {
        return new AuthResult(jwt.issueAccessToken(user), jwt.accessTokenTtlSeconds(), rawRefreshToken,
                properties.refreshTokenTtl().toSeconds(), UserSummary.from(user));
    }

    private static DomainException emailTaken() {
        return new DomainException(ErrorCode.EMAIL_TAKEN, "An account with this email already exists.");
    }

    private static DomainException badCredentials() {
        return new DomainException(ErrorCode.UNAUTHENTICATED, "Invalid email or password.");
    }

    private static DomainException unauthenticated() {
        return new DomainException(ErrorCode.UNAUTHENTICATED, "Authentication required.");
    }
}
