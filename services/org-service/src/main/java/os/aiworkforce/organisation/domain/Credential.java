// @find: credential entity, encrypted value, fingerprint, key id, expires at, last used at, API key storage, credentials table
// @what: JPA entity for a stored encrypted credential.
// @flow: Mapped to the credentials table; used by CredentialService.
package os.aiworkforce.organisation.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.OrgScopedEntity;

/**
 * One secret held on a workspace's behalf.
 *
 * <p>The plaintext exists only inside {@code CredentialService}, for the length of one call. This
 * entity never exposes it: {@link #getEncryptedValue()} returns an envelope that is useless
 * without the master key, and {@link #getFingerprint()} is a truncated digest that proves a
 * credential is present and distinguishes two without revealing either.
 */
@Entity
@Table(name = "credentials")
public class Credential extends OrgScopedEntity {

    /** What points at this credential, for example {@code provider:openrouter}. */
    @Column(nullable = false)
    private String ref;

    @Column(nullable = false)
    private String kind = "api_key";

    @Column(name = "encrypted_value", nullable = false, columnDefinition = "text")
    private String encryptedValue;

    @Column(nullable = false)
    private String fingerprint;

    /** Which master key wrapped it, so rotation does not orphan existing rows. */
    @Column(name = "key_id", nullable = false)
    private String keyId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    public boolean hasExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    public void markUsed() {
        this.lastUsedAt = Instant.now();
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getEncryptedValue() {
        return encryptedValue;
    }

    public void setEncryptedValue(String encryptedValue) {
        this.encryptedValue = encryptedValue;
        this.rotatedAt = Instant.now();
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRotatedAt() {
        return rotatedAt;
    }

    /** Keeps ciphertext out of logs and stack traces. */
    @Override
    public String toString() {
        return "Credential[ref=" + ref + ", fingerprint=" + fingerprint + "]";
    }
}
