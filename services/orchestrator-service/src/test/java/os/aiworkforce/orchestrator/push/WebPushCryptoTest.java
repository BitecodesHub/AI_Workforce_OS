// @find: tests for web push crypto, RFC 8291 test vector, aes128gcm, VAPID JWT ES256, P-256 key checks, WebPushCryptoTest
// @what: Unit tests for WebPushCrypto: the RFC 8291 worked example reproduced byte for byte, key checks and the RFC 8292 VAPID token.
package os.aiworkforce.orchestrator.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The encryption every push message gets, held to the RFC's own example, and the token that signs each request. */
class WebPushCryptoTest {

    // RFC 8291, section 5 and appendix A.
    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
            + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
            + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    @Test
    @DisplayName("encrypting the RFC 8291 example with its keys and salt gives exactly the RFC's message")
    void reproducesTheRfcExample() {
        byte[] body = WebPushCrypto.encrypt(
                PLAINTEXT.getBytes(StandardCharsets.US_ASCII),
                WebPushCrypto.fromBase64Url(UA_PUBLIC),
                WebPushCrypto.fromBase64Url(AUTH_SECRET),
                WebPushCrypto.privateKey(WebPushCrypto.fromBase64Url(AS_PRIVATE)),
                WebPushCrypto.publicKey(WebPushCrypto.fromBase64Url(AS_PUBLIC)),
                WebPushCrypto.fromBase64Url(SALT));

        assertThat(WebPushCrypto.base64Url(body)).isEqualTo(BODY);
        // salt (16) + record size (4) + key length (1) + sender key (65) + text, delimiter and tag.
        assertThat(body).hasSize(86 + PLAINTEXT.length() + 1 + 16);
    }

