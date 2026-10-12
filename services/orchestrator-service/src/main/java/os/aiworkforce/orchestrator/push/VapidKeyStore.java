// @find: VAPID keys, push server key pair, application server key, generate once, AIWOS_VAPID_PUBLIC_KEY, AIWOS_VAPID_PRIVATE_KEY, push_vapid_keys table, encrypted private key, VapidKeyStore
// @what: Holds the server's VAPID key pair: from the environment when set, otherwise generated once, stored encrypted and read back.
// @flow: Used by PushService (public key for browsers) and PushSender (signing); reads and writes push_vapid_keys; encrypts with EnvelopeEncryptionService
package os.aiworkforce.orchestrator.push;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;

/**
 * The server's Web Push identity.
 *
 * <p>Every browser subscription is bound to one public key, so the key pair must outlive restarts
 * and be the same on every instance: a new pair would silently orphan every device. It comes from
 * {@code AIWOS_VAPID_PUBLIC_KEY} and {@code AIWOS_VAPID_PRIVATE_KEY} when an operator sets both;
 * otherwise it is generated the first time it is needed and kept in {@code push_vapid_keys}, the
 * private half encrypted under the platform master key like every other stored secret. Two
 * instances generating at once both insert with {@code on conflict do nothing} and read back the
 * one row that won.
 *
 * <p>A pair that cannot be had - one environment variable without the other, halves that do not
 * belong together, a stored key the master key cannot open - turns push off with an error in the
 * log rather than quietly replacing the identity. A database that cannot be reached is tried again
 * after {@value #RETRY_SECONDS} seconds.
 */
@Component
public class VapidKeyStore {

    private static final Logger log = LoggerFactory.getLogger(VapidKeyStore.class);

    /** How long a failed load is remembered before it is tried again. */
    static final long RETRY_SECONDS = 60;

    private static final String SELECT = "select public_key, private_key_encrypted from push_vapid_keys where id = 1";
    private static final String INSERT =
            "insert into push_vapid_keys (id, public_key, private_key_encrypted) values (1, ?, ?) on conflict (id) do nothing";

    private final JdbcTemplate jdbc;
    private final EnvelopeEncryptionService encryption;
    private final String envPublic;
    private final String envPrivate;
    private final Clock clock;

    private volatile WebPushCrypto.RawKeyPair keys;
    private volatile Instant retryAfter = Instant.MIN;
    /** Set once the keys are known to be unusable as configured; nothing will fix that without a restart. */
    private volatile boolean misconfigured;

    /** Spring's constructor: the database, the encryption service and the two optional environment values. */
    @Autowired
    public VapidKeyStore(
            JdbcTemplate jdbc,
            EnvelopeEncryptionService encryption,
            @Value("${aiwos.push.vapid-public-key:${AIWOS_VAPID_PUBLIC_KEY:}}") String envPublic,
            @Value("${aiwos.push.vapid-private-key:${AIWOS_VAPID_PRIVATE_KEY:}}") String envPrivate) {
        this(jdbc, encryption, envPublic, envPrivate, Clock.systemUTC());
    }

    /** For tests: a clock of their own. */
    VapidKeyStore(JdbcTemplate jdbc, EnvelopeEncryptionService encryption, String envPublic, String envPrivate, Clock clock) {
        this.jdbc = jdbc;
        this.encryption = encryption;
        this.envPublic = envPublic == null ? "" : envPublic.strip();
        this.envPrivate = envPrivate == null ? "" : envPrivate.strip();
        this.clock = clock;
    }

