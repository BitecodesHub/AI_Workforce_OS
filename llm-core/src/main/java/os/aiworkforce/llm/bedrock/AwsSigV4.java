// @find: model router, LLM, model providers, AWS Signature V4, SigV4 signing, Bedrock request signing, AwsSigV4
// @what: Signs Bedrock requests with AWS Signature Version 4 without the AWS SDK.
// @flow: Used by BedrockProvider.
package os.aiworkforce.llm.bedrock;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4, for the handful of Bedrock calls this platform makes.
 *
 * <p>Written here rather than taken from the AWS SDK so that every provider goes through the same
 * WebClient stack (timeouts, error mapping, stub servers in tests), and so that a Bedrock API key,
 * which is a bearer token rather than a signature, is just another header. The algorithm is the
 * published one; {@code AwsSigV4Test} checks it against AWS's own test vectors and against the
 * SDK's signer.
 *
 * <p>Nothing here logs. The secret only ever enters an HMAC.
 */
public final class AwsSigV4 {

    public static final String ALGORITHM = "AWS4-HMAC-SHA256";

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SHORT_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AwsSigV4() {}

    /**
     * Signs one request.
     *
     * @param method the HTTP method, upper case
     * @param uri the full URI, with its path and query already percent-encoded as they will be sent
     * @param headers headers that will be sent and should be signed (content type, for example);
     *     {@code host} and {@code x-amz-date} are added here
     * @param body the exact bytes that will be sent; empty for a GET
     * @param service the signing name, {@code bedrock} for both Bedrock endpoints
     * @param doubleEncodePath true for every service but S3: the canonical path encodes the
     *     already-encoded path once more
     * @return the headers to add to the request: {@code Authorization}, {@code X-Amz-Date} and,
     *     with temporary credentials, {@code X-Amz-Security-Token}
     */
    public static Map<String, String> sign(
            String method,
            URI uri,
            Map<String, String> headers,
            byte[] body,
            String accessKeyId,
            String secretAccessKey,
            String sessionToken,
            String region,
            String service,
            Instant now,
            boolean doubleEncodePath) {
        String amzDate = AMZ_DATE.format(now);
        String date = SHORT_DATE.format(now);

        Map<String, String> signed = new TreeMap<>();
        headers.forEach((name, value) -> signed.put(name.toLowerCase(Locale.ROOT), normaliseValue(value)));
        signed.put("host", hostOf(uri));
        signed.put("x-amz-date", amzDate);
        if (sessionToken != null && !sessionToken.isBlank()) {
            signed.put("x-amz-security-token", sessionToken.strip());
        }

        String signedHeaders = String.join(";", signed.keySet());
        String canonical = canonicalRequest(
                method, canonicalPath(uri, doubleEncodePath), canonicalQuery(uri), signed, signedHeaders, body);
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonical));
        byte[] key = signingKey(secretAccessKey, date, region, service);
        String signature = hex(hmac(key, stringToSign));

        Map<String, String> out = new LinkedHashMap<>();
        out.put("X-Amz-Date", amzDate);
        if (sessionToken != null && !sessionToken.isBlank()) {
            out.put("X-Amz-Security-Token", sessionToken.strip());
        }
        out.put(
                "Authorization",
                ALGORITHM + " Credential=" + accessKeyId + "/" + scope + ", SignedHeaders=" + signedHeaders
                        + ", Signature=" + signature);
        return out;
    }

    static String canonicalRequest(
            String method,
            String path,
            String query,
            Map<String, String> sortedHeaders,
            String signedHeaders,
            byte[] body) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(method).append('\n').append(path).append('\n').append(query).append('\n');
        sortedHeaders.forEach((name, value) -> canonical.append(name).append(':').append(value).append('\n'));
        canonical.append('\n').append(signedHeaders).append('\n').append(hex(sha256(body == null ? new byte[0] : body)));
        return canonical.toString();
    }

    /** The derived key: the secret, HMAC'd through date, region, service and the terminator. */
    static byte[] signingKey(String secret, String date, String region, String service) {
        byte[] kDate = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        return hmac(kService, "aws4_request");
    }

    static String canonicalPath(URI uri, boolean doubleEncode) {
        String raw = uri.getRawPath();
        if (raw == null || raw.isEmpty()) {
            return "/";
        }
        if (!doubleEncode) {
            return raw;
        }
        StringBuilder out = new StringBuilder();
        int start = 0;
        while (start <= raw.length()) {
            int slash = raw.indexOf('/', start);
            int end = slash < 0 ? raw.length() : slash;
            out.append(encode(raw.substring(start, end)));
            if (slash < 0) {
                break;
            }
            out.append('/');
            start = slash + 1;
        }
        return out.toString();
    }

    static String canonicalQuery(URI uri) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        List<String[]> pairs = new ArrayList<>();
        for (String part : raw.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String name = eq < 0 ? part : part.substring(0, eq);
            String value = eq < 0 ? "" : part.substring(eq + 1);
            pairs.add(new String[] {encode(decode(name)), encode(decode(value))});
        }
        pairs.sort((a, b) -> {
            int byName = a[0].compareTo(b[0]);
            return byName != 0 ? byName : a[1].compareTo(b[1]);
        });
        StringBuilder out = new StringBuilder();
        for (String[] pair : pairs) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(pair[0]).append('=').append(pair[1]);
        }
        return out.toString();
    }

    /** The Host header as the client will send it: the port only when it is not the scheme's default. */
    public static String hostOf(URI uri) {
        String host = uri.getHost();
        int port = uri.getPort();
        boolean defaultPort = port == -1
                || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443)
                || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80);
        return defaultPort ? host : host + ":" + port;
    }

    /** RFC 3986 percent-encoding, as SigV4 defines it: everything but unreserved characters. */
    public static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    private static String decode(String value) {
        // '+' is kept as a literal plus: SigV4 query values are percent-encoded, never form-encoded.
        return java.net.URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /** Trimmed, with runs of spaces collapsed to one, as the canonical form requires. */
    private static String normaliseValue(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    static byte[] sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
