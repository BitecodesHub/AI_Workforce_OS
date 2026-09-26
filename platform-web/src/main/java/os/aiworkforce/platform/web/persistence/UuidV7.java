package os.aiworkforce.platform.web.persistence;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Time-ordered identifiers, as specified by RFC 9562.
 *
 * <p>A random UUIDv4 primary key scatters inserts across the whole B-tree, so every insert dirties
 * a different page and the index stops fitting in cache. A v7 puts a millisecond timestamp in the
 * high bits, so rows written together are stored together, while the random tail keeps them
 * unguessable - which matters, because these identifiers appear in URLs.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static UUID generate() {
        byte[] value = new byte[16];
        RANDOM.nextBytes(value);

        long timestamp = System.currentTimeMillis();
        value[0] = (byte) (timestamp >>> 40);
        value[1] = (byte) (timestamp >>> 32);
        value[2] = (byte) (timestamp >>> 24);
        value[3] = (byte) (timestamp >>> 16);
        value[4] = (byte) (timestamp >>> 8);
        value[5] = (byte) timestamp;

        value[6] = (byte) ((value[6] & 0x0F) | 0x70); // version 7
        value[8] = (byte) ((value[8] & 0x3F) | 0x80); // IETF variant

        long high = 0;
        long low = 0;
        for (int i = 0; i < 8; i++) {
            high = (high << 8) | (value[i] & 0xFFL);
        }
        for (int i = 8; i < 16; i++) {
            low = (low << 8) | (value[i] & 0xFFL);
        }
        return new UUID(high, low);
    }

    /** Milliseconds since the epoch encoded in a v7 identifier, for diagnostics. */
    public static long timestampOf(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("Not a UUIDv7: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> 16;
    }
}
