// @find: model router, LLM, model providers, Bedrock credentials, AWS access key, session token, Bedrock API key, region, credential parsing, error sentences, BedrockCredentials
// @what: Parses and validates what a workspace stores to reach Amazon Bedrock.
// @flow: Used by BedrockProvider and the integrations console.
package os.aiworkforce.llm.bedrock;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What a workspace stores to reach Amazon Bedrock: either an AWS access key (with an optional
 * session token, for temporary credentials) or a Bedrock API key, which is a bearer token, and the
 * region to call.
 *
 * <p>Stored as one encrypted JSON credential, like every other multi-field credential:
 *
 * <pre>{"type":"access_key","accessKeyId":"AKIA…","secretAccessKey":"…","sessionToken":"…","region":"us-east-1"}
 * {"type":"api_key","apiKey":"ABSK…","region":"eu-west-1"}</pre>
 *
 * <p>Two older shapes are still read: {@code accessKeyId:secretAccessKey}, and a bare API key.
 * Neither names a region, so the provider's own region list is used for them.
 *
 * <p>{@link #toString()} never prints a secret. Parse failures say what is wrong in plain words
 * and never quote the value.
 */
public record BedrockCredentials(
        String accessKeyId, String secretAccessKey, String sessionToken, String apiKey, String region) {

    public static final String DEFAULT_REGION = "us-east-1";

    /** The credential kind the console stores this under. */
    public static final String CREDENTIAL_KIND = "aws_bedrock";

    /** The regions Bedrock runs in, as the console offers them. Any well-formed region is accepted. */
    public static final List<String> REGIONS = List.of(
            "us-east-1",
            "us-east-2",
            "us-west-1",
            "us-west-2",
            "ca-central-1",
            "sa-east-1",
            "eu-central-1",
            "eu-central-2",
            "eu-west-1",
            "eu-west-2",
            "eu-west-3",
            "eu-north-1",
            "eu-south-1",
            "eu-south-2",
            "ap-south-1",
            "ap-south-2",
            "ap-southeast-1",
            "ap-southeast-2",
            "ap-northeast-1",
            "ap-northeast-2",
            "ap-northeast-3",
            "us-gov-west-1",
            "us-gov-east-1");

    private static final Pattern REGION = Pattern.compile("^[a-z]{2}(-gov|-iso[a-z]?)?-[a-z]+-\\d{1,2}$");
    private static final Pattern ACCESS_KEY_ID = Pattern.compile("^[A-Z0-9]{16,128}$");
    private static final Pattern NO_SPACES = Pattern.compile("^\\S+$");
    private static final ObjectMapper JSON = new ObjectMapper();

    public BedrockCredentials {
        accessKeyId = blankToNull(accessKeyId);
        secretAccessKey = blankToNull(secretAccessKey);
        sessionToken = blankToNull(sessionToken);
        apiKey = blankToNull(apiKey);
        region = blankToNull(region);
    }

    /** A credential that could not be read. The message is safe to show: it never quotes the value. */
    public static final class Invalid extends IllegalArgumentException {
        private final String field;

        public Invalid(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    public boolean usesApiKey() {
        return apiKey != null;
    }

    /** The region to call, or the default when the credential names none. */
    public String regionOrDefault() {
        return region == null ? DEFAULT_REGION : region;
    }

    /**
     * Reads a stored or pasted credential.
     *
     * @throws Invalid with a plain sentence when a field is missing or malformed
     */
    public static BedrockCredentials parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new Invalid("value", "Enter your AWS credentials or a Bedrock API key.");
        }
        String text = raw.strip();
        if (!text.startsWith("{")) {
            return legacy(text);
        }
        JsonNode node;
        try {
            node = JSON.readTree(text);
        } catch (Exception e) {
            throw new Invalid("value", "The Bedrock credentials could not be read. Enter them again.");
        }
        if (node == null || !node.isObject()) {
            throw new Invalid("value", "The Bedrock credentials could not be read. Enter them again.");
        }
        String type = field(node, "type");
        String apiKey = field(node, "apiKey");
        String accessKeyId = field(node, "accessKeyId");
        String secret = field(node, "secretAccessKey");
        String token = field(node, "sessionToken");
        String region = field(node, "region");
        boolean wantsApiKey = type != null ? type.equalsIgnoreCase("api_key") : apiKey != null;

        BedrockCredentials parsed = wantsApiKey
                ? new BedrockCredentials(null, null, null, apiKey, region)
                : new BedrockCredentials(accessKeyId, secret, token, null, region);
        parsed.validate();
        return parsed;
    }

    private static BedrockCredentials legacy(String text) {
        int colon = text.indexOf(':');
        BedrockCredentials parsed = colon > 0 && ACCESS_KEY_ID.matcher(text.substring(0, colon)).matches()
                ? new BedrockCredentials(text.substring(0, colon), text.substring(colon + 1), null, null, null)
                : new BedrockCredentials(null, null, null, text, null);
        parsed.validate();
        return parsed;
    }

    /** Checks each field's shape, in the order the form shows them. */
    public void validate() {
        if (usesApiKey()) {
            if (!NO_SPACES.matcher(apiKey).matches() || apiKey.length() < 20) {
                throw new Invalid(
                        "apiKey", "That does not look like a Bedrock API key. Copy it again, with nothing around it.");
            }
        } else {
            if (accessKeyId == null) {
                throw new Invalid("accessKeyId", "Enter the access key ID, or use a Bedrock API key instead.");
            }
            if (!ACCESS_KEY_ID.matcher(accessKeyId).matches()) {
                throw new Invalid(
                        "accessKeyId",
                        "An access key ID is capital letters and digits, usually 20 of them, starting AKIA or ASIA.");
            }
            if (secretAccessKey == null) {
                throw new Invalid("secretAccessKey", "Enter the secret access key that came with the access key ID.");
            }
            if (!NO_SPACES.matcher(secretAccessKey).matches() || secretAccessKey.length() < 16) {
                throw new Invalid(
                        "secretAccessKey",
                        "That does not look like a secret access key. Copy it again, with no spaces or line breaks.");
            }
            if (sessionToken != null && !NO_SPACES.matcher(sessionToken).matches()) {
                throw new Invalid("sessionToken", "A session token has no spaces or line breaks. Copy it again.");
            }
            if (accessKeyId.startsWith("ASIA") && sessionToken == null) {
                throw new Invalid(
                        "sessionToken",
                        "Access key IDs starting ASIA are temporary and need their session token as well.");
            }
        }
        if (region != null && !REGION.matcher(region).matches()) {
            throw new Invalid("region", "Choose an AWS region, such as us-east-1.");
        }
    }

    /** The JSON form that is stored. */
    public String toJson() {
        ObjectNode node = JSON.createObjectNode();
        if (usesApiKey()) {
            node.put("type", "api_key");
            node.put("apiKey", apiKey);
        } else {
            node.put("type", "access_key");
            node.put("accessKeyId", accessKeyId);
            node.put("secretAccessKey", secretAccessKey);
            if (sessionToken != null) {
                node.put("sessionToken", sessionToken);
            }
        }
        node.put("region", regionOrDefault());
        return node.toString();
    }

    /**
     * The credentials the process was started with, for a deployment that keeps them out of the
     * database: {@code AWS_BEARER_TOKEN_BEDROCK}, or {@code AWS_ACCESS_KEY_ID} with
     * {@code AWS_SECRET_ACCESS_KEY} (and {@code AWS_SESSION_TOKEN}); region from {@code AWS_REGION}.
     * Null when none are set.
     */
    public static BedrockCredentials fromEnvironment(Map<String, String> env) {
        String region = env.getOrDefault("AWS_REGION", env.get("AWS_DEFAULT_REGION"));
        String bearer = env.get("AWS_BEARER_TOKEN_BEDROCK");
        if (bearer != null && !bearer.isBlank()) {
            return new BedrockCredentials(null, null, null, bearer, region);
        }
        String id = env.get("AWS_ACCESS_KEY_ID");
        String secret = env.get("AWS_SECRET_ACCESS_KEY");
        if (id != null && !id.isBlank() && secret != null && !secret.isBlank()) {
            return new BedrockCredentials(id, secret, env.get("AWS_SESSION_TOKEN"), null, region);
        }
        return null;
    }

    /**
     * The headers that authenticate one request: a bearer token for an API key, otherwise a SigV4
     * signature over exactly these bytes.
     *
     * @param contentType sent and signed when the request has a body; null for a GET
     */
    public Map<String, String> authHeaders(String method, URI uri, byte[] body, String contentType, Instant now) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        if (usesApiKey()) {
            headers.put("Authorization", "Bearer " + apiKey);
            return headers;
        }
        Map<String, String> signed = AwsSigV4.sign(
                method,
                uri,
                headers,
                body,
                accessKeyId,
                secretAccessKey,
                sessionToken,
                regionOrDefault(),
                "bedrock",
                now,
                true);
        headers.putAll(signed);
        return headers;
    }

    /** The runtime endpoint (chat and embeddings) for a region. */
    public static String runtimeEndpoint(String region) {
        return "https://bedrock-runtime." + region + ".amazonaws.com";
    }

    /** The control-plane endpoint (model lists) for a region. */
    public static String controlEndpoint(String region) {
        return "https://bedrock." + region + ".amazonaws.com";
    }

    @Override
    public String toString() {
        return "BedrockCredentials[" + (usesApiKey() ? "api key" : "access key") + ", region=" + region + "]";
    }

    private static String field(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isTextual() ? blankToNull(value.asText()) : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
