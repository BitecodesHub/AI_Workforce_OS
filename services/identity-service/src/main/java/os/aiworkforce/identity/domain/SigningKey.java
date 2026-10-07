package os.aiworkforce.identity.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * One key that signs, or has signed, this platform's access tokens.
 *
 * <p>Not a {@code BaseEntity}: the table predates that shape and is keyed by the key identifier
 * itself, which is what a token names in its header and what a verifier looks up.
 *
 * <p>Status moves one way. {@code active} signs; {@code retiring} no longer signs but is still
 * published, so tokens it signed keep verifying until {@link #expiresAt} has passed; {@code
 * retired} is history.
 */
@Entity
@Table(name = "signing_keys")
public class SigningKey implements Persistable<String> {

    public static final String ACTIVE = "active";
    public static final String RETIRING = "retiring";
    public static final String RETIRED = "retired";

    /**
     * Stored in place of a private key that is held outside the database.
     *
     * <p>A key supplied as {@code aiwos.security.signing-private-key-pem} lives in the secret
     * store, and copying it into a table would undo the reason it was put there. Its public half
     * is still recorded, so every replica publishes it and a rotation can retire it gracefully.
     */
    public static final String HELD_EXTERNALLY = "external";

    @Id
    @Column(name = "kid", nullable = false, updatable = false)
    private String kid;

    @Column(nullable = false)
    private String algorithm;

    @Column(name = "public_jwk", nullable = false)
    private String publicJwk;

    @Column(name = "private_key_encrypted", nullable = false)
    private String privateKeyEncrypted;

    @Column(nullable = false)
    private String status = ACTIVE;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt = Instant.now();

    @Column(name = "retired_at")
    private Instant retiredAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /* The identifier is assigned here, so Spring Data cannot tell a new row from its id alone. */
    @Transient
    private boolean isNew = true;

    @Override
    public String getId() {
        return kid;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.isNew = false;
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    public boolean isHeldExternally() {
        return HELD_EXTERNALLY.equals(privateKeyEncrypted);
    }

    /** Whether verifiers should still be offered this key at {@code now}. */
    public boolean isPublishedAt(Instant now) {
        if (ACTIVE.equals(status)) {
            return true;
        }
        return RETIRING.equals(status) && (expiresAt == null || expiresAt.isAfter(now));
    }

    /** Stops signing with this key, keeping it published until {@code publishUntil}. */
    public void retire(Instant now, Instant publishUntil) {
        this.status = RETIRING;
        this.retiredAt = now;
        this.expiresAt = publishUntil;
    }

    /** Signs with this key again, as when an operator goes back to a key supplied earlier. */
    public void reactivate(Instant now) {
        this.status = ACTIVE;
        this.activatedAt = now;
        this.retiredAt = null;
        this.expiresAt = null;
    }

    // ---- Accessors -----------------------------------------------------------------------

    public String getKid() {
        return kid;
    }

    public void setKid(String kid) {
        this.kid = kid;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getPublicJwk() {
        return publicJwk;
    }

    public void setPublicJwk(String publicJwk) {
        this.publicJwk = publicJwk;
    }

    public String getPrivateKeyEncrypted() {
        return privateKeyEncrypted;
    }

    public void setPrivateKeyEncrypted(String privateKeyEncrypted) {
        this.privateKeyEncrypted = privateKeyEncrypted;
    }

    public String getStatus() {
        return status;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