    // @find: get VAPID keys, load or create push keys
    /**
     * The key pair, or empty when push cannot be offered.
     *
     * <p>Loaded once and then held in memory. The first call may read the database, and on an empty
     * table generate a pair, encrypt its private half and insert it. Never throws: a failure is
     * logged and reported as empty, and retried after {@value #RETRY_SECONDS} seconds unless it was
     * a configuration mistake.
     */
    public Optional<WebPushCrypto.RawKeyPair> keys() {
        WebPushCrypto.RawKeyPair held = keys;
        if (held != null) {
            return Optional.of(held);
        }
        if (misconfigured || clock.instant().isBefore(retryAfter)) {
            return Optional.empty();
        }
        synchronized (this) {
            if (keys != null) {
                return Optional.of(keys);
            }
            try {
                keys = load();
                return Optional.ofNullable(keys);
            } catch (RuntimeException unavailable) {
                retryAfter = clock.instant().plus(Duration.ofSeconds(RETRY_SECONDS));
                log.warn("Push keys could not be loaded; push stays off for now: {}", unavailable.getMessage());
                return Optional.empty();
            }
        }
    }

    /** The public key browsers subscribe with, base64url, or empty when push is off. */
    public Optional<String> publicKey() {
        return keys().map(WebPushCrypto.RawKeyPair::publicKey);
    }

    // The environment's pair when set, else the stored one, else a new stored one. Null when misconfigured.
    private WebPushCrypto.RawKeyPair load() {
        try {
            return loadOrRefuse();
        } catch (Unusable refused) {
            misconfigured = true;
            log.error("Push notifications are off: {}", refused.getMessage());
            return null;
        }
    }

    // The pair, or an Unusable naming why there can be none until somebody fixes the configuration.
    private WebPushCrypto.RawKeyPair loadOrRefuse() {
        if (!envPublic.isEmpty() || !envPrivate.isEmpty()) {
            if (envPublic.isEmpty() || envPrivate.isEmpty()) {
                throw new Unusable("Only one of AIWOS_VAPID_PUBLIC_KEY and AIWOS_VAPID_PRIVATE_KEY is set; set both or neither");
            }
            if (!WebPushCrypto.pairMatches(envPublic, envPrivate)) {
                throw new Unusable("AIWOS_VAPID_PUBLIC_KEY and AIWOS_VAPID_PRIVATE_KEY are not one P-256 key pair");
            }
            log.info("Push notifications use the VAPID key pair from the environment");
            return new WebPushCrypto.RawKeyPair(envPublic, envPrivate);
        }
        Optional<WebPushCrypto.RawKeyPair> stored = readStored();
        if (stored.isPresent()) {
            return stored.get();
        }
        WebPushCrypto.RawKeyPair fresh = WebPushCrypto.generateKeyPair();
        int inserted = jdbc.update(INSERT, fresh.publicKey(), encryption.encrypt(null, fresh.privateKey()).serialise());
        if (inserted == 1) {
            log.info("Generated and stored this platform's VAPID key pair for push notifications");
        }
        // Read back whichever row won, so every instance signs with the same key.
        return readStored().orElseThrow(() -> new Unusable("The VAPID key pair was stored but could not be read back"));
    }

    // The stored pair, decrypted and checked; empty when there is none, Unusable when it cannot be used.
    private Optional<WebPushCrypto.RawKeyPair> readStored() {
        List<String[]> rows = jdbc.query(SELECT, (row, n) -> new String[] {row.getString(1), row.getString(2)});
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        String publicKey = rows.get(0)[0];
        String privateKey;
        try {
            privateKey = encryption.decrypt(null, rows.get(0)[1]);
        } catch (RuntimeException unreadable) {
            throw new Unusable("The stored VAPID private key cannot be decrypted with the current master key");
        }
        if (!WebPushCrypto.pairMatches(publicKey, privateKey)) {
            throw new Unusable("The stored VAPID keys are not one P-256 key pair");
        }
        return Optional.of(new WebPushCrypto.RawKeyPair(publicKey, privateKey));
    }

    /** Keys that exist but cannot be used: push stays off until the configuration is fixed and the service restarted. */
    private static final class Unusable extends RuntimeException {
        Unusable(String why) {
            super(why, null, false, false);
        }
    }
}
