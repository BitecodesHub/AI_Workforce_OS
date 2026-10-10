// @find: AWS default credential chain, instance profile, IMDSv2, EC2 metadata, instance role, container credentials, ECS task role, temporary credentials, token refresh, AIWOS_BEDROCK_USE_DEFAULT_CREDENTIALS, AwsDefaultCredentials
// @what: Finds AWS credentials the way the AWS SDKs do when nothing is stored: environment, container endpoint, then the EC2 instance role over IMDSv2, refreshed before they expire.
// @flow: Used by BedrockProvider when a workspace has no Bedrock credential and AIWOS_BEDROCK_USE_DEFAULT_CREDENTIALS=true.
package os.aiworkforce.llm.bedrock;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The AWS default credential chain, for a deployment that keeps AWS keys out of the database and
 * out of its environment file: an EC2 instance with an IAM role (instance profile), or a container
 * with a task role.
 *
 * <p>Looked up in the order the AWS SDKs use:
 *
 * <ol>
 *   <li>{@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY} (/ {@code AWS_SESSION_TOKEN}), or
 *       {@code AWS_BEARER_TOKEN_BEDROCK};
 *   <li>the container credentials endpoint ({@code AWS_CONTAINER_CREDENTIALS_FULL_URI} or
 *       {@code AWS_CONTAINER_CREDENTIALS_RELATIVE_URI}), as ECS and EKS Pod Identity provide;
 *   <li>the EC2 instance metadata service, version 2 only: a session token is fetched with a PUT
 *       first, so this works on an instance that requires IMDSv2 (the default on Amazon Linux 2023).
 *       From inside a Docker container the instance's hop limit must be at least 2.
 * </ol>
 *
 * <p>Temporary credentials are cached and fetched again five minutes before they expire. If that
 * refresh fails while the cached ones are still valid, the cached ones are used and the refresh is
 * tried again on the next call.
 *
 * <p>Nothing here logs a key, a token or a response body. {@link BedrockCredentials#toString()}
 * prints neither.
 */
public final class AwsDefaultCredentials {

    private static final Logger log = LoggerFactory.getLogger(AwsDefaultCredentials.class);

    /** The environment switch that turns the chain on. */
    public static final String SWITCH = "AIWOS_BEDROCK_USE_DEFAULT_CREDENTIALS";

    /** The region Bedrock is called in when the credentials come from the chain. */
    public static final String REGION = "AIWOS_BEDROCK_REGION";

    static final String DEFAULT_IMDS_ENDPOINT = "http://169.254.169.254";
    static final String ECS_HOST = "http://169.254.170.2";

    /** How long before expiry cached credentials are replaced. */
    static final Duration REFRESH_BEFORE = Duration.ofMinutes(5);

    /** How long the IMDSv2 session token is asked to live. */
    private static final int IMDS_TOKEN_TTL_SECONDS = 21_600;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The plain sentence a caller can show. It never names a value. */
    public static final class Unavailable extends RuntimeException {
        private final boolean transientFailure;

        Unavailable(String message, boolean transientFailure) {
            super(message);
            this.transientFailure = transientFailure;
        }

        /** True when trying again later may work (the metadata service did not answer). */
        public boolean isTransient() {
            return transientFailure;
        }
    }

    /** The two HTTP calls the chain makes, behind an interface so a test needs no metadata service. */
    interface Http {
        record Response(int status, String body) {}

        Response send(String method, URI uri, Map<String, String> headers) throws IOException;
    }

    private final Map<String, String> env;
    private final Http http;
    private final Clock clock;

    private BedrockCredentials cached;
    private Instant cachedExpiry;

    AwsDefaultCredentials(Map<String, String> env, Http http, Clock clock) {
        this.env = env;
        this.http = http;
        this.clock = clock;
    }

    /** The chain as the process environment describes it, on the JDK's HTTP client with short timeouts. */
    public static AwsDefaultCredentials fromEnvironment(Map<String, String> env, Clock clock) {
        return new AwsDefaultCredentials(env, jdkHttp(), clock);
    }

    /** Whether the environment turns the chain on. */
    public static boolean enabled(Map<String, String> env) {
        String value = env.get(SWITCH);
        return value != null && (value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes"));
    }

    /**
     * The region the chain's credentials call Bedrock in: {@code AIWOS_BEDROCK_REGION}, then
     * {@code AWS_REGION}, then {@code AWS_DEFAULT_REGION}; null when none is set.
     */
    public static String region(Map<String, String> env) {
        for (String name : new String[] {REGION, "AWS_REGION", "AWS_DEFAULT_REGION"}) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    /**
     * Credentials for one call, in {@link #region(Map)}.
     *
     * @throws Unavailable when no source in the chain has any
     */
    public synchronized BedrockCredentials resolve() {
        String region = region(env);
        BedrockCredentials fromEnv = BedrockCredentials.fromEnvironment(env);
        if (fromEnv != null) {
            return withRegion(fromEnv, region);
        }
        Instant now = clock.instant();
        if (cached != null && (cachedExpiry == null || now.isBefore(cachedExpiry.minus(REFRESH_BEFORE)))) {
            return withRegion(cached, region);
        }
        try {
            fetch();
        } catch (Unavailable e) {
            if (cached != null && cachedExpiry != null && now.isBefore(cachedExpiry)) {
                log.warn("Refreshing AWS role credentials failed ({}); using the current ones until they expire", e.getMessage());
                return withRegion(cached, region);
            }
            throw e;
        }
        return withRegion(cached, region);
    }

    /** When the cached credentials expire; null when none are cached or they do not expire. */
    synchronized Instant expiry() {
        return cachedExpiry;
    }

    private void fetch() {
        String full = env.get("AWS_CONTAINER_CREDENTIALS_FULL_URI");
        String relative = env.get("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI");
        if (notBlank(full) || notBlank(relative)) {
            URI uri = URI.create(notBlank(full) ? full.strip() : ECS_HOST + relative.strip());
            Map<String, String> headers = new LinkedHashMap<>();
            String token = env.get("AWS_CONTAINER_AUTHORIZATION_TOKEN");
            if (notBlank(token)) {
                headers.put("Authorization", token.strip());
            }
            store(read(get(uri, headers, "the container credentials endpoint")), "container role");
            return;
        }
        if ("true".equalsIgnoreCase(env.getOrDefault("AWS_EC2_METADATA_DISABLED", ""))) {
            throw new Unavailable("No AWS credentials were found: no keys are set and the instance metadata"
                    + " service is turned off.", false);
        }
        String base = stripSlash(env.getOrDefault("AWS_EC2_METADATA_SERVICE_ENDPOINT", DEFAULT_IMDS_ENDPOINT));
        String token = imdsToken(base);
        Map<String, String> headers = Map.of("X-aws-ec2-metadata-token", token);
        Http.Response roles = get(URI.create(base + "/latest/meta-data/iam/security-credentials/"), headers,
                "the instance metadata service");
        String role = roles.body() == null ? "" : roles.body().strip().split("\\R", 2)[0].strip();
        if (role.isEmpty()) {
            throw new Unavailable("This server has no IAM role attached, so it has no AWS credentials.", false);
        }
        Http.Response body = get(URI.create(base + "/latest/meta-data/iam/security-credentials/" + role), headers,
                "the instance metadata service");
        store(read(body), "instance role");
    }

    private String imdsToken(String base) {
        Http.Response response;
        try {
            response = http.send(
                    "PUT",
                    URI.create(base + "/latest/api/token"),
                    Map.of("X-aws-ec2-metadata-token-ttl-seconds", Integer.toString(IMDS_TOKEN_TTL_SECONDS)));
        } catch (IOException e) {
            throw new Unavailable("The instance metadata service did not answer. This server may not be on"
                    + " EC2, or its hop limit is 1 while the app runs in a container.", true);
        }
        if (response.status() != 200 || response.body() == null || response.body().isBlank()) {
            throw new Unavailable("The instance metadata service refused a session token (HTTP "
                    + response.status() + ").", response.status() >= 500);
        }
        return response.body().strip();
    }

    private Http.Response get(URI uri, Map<String, String> headers, String what) {
        Http.Response response;
        try {
            response = http.send("GET", uri, headers);
        } catch (IOException e) {
            throw new Unavailable("Could not reach " + what + " for AWS credentials.", true);
        }
        if (response.status() == 404) {
            throw new Unavailable("This server has no IAM role attached, so it has no AWS credentials.", false);
        }
        if (response.status() != 200) {
            throw new Unavailable("Reading AWS credentials from " + what + " failed (HTTP " + response.status() + ").",
                    response.status() >= 500);
        }
        return response;
    }

    private static JsonNode read(Http.Response response) {
        try {
            JsonNode node = JSON.readTree(response.body());
            if (node == null || !node.isObject()) {
                throw new Unavailable("The AWS credentials endpoint answered with something unreadable.", true);
            }
            return node;
        } catch (IOException e) {
            throw new Unavailable("The AWS credentials endpoint answered with something unreadable.", true);
        }
    }

    private void store(JsonNode node, String source) {
        String code = node.path("Code").asText("Success");
        if (!code.isEmpty() && !code.equals("Success")) {
            throw new Unavailable("The " + source + " has no usable credentials right now (" + code + ").", true);
        }
        String id = node.path("AccessKeyId").asText("");
        String secret = node.path("SecretAccessKey").asText("");
        String token = node.path("Token").asText("");
        if (id.isEmpty() || secret.isEmpty()) {
            throw new Unavailable("The " + source + " answered without credentials.", true);
        }
        Instant expiry = null;
        String expiration = node.path("Expiration").asText("");
        if (!expiration.isEmpty()) {
            try {
                expiry = Instant.parse(expiration);
            } catch (DateTimeParseException e) {
                // Unknown expiry: treat as short-lived, so it is fetched again soon.
                expiry = clock.instant().plus(REFRESH_BEFORE.multipliedBy(2));
            }
        }
        cached = new BedrockCredentials(id, secret, token.isEmpty() ? null : token, null, null);
        cachedExpiry = expiry;
        log.info("Using AWS credentials from the {}{}", source, expiry == null ? "" : ", valid until " + expiry);
    }

    private static BedrockCredentials withRegion(BedrockCredentials credentials, String region) {
        if (region == null || region.equals(credentials.region())) {
            return credentials;
        }
        return new BedrockCredentials(
                credentials.accessKeyId(),
                credentials.secretAccessKey(),
                credentials.sessionToken(),
                credentials.apiKey(),
                region);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String stripSlash(String value) {
        String out = value.strip();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static Http jdkHttp() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (method, uri, headers) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3));
            headers.forEach(request::header);
            request.method(method, HttpRequest.BodyPublishers.noBody());
            try {
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                return new Http.Response(response.statusCode(), response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        };
    }
}
