// @find: web push encryption, RFC 8291, aes128gcm, VAPID, RFC 8292, ES256 JWT, P-256 keys, ECDH, HKDF, push message encryption, application server key, push crypto, WebPushCrypto
// @what: The cryptography Web Push needs, written on the JDK alone: P-256 keys in their raw forms, RFC 8291 message encryption and the RFC 8292 VAPID token.
// @flow: Used by VapidKeyStore (key generation and checks), PushSender (encrypt and sign each message) and PushService (subscription key checks)
package os.aiworkforce.orchestrator.push;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push cryptography on the JDK's own providers, with no third-party library.
 *
 * <p>Three things are needed to put a message on somebody's phone. The browser hands over a P-256
 * public key and a 16-byte secret when it subscribes; the message is encrypted to those (RFC 8291,
 * the {@code aes128gcm} content coding), so the push service that carries it can read nothing but
 * its length. And the request to the push service carries a short token signed with this server's
 * own P-256 key (RFC 8292, VAPID), which the browser bound the subscription to, so nobody else can
 * push to it even if they learn its address.
 *
 * <p>Keys travel in their raw forms, base64url without padding, because that is how the browser
 * gives and wants them: a public key is the 65-byte uncompressed point {@code 0x04 || X || Y}, a
 * private key the 32-byte scalar. Every method here is pure apart from {@link #generateKeyPair} and
 * {@link #encrypt(byte[], byte[], byte[])}, which draw random bytes.
 */
public final class WebPushCrypto {

    /** The record size written in every message header; one record always holds a whole notification. */
    static final int RECORD_SIZE = 4096;

    /** The largest notification text this sends: a record less its header, tag and delimiter, with room to spare. */
    public static final int MAX_PLAINTEXT = 3_000;

    private static final int SALT_LENGTH = 16;
    private static final int AUTH_SECRET_LENGTH = 16;
    private static final int RAW_PUBLIC_LENGTH = 65;
    private static final int COORDINATE_LENGTH = 32;
    private static final byte[] KEY_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECParameterSpec P256 = p256();

    private WebPushCrypto() {}

    /** A P-256 key pair in its raw base64url forms: the 65-byte public point and the 32-byte private scalar. */
    public record RawKeyPair(String publicKey, String privateKey) {
        @Override
        public String toString() {
            // The private half never reaches a log line.
            return "RawKeyPair[publicKey=" + publicKey + "]";
        }
    }

    // ---- Encoding ----------------------------------------------------------------------------

    /** URL-safe base64 without padding, the form every Web Push key and token uses. */
    public static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Decodes base64url, with or without padding, and also accepts the standard alphabet some
     * browsers once used.
     *
     * @throws IllegalArgumentException when the text is not base64 at all
     */
    public static byte[] fromBase64Url(String text) {
        String clean = text.strip().replace('+', '-').replace('/', '_');
        while (clean.endsWith("=")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return Base64.getUrlDecoder().decode(clean);
    }

    // ---- Keys --------------------------------------------------------------------------------

    // @find: generate VAPID key pair, new P-256 key
    /** A fresh P-256 key pair, for the server's VAPID identity. Draws random bytes; never throws in a standard JDK. */
    public static RawKeyPair generateKeyPair() {
        KeyPair pair = generateEcKeyPair();
        return new RawKeyPair(
                base64Url(rawPublic((ECPublicKey) pair.getPublic())),
                base64Url(rawPrivate((ECPrivateKey) pair.getPrivate())));
    }

    // A P-256 key pair from the JDK's own generator.
    private static KeyPair generateEcKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 keys are not available in this JVM", e);
        }
    }

    /** The 65-byte uncompressed point of a public key. */
    static byte[] rawPublic(ECPublicKey key) {
        byte[] raw = new byte[RAW_PUBLIC_LENGTH];
        raw[0] = 0x04;
        System.arraycopy(fixed(key.getW().getAffineX()), 0, raw, 1, COORDINATE_LENGTH);
        System.arraycopy(fixed(key.getW().getAffineY()), 0, raw, 1 + COORDINATE_LENGTH, COORDINATE_LENGTH);
        return raw;
    }

    /** The 32-byte scalar of a private key. */
    static byte[] rawPrivate(ECPrivateKey key) {
        return fixed(key.getS());
    }

    // An unsigned big-endian integer as exactly 32 bytes.
    private static byte[] fixed(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == COORDINATE_LENGTH) {
            return bytes;
        }
        byte[] out = new byte[COORDINATE_LENGTH];
        if (bytes.length > COORDINATE_LENGTH) {
            // A leading sign byte.
            System.arraycopy(bytes, bytes.length - COORDINATE_LENGTH, out, 0, COORDINATE_LENGTH);
        } else {
            System.arraycopy(bytes, 0, out, COORDINATE_LENGTH - bytes.length, bytes.length);
        }
        return out;
    }

    // @find: parse browser p256dh key, validate push subscription key
    /**
     * The public key in a raw 65-byte point, checked to lie on P-256.
     *
     * <p>A browser's {@code p256dh} arrives from the client, so it is checked before it is ever used
     * in a key agreement: the right length and form, coordinates inside the field, and the point on
     * the curve. Anything else is refused here, at subscription time.
     *
     * @throws IllegalArgumentException when it is not a valid P-256 point
     */
    public static ECPublicKey publicKey(byte[] raw) {
        if (raw == null || raw.length != RAW_PUBLIC_LENGTH || raw[0] != 0x04) {
            throw new IllegalArgumentException("is not an uncompressed P-256 public key");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 1 + COORDINATE_LENGTH));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 1 + COORDINATE_LENGTH, RAW_PUBLIC_LENGTH));
        if (!onCurve(x, y)) {
            throw new IllegalArgumentException("is not a point on the P-256 curve");
        }
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("is not a usable P-256 public key", e);
        }
    }

    /** The private key for a raw 32-byte scalar. @throws IllegalArgumentException when it is not one */
    static ECPrivateKey privateKey(byte[] raw) {
        if (raw == null || raw.length != COORDINATE_LENGTH) {
            throw new IllegalArgumentException("is not a 32-byte P-256 private key");
        }
        BigInteger s = new BigInteger(1, raw);
        if (s.signum() == 0 || s.compareTo(P256.getOrder()) >= 0) {
            throw new IllegalArgumentException("is outside the P-256 key range");
        }
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(s, P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("is not a usable P-256 private key", e);
        }
    }

    // y^2 = x^3 + ax + b (mod p), with both coordinates inside the field.
    private static boolean onCurve(BigInteger x, BigInteger y) {
        EllipticCurve curve = P256.getCurve();
        BigInteger p = ((ECFieldFp) curve.getField()).getP();
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            return false;
        }
        BigInteger left = y.modPow(BigInteger.TWO, p);
        BigInteger right = x.modPow(BigInteger.valueOf(3), p).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);
        return left.equals(right);
    }

    // @find: check VAPID key pair matches
    /**
     * Whether a raw public and private key belong together, tested by signing and verifying a
     * message, so a pair supplied through the environment with one half mistyped is caught at
     * start rather than by every push service refusing every message.
     */
    public static boolean pairMatches(String publicKey, String privateKey) {
        try {
            byte[] probe = "aiwos-vapid-check".getBytes(StandardCharsets.US_ASCII);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey(fromBase64Url(privateKey)));
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey(fromBase64Url(publicKey)));
            verifier.update(probe);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    // ---- RFC 8291 message encryption -----------------------------------------------------------

    // @find: encrypt push message, aes128gcm body
    /**
     * Encrypts one notification for one subscription (RFC 8291), with a fresh sender key and salt.
     *
     * @param plaintext the notification, at most {@value #MAX_PLAINTEXT} bytes
     * @param uaPublic the subscription's {@code p256dh}: the browser's raw public key
     * @param authSecret the subscription's {@code auth}: 16 bytes
     * @return the request body for the push service: header and ciphertext
     * @throws IllegalArgumentException when a key or the secret is malformed, or the text too long
     */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
        KeyPair sender = generateEcKeyPair();
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return encrypt(
                plaintext, uaPublic, authSecret, (ECPrivateKey) sender.getPrivate(), (ECPublicKey) sender.getPublic(), salt);
    }

    /**
     * The same, with the sender key and salt given, so the RFC 8291 example can be reproduced exactly.
     * Pure; for tests and for {@link #encrypt(byte[], byte[], byte[])}.
     */
    static byte[] encrypt(
            byte[] plaintext,
            byte[] uaPublic,
            byte[] authSecret,
            ECPrivateKey asPrivate,
            ECPublicKey asPublicKey,
            byte[] salt) {
        if (plaintext.length > MAX_PLAINTEXT) {
            throw new IllegalArgumentException("A push message is limited to " + MAX_PLAINTEXT + " bytes");
        }
        if (authSecret == null || authSecret.length != AUTH_SECRET_LENGTH) {
            throw new IllegalArgumentException("The subscription's auth secret must be 16 bytes");
        }
        if (salt.length != SALT_LENGTH) {
            throw new IllegalArgumentException("The salt must be 16 bytes");
        }
        ECPublicKey ua = publicKey(uaPublic);
        byte[] asPublic = rawPublic(asPublicKey);
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(asPrivate);
            agreement.doPhase(ua, true);
            byte[] ecdhSecret = agreement.generateSecret();

            // HKDF-Extract(auth_secret, ecdh_secret), then expand with "WebPush: info".
            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] keyInfo = concat(KEY_INFO_PREFIX, uaPublic, asPublic);
            byte[] ikm = expand(prkKey, keyInfo, 32);
            byte[] prk = hmac(salt, ikm);
            byte[] cek = expand(prk, CEK_INFO, 16);
            byte[] nonce = expand(prk, NONCE_INFO, 12);

            // One record, the last: the text, then the 0x02 delimiter, no padding.
            byte[] padded = Arrays.copyOf(plaintext, plaintext.length + 1);
            padded[plaintext.length] = 0x02;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(padded);

            ByteBuffer header = ByteBuffer.allocate(SALT_LENGTH + 4 + 1 + asPublic.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("A push message could not be encrypted", e);
        }
    }

    // HKDF-Expand for a length up to one hash block: T(1) = HMAC(prk, info || 0x01), cut to length.
    private static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        byte[] block = hmac(prk, concat(info, new byte[] {0x01}));
        return Arrays.copyOf(block, length);
    }

    // HMAC-SHA-256 of data under key.
    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    // The arrays one after another.
    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    // ---- RFC 8292 VAPID ----------------------------------------------------------------------

    // @find: VAPID token, sign push request, ES256 JWT
    /**
     * The VAPID token for one push service (RFC 8292): an ES256 JSON Web Token naming the push
     * service's origin as audience, an expiry and a contact address.
     *
     * @param audience the push service origin, for example {@code https://fcm.googleapis.com}
     * @param expiresAtEpochSeconds when the token stops being valid; push services refuse more than 24 hours ahead
     * @param subject a {@code mailto:} or {@code https:} contact for the push service's operator
     * @param privateKey this server's raw private key, base64url
     * @return the compact token
     */
    public static String vapidToken(String audience, long expiresAtEpochSeconds, String subject, String privateKey) {
        String header = base64Url("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = base64Url(("{\"aud\":" + jsonString(audience) + ",\"exp\":" + expiresAtEpochSeconds
                        + ",\"sub\":" + jsonString(subject) + "}")
                .getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        try {
            // IEEE P1363 form: R || S, 64 bytes, as JWS ES256 requires (not the DER the default emits).
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(privateKey(fromBase64Url(privateKey)));
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The VAPID token could not be signed", e);
        }
    }

    /** The {@code Authorization} header value for a VAPID token and the server's raw public key. */
    public static String vapidAuthorization(String token, String publicKey) {
        return "vapid t=" + token + ", k=" + publicKey;
    }

    /** The origin a push endpoint belongs to, which is a VAPID token's audience: scheme, host and any port. */
    public static String audienceOf(URI endpoint) {
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        return endpoint.getPort() == -1 ? scheme + "://" + host : scheme + "://" + host + ":" + endpoint.getPort();
    }

    // A JSON string literal for plain text: quotes, backslashes and control characters escaped.
    private static String jsonString(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    // The P-256 domain parameters, read once from the JDK.
    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is not available in this JVM", e);
        }
    }
}
