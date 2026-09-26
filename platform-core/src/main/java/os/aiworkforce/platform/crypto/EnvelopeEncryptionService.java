package os.aiworkforce.platform.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Encrypts the secrets the platform stores: integration credentials, provider API keys,
 * refresh tokens held on a user's behalf.
 *
 * <p>Envelope encryption rather than a single key: each value is encrypted under a fresh data
 * key, and only that short data key is wrapped by the master key. Rotating the master key then
 * rewraps a small number of keys instead of re-encrypting every row, and a leaked data key
 * exposes one value rather than the database.
 *
 * <p>The organisation identifier is bound into the encryption as additional authenticated data,
 * so a ciphertext copied from one workspace's row into another's fails to decrypt instead of
 * silently succeeding.
 */
@Service
public class EnvelopeEncryptionService {

    private static final String KEY_ALGORITHM = "AES";
    private static final int DATA_KEY_BITS = 256;

    private final PlatformProperties.Encryption config;
    private final SecureRandom random = new SecureRandom();
    private final SecretKey masterKey;
    private final String masterKeyId;

    public EnvelopeEncryptionService(PlatformProperties properties) {
        this.config = properties.security().encryption();
        this.masterKeyId = config.masterKeyId();
        this.masterKey = resolveMasterKey(properties);
    }

    /**
     * Derives the master key, refusing a development key outside local and test.
     *
     * <p>A deployed environment without a configured master key is a configuration mistake that
     * must stop the process, not a condition to work around: starting anyway would write secrets
     * under a key that is published in this source file.
     */
    private SecretKey resolveMasterKey(PlatformProperties properties) {
        String configured = config.masterKeyBase64();
        if (configured != null && !configured.isBlank()) {
            byte[] raw = Base64.getDecoder().decode(configured);
            if (raw.length != 32) {
                throw new IllegalStateException(
                        "aiwos.security.encryption.master-key-base64 must decode to 32 bytes, got " + raw.length);
            }
            return new SecretKeySpec(raw, KEY_ALGORITHM);
        }
        if (properties.environment().isDeployed()) {
            throw new IllegalStateException(
                    "aiwos.security.encryption.master-key-base64 is required outside local and test");
        }
        // Deterministic local key derived from the development secret, so a developer's
        // encrypted rows survive a restart without anybody configuring a key.
        try {
            byte[] derived = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(properties.security().developmentSecret().getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(derived, KEY_ALGORITHM);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    /** Encrypts {@code plaintext} for {@code orgId}. */
    public EncryptedValue encrypt(String orgId, String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        try {
            SecretKey dataKey = newDataKey();
            byte[] iv = randomIv();
            Cipher cipher = Cipher.getInstance(config.transformation());
            cipher.init(Cipher.ENCRYPT_MODE, dataKey, gcmSpec(iv));
            cipher.updateAAD(aad(orgId));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return EncryptedValue.of(masterKeyId, wrap(dataKey, orgId), iv, ciphertext);
        } catch (GeneralSecurityException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "The value could not be encrypted.", e);
        }
    }

    /** Decrypts a stored value for {@code orgId}. */
    public String decrypt(String orgId, EncryptedValue value) {
        try {
            SecretKey dataKey = unwrap(value.wrappedDataKey(), value.keyId(), orgId);
            Cipher cipher = Cipher.getInstance(config.transformation());
            cipher.init(Cipher.DECRYPT_MODE, dataKey, gcmSpec(value.iv()));
            cipher.updateAAD(aad(orgId));
            return new String(cipher.doFinal(value.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Authentication failure and a wrong organisation are indistinguishable on purpose:
            // both mean this ciphertext is not readable here.
            throw new ApiException(
                    ErrorCode.INTERNAL_ERROR, "The stored value could not be decrypted.", e);
        }
    }

    public String decrypt(String orgId, String serialised) {
        return decrypt(orgId, EncryptedValue.parse(serialised));
    }

    /**
     * A fingerprint safe to show in the interface.
     *
     * <p>The console has to prove a credential is present and tell two apart, without ever
     * returning one. A truncated digest does both and reveals nothing useful about the secret.
     */
    public String fingerprint(String plaintext) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 6));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    /** True when a value was wrapped under a key that is no longer the active one. */
    public boolean needsRewrap(EncryptedValue value) {
        return !masterKeyId.equals(value.keyId());
    }

    private SecretKey newDataKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(KEY_ALGORITHM);
        generator.init(DATA_KEY_BITS, random);
        return generator.generateKey();
    }

    private byte[] wrap(SecretKey dataKey, String orgId) throws GeneralSecurityException {
        byte[] iv = randomIv();
        Cipher cipher = Cipher.getInstance(config.transformation());
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, gcmSpec(iv));
        cipher.updateAAD(aad(orgId));
        byte[] wrapped = cipher.doFinal(dataKey.getEncoded());
        byte[] out = new byte[iv.length + wrapped.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(wrapped, 0, out, iv.length, wrapped.length);
        return out;
    }

    private SecretKey unwrap(byte[] wrapped, String keyId, String orgId) throws GeneralSecurityException {
        if (!masterKeyId.equals(keyId)) {
            throw new ApiException(
                    ErrorCode.INTERNAL_ERROR,
                    "The stored value was encrypted under a master key this process does not hold.");
        }
        int ivLength = config.ivLengthBytes();
        byte[] iv = Arrays.copyOfRange(wrapped, 0, ivLength);
        byte[] body = Arrays.copyOfRange(wrapped, ivLength, wrapped.length);
        Cipher cipher = Cipher.getInstance(config.transformation());
        cipher.init(Cipher.DECRYPT_MODE, masterKey, gcmSpec(iv));
        cipher.updateAAD(aad(orgId));
        return new SecretKeySpec(cipher.doFinal(body), KEY_ALGORITHM);
    }

    private GCMParameterSpec gcmSpec(byte[] iv) {
        return new GCMParameterSpec(config.tagLengthBits(), iv);
    }

    private byte[] randomIv() {
        byte[] iv = new byte[config.ivLengthBytes()];
        random.nextBytes(iv);
        return iv;
    }

    /* Binding the organisation into the cipher stops a ciphertext being moved between rows. */
    private static byte[] aad(String orgId) {
        return ("org:" + (orgId == null ? "platform" : orgId)).getBytes(StandardCharsets.UTF_8);
    }
}
