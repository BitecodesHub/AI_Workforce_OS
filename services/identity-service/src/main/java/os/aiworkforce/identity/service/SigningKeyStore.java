package os.aiworkforce.identity.service;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.identity.domain.SigningKey;
import os.aiworkforce.identity.repository.SigningKeys;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.crypto.EncryptedValue;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;

/**
 * Keeps the token signing key in the database, so every replica and every restart signs with the
 * same one.
 *
 * <p>A key generated in memory at startup changes on every restart and differs between replicas,
 * and every token signed by another copy of the key then fails verification somewhere. Storing one
 * key - its private half encrypted under the platform master key - and having every process load
 * it is what makes a restart invisible to the people signed in.
 *
 * <p>The key identifier is always the RFC 7638 thumbprint of the public key. A new key therefore
 * always has a new identifier, and a verifier that meets one it has not seen fetches the key set
 * again instead of checking the token against a stale key under a reused name.
 *
 * <p>A key that stops signing is kept published, as {@code retiring}, for {@link #publishOverlap()}
 * - the access-token lifetime, the clock-skew allowance and the verifiers' key-set cache added
 * together - so a token signed a moment before the change keeps verifying until it expires.
 */
@Service
public class SigningKeyStore {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyStore.class);

    /** Advisory-lock key for creating or replacing the active key: "aiwos-k1" as eight bytes. */
    static final long KEY_LOCK = 0x6169776f732d6b31L;

    static final String ALGORITHM = "ES256";

    private final SigningKeys keys;
    private final EnvelopeEncryptionService encryption;
    private final Duration publishOverlap;
    private final boolean deployed;
    private final String masterKeyId;

    public SigningKeyStore(SigningKeys keys, EnvelopeEncryptionService encryption, PlatformProperties properties) {
        this.keys = keys;
        this.encryption = encryption;
        this.deployed = properties.environment().isDeployed();
        PlatformProperties.Security security = properties.security();
        this.masterKeyId = security.encryption().masterKeyId();
        this.publishOverlap = security.accessTokenTtl()
                .plus(security.clockSkewLeeway())
                .plus(security.jwksCacheTtl());
    }

    /**
     * The stored active key, creating it on first use.
     *
     * <p>The common case reads one row. Only when there is no usable key does this take the
     * advisory lock, look again (another replica may have written one while this one waited) and,
     * if there is still none, generate a P-256 key and insert it. The row is read back after the
     * insert, so the caller always signs with what the table holds rather than what it meant to
     * write.
     *
     * <p>An active row supplied as a PEM that is no longer configured is retired and replaced: its
     * public half stays published for the overlap, so nobody holding a token it signed is signed
     * out early. So, in local and test only, is a stored key this process cannot decrypt.
     *
     * @throws IllegalStateException in a deployed environment, when the stored key was encrypted
     *     under a master key this process does not hold or cannot be decrypted with the one it
     *     does. Every replica shares that key; a single pod with a wrong or stale master key must
     *     not replace it for all of them, and then have the healthy pods replace it back.
     */
    @Transactional
    public ECKey activeKey() {
        Optional<SigningKey> current = keys.findActive();
        Optional<ECKey> stored = current.flatMap(this::readPrivateKey);
        if (stored.isPresent()) {
            return stored.get();
        }
        current.ifPresent(this::refuseUnreadableWhenDeployed);

        keys.holdKeyCreationLock(KEY_LOCK);
        Optional<SigningKey> active = keys.findActive();
        if (active.isPresent()) {
            Optional<ECKey> usable = readPrivateKey(active.get());
            if (usable.isPresent()) {
                return usable.get();
            }
            refuseUnreadableWhenDeployed(active.get());
            log.warn(
                    "Signing key {} cannot be used by this process; retiring it and generating a new one",
                    active.get().getKid());
            retire(active.get());
        }

        ECKey generated = generate();
        SigningKey row = newRow(generated);
        row.setPrivateKeyEncrypted(encryption.encrypt(null, generated.toJSONString()).serialise());
        keys.saveAndFlush(row);
        log.info("Generated and stored token signing key {}", generated.getKeyID());

        return keys.findActive()
                .flatMap(this::readPrivateKey)
                .orElseThrow(() -> new IllegalStateException("The signing key just stored could not be read back"));
    }

    /**
     * Records a key supplied from configuration as the active one.
     *
     * <p>Only the public half is written; the private key stays in the secret store it came from.
     * Recording it still matters: every replica then publishes it, and when the configured key is
     * changed the previous one is retired with an overlap instead of vanishing from the key set
     * while tokens it signed are still in use.
     *
     * @throws IllegalStateException when the key identifier already names a different key, which
     *     would publish the wrong public key under it
     */
    @Transactional
    public void adoptExternalKey(ECKey key) {
        keys.holdKeyCreationLock(KEY_LOCK);

        Optional<SigningKey> existing = keys.findById(key.getKeyID());
        if (existing.isPresent() && !samePublicKey(existing.get(), key)) {
            throw new IllegalStateException("Signing key id " + key.getKeyID()
                    + " already names a different key; give a new key a new aiwos.security.active-key-id");
        }

        Optional<SigningKey> active = keys.findActive();
        if (active.isPresent() && active.get().getKid().equals(key.getKeyID())) {
            return;
        }
        active.ifPresent(this::retire);

        if (existing.isPresent()) {
            SigningKey row = existing.get();
            row.reactivate(Instant.now());
            keys.saveAndFlush(row);
        } else {
            SigningKey row = newRow(key);
            row.setPrivateKeyEncrypted(SigningKey.HELD_EXTERNALLY);
            keys.saveAndFlush(row);
        }
        log.info("Configured token signing key {} recorded as the active key", key.getKeyID());
    }

    /**
     * The public keys verifiers should accept now: the active key and every retiring key still
     * inside its overlap. A row that cannot be parsed is skipped rather than breaking the set.
     */
    @Transactional(readOnly = true)
    public List<JWK> publishedKeys() {
        Instant now = Instant.now();
        return keys.findByStatusIn(List.of(SigningKey.ACTIVE, SigningKey.RETIRING)).stream()
                .filter(row -> row.isPublishedAt(now))
                .map(SigningKeyStore::publicKey)
                .flatMap(Optional::stream)
                .toList();
    }

    /** How long a retired key stays published. */
    public Duration publishOverlap() {
        return publishOverlap;
    }

    /**
     * Stops startup when a deployed process cannot read the shared stored key.
     *
     * <p>A key held externally is exempt: it was configured as a PEM that is no longer set, and
     * replacing it with a stored key is the intended way off a PEM.
     */
    private void refuseUnreadableWhenDeployed(SigningKey row) {
        if (!deployed || row.isHeldExternally()) {
            return;
        }
        String storedUnder;
        try {
            storedUnder = EncryptedValue.parse(row.getPrivateKeyEncrypted()).keyId();
        } catch (RuntimeException e) {
            storedUnder = "an unreadable key id";
        }
        throw new IllegalStateException("Token signing key " + row.getKid() + " is stored encrypted under master key '"
                + storedUnder + "', and this process, holding master key '" + masterKeyId
                + "', cannot read it. Give this process the same aiwos.security.encryption.master-key-base64"
                + " and master-key-id as the other replicas; the key is not replaced outside local and test.");
    }

    private void retire(SigningKey row) {
        Instant now = Instant.now();
        row.retire(now, now.plus(publishOverlap));
        // Flushed at once: the new active row must not reach the database before this update,
        // or the single-active index refuses it.
        keys.saveAndFlush(row);
    }

    private Optional<ECKey> readPrivateKey(SigningKey row) {
        if (row.isHeldExternally()) {
            return Optional.empty();
        }
        try {
            ECKey key = ECKey.parse(encryption.decrypt(null, row.getPrivateKeyEncrypted()));
            if (!key.isPrivate() || !Curve.P_256.equals(key.getCurve()) || !row.getKid().equals(key.getKeyID())) {
                log.warn("Stored signing key {} is not a P-256 private key under its own id", row.getKid());
                return Optional.empty();
            }
            return Optional.of(key);
        } catch (ParseException | RuntimeException e) {
            log.warn("Stored signing key {} could not be decrypted: {}", row.getKid(), e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<JWK> publicKey(SigningKey row) {
        try {
            return Optional.of(JWK.parse(row.getPublicJwk()).toPublicJWK());
        } catch (ParseException e) {
            log.warn("Published signing key {} could not be parsed; leaving it out of the key set", row.getKid());
            return Optional.empty();
        }
    }

    private static boolean samePublicKey(SigningKey row, ECKey key) {
        return publicKey(row)
                .filter(ECKey.class::isInstance)
                .map(ECKey.class::cast)
                .map(stored -> stored.getCurve().equals(key.getCurve())
                        && stored.getX().equals(key.getX())
                        && stored.getY().equals(key.getY()))
                .orElse(false);
    }

    private static SigningKey newRow(ECKey key) {
        SigningKey row = new SigningKey();
        row.setKid(key.getKeyID());
        // Set explicitly: the column still defaults to EdDSA from the original schema, and this
        // platform signs with ES256 (see TokenService for why).
        row.setAlgorithm(ALGORITHM);
        row.setPublicJwk(key.toPublicJWK().toJSONString());
        return row;
    }

    /** A new P-256 signing key, identified by its RFC 7638 thumbprint. */
    static ECKey generate() {
        try {
            return new ECKeyGenerator(Curve.P_256)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256)
                    .keyIDFromThumbprint(true)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not generate a signing key", e);
        }
    }
}
