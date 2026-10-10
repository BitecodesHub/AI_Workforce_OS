// @find: encrypted value, ciphertext, wrapped data key, key id, iv, serialised secret, stored credential format
// @what: Value object for a stored secret together with the key id and parameters needed to decrypt it.
// @flow: Produced by EnvelopeEncryptionService.encrypt
package os.aiworkforce.platform.crypto;

import java.util.Base64;

/**
 * A stored secret, together with everything needed to decrypt it later.
 *
 * <p>The key identifier travels with the ciphertext so a master key can be rotated without
 * rewriting every row: a value stays readable under the key that wrapped it until a background
 * job re-wraps it. The serialised form is deliberately self-describing, because a database dump
 * with no key identifier is a secret nobody can ever recover.
 *
 * @param keyId identifier of the master key that wrapped the data key
 * @param wrappedDataKey the organisation's data key, encrypted under the master key
 * @param iv initialisation vector, unique per encryption
 * @param ciphertext the encrypted payload, including the authentication tag
 * @param version format version, so the layout can change without ambiguity
 */
public record EncryptedValue(String keyId, byte[] wrappedDataKey, byte[] iv, byte[] ciphertext, int version) {

    private static final int CURRENT_VERSION = 1;
    private static final String SEPARATOR = ":";
    private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    public static EncryptedValue of(String keyId, byte[] wrappedDataKey, byte[] iv, byte[] ciphertext) {
        return new EncryptedValue(keyId, wrappedDataKey, iv, ciphertext, CURRENT_VERSION);
    }

    /** {@code v1:keyId:wrappedKey:iv:ciphertext}, all base64 without padding. */
    public String serialise() {
        return "v" + version
                + SEPARATOR + keyId
                + SEPARATOR + ENCODER.encodeToString(wrappedDataKey)
                + SEPARATOR + ENCODER.encodeToString(iv)
                + SEPARATOR + ENCODER.encodeToString(ciphertext);
    }

    public static EncryptedValue parse(String serialised) {
        if (serialised == null || serialised.isBlank()) {
            throw new IllegalArgumentException("Encrypted value is empty");
        }
        String[] parts = serialised.split(SEPARATOR, 5);
        if (parts.length != 5 || !parts[0].startsWith("v")) {
            throw new IllegalArgumentException("Encrypted value is not in the expected format");
        }
        int version = Integer.parseInt(parts[0].substring(1));
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported encrypted value version: " + version);
        }
        return new EncryptedValue(
                parts[1], DECODER.decode(parts[2]), DECODER.decode(parts[3]), DECODER.decode(parts[4]), version);
    }

    /** Keeps ciphertext out of logs and stack traces. */
    @Override
    public String toString() {
        return "EncryptedValue[keyId=" + keyId + ", version=" + version + ", bytes=" + ciphertext.length + "]";
    }
}
