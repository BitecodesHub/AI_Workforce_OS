package os.aiworkforce.organisation.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.organisation.domain.Credential;
import os.aiworkforce.organisation.repository.Credentials;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.crypto.EncryptedValue;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Stores and serves the workspace's secrets.
 *
 * <p>This service is the only place in the platform that holds a decrypted credential, and only
 * for the length of one call. Three rules make that worth something:
 *
 * <ul>
 *   <li>No public endpoint returns a plaintext value. {@link #describe} returns a fingerprint;
 *       only the internal endpoint, reachable by a service token, returns the secret itself.
 *   <li>The organisation is bound into the ciphertext, so a value copied from one workspace's row
 *       into another's fails to decrypt rather than quietly working.
 *   <li>Every read is recorded on the row, so an unused credential is visible as unused and a
 *       credential being read by something unexpected is visible too.
 * </ul>
 */
@Service
public class CredentialService {

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);

    private final Credentials credentials;
    private final EnvelopeEncryptionService encryption;

    public CredentialService(
            Credentials credentials, EnvelopeEncryptionService encryption) {
        this.credentials = credentials;
        this.encryption = encryption;
    }

    /**
     * What the console is allowed to see.
     *
     * @param ref what points at the credential
     * @param kind what sort of secret it is
     * @param fingerprint enough to tell two apart, not enough to use either
     * @param present whether anything is stored
     * @param expiresAt when it stops working, where that is known
     * @param lastUsedAt when the platform last needed it
     */
    public record CredentialView(
            String ref,
            String kind,
            String fingerprint,
            boolean present,
            Instant expiresAt,
            Instant lastUsedAt) {}

    @Transactional
    public CredentialView store(UUID orgId, String ref, String kind, String plaintext, Instant expiresAt) {
        if (plaintext == null || plaintext.isBlank()) {
            throw ApiException.validation("value", "must not be empty");
        }

        EncryptedValue encrypted = encryption.encrypt(orgId.toString(), plaintext.strip());
        Credential credential = credentials.findByOrgIdAndRef(orgId, ref).orElseGet(() -> {
            Credential fresh = new Credential();
            fresh.setId(UuidV7.generate());
            fresh.setOrgId(orgId);
            fresh.setRef(ref);
            return fresh;
        });

        credential.setKind(kind == null ? "api_key" : kind);
        credential.setEncryptedValue(encrypted.serialise());
        credential.setFingerprint(encryption.fingerprint(plaintext.strip()));
        credential.setKeyId(encrypted.keyId());
        credential.setExpiresAt(expiresAt);
        credentials.save(credential);

        // The value is never logged; the reference and who stored it are.
        log.info(
                "Credential {} stored for workspace {} by {}",
                ref, orgId, RequestContext.actor().map(actor -> actor.id()).orElse("system"));
        return describe(credential);
    }

    /**
     * The decrypted value, for an internal caller only.
     *
     * <p>Returns empty rather than throwing when nothing is stored: for the router this is a skip
     * with a recorded reason, not a failure, and turning it into an exception would make one
     * unconfigured provider fail an entire run.
     */
    @Transactional
    public Optional<String> reveal(UUID orgId, String ref) {
        return credentials.findByOrgIdAndRef(orgId, ref).flatMap(credential -> {
            if (credential.hasExpired()) {
                log.warn("Credential {} for workspace {} has expired", ref, orgId);
                return Optional.empty();
            }
            credential.markUsed();
            credentials.save(credential);
            try {
                return Optional.of(encryption.decrypt(orgId.toString(), credential.getEncryptedValue()));
            } catch (ApiException e) {
                // A value that will not decrypt is a key-management problem, not a missing
                // credential, and it needs a person rather than a silent fallback.
                log.error("Credential {} for workspace {} could not be decrypted", ref, orgId, e);
                return Optional.empty();
            }
        });
    }

    @Transactional(readOnly = true)
    public List<CredentialView> list(UUID orgId) {
        return credentials.findByOrgIdOrderByRef(orgId).stream().map(this::describe).toList();
    }

    @Transactional
    public void delete(UUID orgId, String ref) {
        Credential credential = credentials.findByOrgIdAndRef(orgId, ref)
                .orElseThrow(() -> ApiException.notFound("credential", ref));
        credentials.delete(credential);
        log.info("Credential {} removed from workspace {}", ref, orgId);
    }

    /**
     * Re-wraps values still encrypted under a retired master key.
     *
     * <p>Rotation is a background job rather than a migration because the old key must keep
     * working until every value has moved. Doing it in one transaction would mean a failure
     * halfway leaves half the workspace's secrets unreadable.
     */
    @Transactional
    public int rewrapOutdated(UUID orgId, int limit) {
        int rewrapped = 0;
        for (Credential credential : credentials.findByOrgIdOrderByRef(orgId)) {
            if (rewrapped >= limit) {
                break;
            }
            EncryptedValue current = EncryptedValue.parse(credential.getEncryptedValue());
            if (!encryption.needsRewrap(current)) {
                continue;
            }
            String plaintext = encryption.decrypt(orgId.toString(), current);
            EncryptedValue rewrappedValue = encryption.encrypt(orgId.toString(), plaintext);
            credential.setEncryptedValue(rewrappedValue.serialise());
            credential.setKeyId(rewrappedValue.keyId());
            credentials.save(credential);
            rewrapped++;
        }
        if (rewrapped > 0) {
            log.info("Re-wrapped {} credential(s) for workspace {} under the current master key", rewrapped, orgId);
        }
        return rewrapped;
    }

    private CredentialView describe(Credential credential) {
        return new CredentialView(
                credential.getRef(),
                credential.getKind(),
                credential.getFingerprint(),
                true,
                credential.getExpiresAt(),
                credential.getLastUsedAt());
    }

    static ApiException notConfigured(String ref) {
        return new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED)
                .with("credentialRef", ref);
    }
}
