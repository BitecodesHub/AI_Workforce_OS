// @find: test helper push decrypt, browser side of RFC 8291, read a push message in tests, PushTestCrypto
// @what: Test helper that plays the browser: decrypts an aes128gcm push body with the browser's private key, written from RFC 8291 independently of the sender.
package os.aiworkforce.orchestrator.push;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** What a browser does with a push message, so a test can read what was really sent. */
final class PushTestCrypto {

    private PushTestCrypto() {}

    /**
     * The plaintext of an {@code aes128gcm} body (RFC 8291, receiver side): header parsed, keys
     * derived from the browser's private key and secret, the one record decrypted and its padding
     * removed.
     */
    static byte[] decrypt(byte[] body, String uaPrivate, String uaPublic, String authSecret) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        int recordSize = in.getInt();
        int idLength = in.get() & 0xFF;
        byte[] asPublic = new byte[idLength];
        in.get(asPublic);
        byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);
        if (recordSize < ciphertext.length) {
            throw new IllegalStateException("more than one record");
        }

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(WebPushCrypto.privateKey(WebPushCrypto.fromBase64Url(uaPrivate)));
        agreement.doPhase(WebPushCrypto.publicKey(asPublic), true);
        byte[] ecdh = agreement.generateSecret();

        byte[] uaRaw = WebPushCrypto.fromBase64Url(uaPublic);
        byte[] keyInfo = join("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaRaw, asPublic, new byte[] {1});
        byte[] ikm = hmac(hmac(WebPushCrypto.fromBase64Url(authSecret), ecdh), keyInfo);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(
                hmac(prk, join("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 16);
        byte[] nonce = Arrays.copyOf(
                hmac(prk, join("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(ciphertext);
        int end = padded.length - 1;
        while (end >= 0 && padded[end] == 0) {
            end--;
        }
        if (end < 0 || padded[end] != 0x02) {
            throw new IllegalStateException("the last record must end with the 0x02 delimiter");
        }
        return Arrays.copyOf(padded, end);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] join(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        ByteBuffer out = ByteBuffer.allocate(length);
        for (byte[] part : parts) {
            out.put(part);
        }
        return out.array();
    }
}
