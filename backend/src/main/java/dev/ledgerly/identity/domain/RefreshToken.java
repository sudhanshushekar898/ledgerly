package dev.ledgerly.identity.domain;

import dev.ledgerly.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One opaque refresh token (stored hashed). Rotated on every use; all rotations of a login share a family. */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private UUID familyId;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    private UUID replacedBy;

    @Column(length = 255)
    private String userAgent;

    @Column(length = 45)
    private String ip;

    protected RefreshToken() {}

    public RefreshToken(UUID userId, String tokenHash, UUID familyId, Instant expiresAt, String userAgent, String ip) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
        this.ip = ip;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void rotate(Instant now, UUID replacement) {
        this.revokedAt = now;
        this.replacedBy = replacement;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }
}