    @Test
    @DisplayName("a message encrypted with a fresh key and salt is read back by the browser's key, and says nothing in clear")
    void freshMessagesDecryptWithTheBrowsersKey() throws Exception {
        byte[] plaintext = "{\"title\":\"An agent needs your approval\"}".getBytes(StandardCharsets.UTF_8);
        byte[] first = WebPushCrypto.encrypt(
                plaintext, WebPushCrypto.fromBase64Url(UA_PUBLIC), WebPushCrypto.fromBase64Url(AUTH_SECRET));
        byte[] second = WebPushCrypto.encrypt(
                plaintext, WebPushCrypto.fromBase64Url(UA_PUBLIC), WebPushCrypto.fromBase64Url(AUTH_SECRET));

        assertThat(first).isNotEqualTo(second);
        assertThat(new String(first, StandardCharsets.ISO_8859_1)).doesNotContain("approval");
        assertThat(PushTestCrypto.decrypt(first, UA_PRIVATE, UA_PUBLIC, AUTH_SECRET)).isEqualTo(plaintext);
        assertThat(PushTestCrypto.decrypt(second, UA_PRIVATE, UA_PUBLIC, AUTH_SECRET)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("a browser key that is not a point on P-256, or the wrong shape, is refused")
    void refusesBadBrowserKeys() {
        byte[] good = WebPushCrypto.fromBase64Url(UA_PUBLIC);
        byte[] offCurve = Arrays.copyOf(good, good.length);
        offCurve[64] ^= 0x01;

        assertThat(WebPushCrypto.publicKey(good)).isInstanceOf(ECPublicKey.class);
        assertThatThrownBy(() -> WebPushCrypto.publicKey(offCurve))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("curve");
        assertThatThrownBy(() -> WebPushCrypto.publicKey(Arrays.copyOf(good, 33)))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] compressed = Arrays.copyOf(good, good.length);
        compressed[0] = 0x02;
        assertThatThrownBy(() -> WebPushCrypto.publicKey(compressed)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushCrypto.encrypt(new byte[1], good, new byte[8]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16 bytes");
        assertThatThrownBy(() -> WebPushCrypto.encrypt(new byte[WebPushCrypto.MAX_PLAINTEXT + 1], good, new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a generated key pair is raw P-256 in base64url, and only its own halves match")
    void generatedPairsMatchThemselvesOnly() {
        WebPushCrypto.RawKeyPair one = WebPushCrypto.generateKeyPair();
        WebPushCrypto.RawKeyPair two = WebPushCrypto.generateKeyPair();

        assertThat(WebPushCrypto.fromBase64Url(one.publicKey())).hasSize(65).startsWith((byte) 0x04);
        assertThat(WebPushCrypto.fromBase64Url(one.privateKey())).hasSize(32);
        assertThat(one.publicKey()).doesNotContain("=", "+", "/");
        assertThat(WebPushCrypto.pairMatches(one.publicKey(), one.privateKey())).isTrue();
        assertThat(WebPushCrypto.pairMatches(AS_PUBLIC, AS_PRIVATE)).isTrue();
        assertThat(WebPushCrypto.pairMatches(one.publicKey(), two.privateKey())).isFalse();
        assertThat(WebPushCrypto.pairMatches("not-a-key", one.privateKey())).isFalse();
        assertThat(one.toString()).doesNotContain(one.privateKey());
    }

    @Test
    @DisplayName("the VAPID token is an ES256 JWT for the push service's origin that the public key verifies")
    void vapidTokenVerifies() throws Exception {
        String token = WebPushCrypto.vapidToken(
                "https://fcm.googleapis.com", 1_800_000_000L, "mailto:no-reply@example.com", AS_PRIVATE);

        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        ObjectMapper json = new ObjectMapper();
        JsonNode header = json.readTree(WebPushCrypto.fromBase64Url(parts[0]));
        JsonNode claims = json.readTree(WebPushCrypto.fromBase64Url(parts[1]));
        assertThat(header.get("alg").asText()).isEqualTo("ES256");
        assertThat(header.get("typ").asText()).isEqualTo("JWT");
        assertThat(claims.get("aud").asText()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.get("exp").asLong()).isEqualTo(1_800_000_000L);
        assertThat(claims.get("sub").asText()).isEqualTo("mailto:no-reply@example.com");

        byte[] signature = WebPushCrypto.fromBase64Url(parts[2]);
        assertThat(signature).hasSize(64);
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(WebPushCrypto.publicKey(WebPushCrypto.fromBase64Url(AS_PUBLIC)));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(signature)).isTrue();

        assertThat(WebPushCrypto.vapidAuthorization(token, AS_PUBLIC)).isEqualTo("vapid t=" + token + ", k=" + AS_PUBLIC);
    }

    @Test
    @DisplayName("the token's audience is the endpoint's origin: scheme, host and any port, never the path")
    void audienceIsTheOrigin() {
        assertThat(WebPushCrypto.audienceOf(URI.create("https://fcm.googleapis.com/fcm/send/abc:def")))
                .isEqualTo("https://fcm.googleapis.com");
        assertThat(WebPushCrypto.audienceOf(URI.create("https://Updates.Push.Services.Mozilla.com:8443/wpush/v2/x")))
                .isEqualTo("https://updates.push.services.mozilla.com:8443");
    }

    @Test
    @DisplayName("raw keys survive a round trip through the JDK's key objects")
    void rawKeysRoundTrip() {
        ECPublicKey publicKey = WebPushCrypto.publicKey(WebPushCrypto.fromBase64Url(UA_PUBLIC));
        ECPrivateKey privateKey = WebPushCrypto.privateKey(WebPushCrypto.fromBase64Url(UA_PRIVATE));

        assertThat(WebPushCrypto.base64Url(WebPushCrypto.rawPublic(publicKey))).isEqualTo(UA_PUBLIC);
        assertThat(WebPushCrypto.base64Url(WebPushCrypto.rawPrivate(privateKey))).isEqualTo(UA_PRIVATE);
        assertThat(WebPushCrypto.fromBase64Url("BTBZMqHH6r4Tts7J/aSIgg==")).isEqualTo(WebPushCrypto.fromBase64Url(AUTH_SECRET));
        assertThatThrownBy(() -> WebPushCrypto.privateKey(new byte[32])).isInstanceOf(IllegalArgumentException.class);
    }
}
