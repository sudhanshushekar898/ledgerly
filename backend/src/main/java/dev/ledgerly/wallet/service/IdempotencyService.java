package dev.ledgerly.wallet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerly.shared.error.DomainException;
import dev.ledgerly.shared.error.ErrorCode;
import dev.ledgerly.wallet.config.WalletProperties;
import dev.ledgerly.wallet.repository.IdempotencyStore;
import dev.ledgerly.wallet.repository.IdempotencyStore.StoredRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exactly-once effect for money-moving requests (ADR-004). The claim, the business action and the stored response
 * all commit in the caller's single transaction, which is why this requires one (MANDATORY).
 */
@Service
public class IdempotencyService {

    public record Outcome<T>(T response, boolean replayed) {
    }

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final IdempotencyStore store;
    private final ObjectMapper mapper;
    private final WalletProperties properties;
    private final Clock clock;
    private final Counter replays;

    IdempotencyService(IdempotencyStore store, ObjectMapper mapper, WalletProperties properties, Clock clock,
                       MeterRegistry metrics) {
        this.replays = metrics.counter("idempotency.replays");
        this.store = store;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** Checks the header before any work, so a bad key never touches the database. */
    public static void requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "The Idempotency-Key header is required.");
        }
        if (!KEY.matcher(key).matches()) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "Idempotency-Key must be 8-64 characters: letters, digits, '-' or '_'.");
        }
    }

    /** Fingerprint of what the request asks for. Same key + different fingerprint = a client bug, never a replay. */
    public static String fingerprint(String operation, String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(operation.getBytes(StandardCharsets.UTF_8));
            for (String part : parts) {
                digest.update((byte) 0x1f); // unit separator, so ("ab","c") and ("a","bc") differ
                digest.update(part.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> Outcome<T> execute(UUID userId, String key, String fingerprint, Class<T> type, Supplier<T> action) {
        requireValidKey(key);
        Instant now = clock.instant();
        if (store.tryClaim(userId, key, fingerprint, now, now.plus(properties.idempotencyKeyTtl()))) {
            T response = action.get();
            store.complete(userId, key, write(response));
            return new Outcome<>(response, false);
        }
        StoredRecord existing = store.find(userId, key).orElseThrow(IdempotencyService::inProgress);
        if (!existing.requestHash().equals(fingerprint)) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different request.");
        }
        if (existing.responseBody() == null) {
            throw inProgress();
        }
        replays.increment(); // a retry or double-click that did NOT move money again
        return new Outcome<>(read(existing.responseBody(), type), true);
    }

    private static DomainException inProgress() {
        return new DomainException(ErrorCode.REQUEST_IN_PROGRESS, "A request with this Idempotency-Key is still running.");
    }

    private String write(Object response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot store idempotent response", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot replay idempotent response", e);
        }
    }
}
