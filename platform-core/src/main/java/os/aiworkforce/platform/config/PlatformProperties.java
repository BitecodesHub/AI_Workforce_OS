package os.aiworkforce.platform.config;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.nimbusds.jose.jwk.Curve;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Everything a process needs to know at startup, in one validated tree.
 *
 * <p>No other class reads {@code System.getenv} or {@code @Value}. Each field carries a default
 * that is correct for local development, so a service starts with no environment file present,
 * and production supplies overrides as {@code AIWOS_*} environment variables.
 *
 * <p>A value an operator should be able to change <em>without a redeployment</em> does not belong
 * here. It belongs in the database-backed runtime configuration, which this tree only tells the
 * process how to reach and how long to cache.
 */
@Validated
@ConfigurationProperties(prefix = "aiwos")
public record PlatformProperties(
        @DefaultValue("local") Environment environment,
        @NotBlank @DefaultValue("unknown") String serviceName,
        @DefaultValue("0.1.0") String serviceVersion,
        @Valid @DefaultValue Security security,
        @Valid @DefaultValue Http http,
        @Valid @DefaultValue RateLimit rateLimit,
        @Valid @DefaultValue Events events,
        @Valid @DefaultValue Resilience resilience,
        @Valid @DefaultValue Observability observability,
        @Valid @DefaultValue RuntimeConfig runtimeConfig,
        @Valid @DefaultValue Services services) {

    public enum Environment {
        LOCAL,
        TEST,
        STAGING,
        PRODUCTION;

        public boolean isDeployed() {
            return this == STAGING || this == PRODUCTION;
        }
    }

    /**
     * Token signing and verification, password hashing and envelope encryption.
     *
     * <p>The development fallbacks below exist so the stack boots with nothing configured.
     * {@link PlatformProperties#validateForEnvironment()} refuses them outside local and test, so
     * a placeholder key cannot reach a deployed environment quietly.
     */
    public record Security(
            @DefaultValue("aiwos") String issuer,
            @DefaultValue("aiwos-api") String audience,
            @DefaultValue("http://identity:8081/.well-known/jwks.json") String jwksUri,
            @DefaultValue("PT10M") Duration jwksCacheTtl,
            @DefaultValue("PT5M") Duration jwksRefreshCooldown,
            String activeKeyId,
            String signingPrivateKeyPem,
            @DefaultValue("PT5M") Duration accessTokenTtl,
            @DefaultValue("P30D") Duration refreshTokenTtl,
            @DefaultValue("PT60S") Duration internalTokenTtl,
            @DefaultValue("PT30S") Duration clockSkewLeeway,
            @DefaultValue("insecure-local-development-secret-change-me") String developmentSecret,
            /*
             * Proves a caller is a platform service on the one endpoint that cannot require a
             * token: the one that issues them. Paired with a network policy rather than relied
             * on alone, and refused outside local and test while it holds its placeholder.
             */
            @DefaultValue("insecure-local-internal-secret-change-me") String internalServiceSecret,
            @Valid @DefaultValue Argon2 argon2,
            @Valid @DefaultValue Encryption encryption,
            @DefaultValue("12") @Min(8) int passwordMinLength,
            @DefaultValue("8") @Positive int maxFailedLogins,
            @DefaultValue("PT15M") Duration lockoutDuration,
            @DefaultValue("true") boolean requireEmailVerification) {

        private static final String PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----";
        private static final String PKCS8_FOOTER = "-----END PRIVATE KEY-----";

        /**
         * The configured signing key, decoded, or {@code null} when none is configured.
         *
         * <p>Decoded here rather than by identity alone so the startup validator can refuse a key
         * that will not load before any service serves traffic. Only an unencrypted PKCS#8 P-256
         * key is accepted, which is what {@code openssl ecparam -name prime256v1 -genkey | openssl
         * pkcs8 -topk8 -nocrypt} prints. A secret store that flattens the PEM onto one line with
         * literal {@code \n} sequences is read as if the line breaks were real.
         *
         * @throws IllegalStateException when a key is configured but is not that kind of key
         */
        public ECPrivateKey signingPrivateKey() {
            if (signingPrivateKeyPem == null || signingPrivateKeyPem.isBlank()) {
                return null;
            }
            String pem = signingPrivateKeyPem.replace("\\n", "\n").strip();
            int start = pem.indexOf(PKCS8_HEADER);
            int end = pem.indexOf(PKCS8_FOOTER);
            if (start < 0 || end < start) {
                throw new IllegalStateException("aiwos.security.signing-private-key-pem must be an unencrypted PKCS#8 key"
                        + " (" + PKCS8_HEADER + "); convert one with: openssl pkcs8 -topk8 -nocrypt");
            }
            try {
                String body = pem.substring(start + PKCS8_HEADER.length(), end).replaceAll("\\s", "");
                PrivateKey key = KeyFactory.getInstance("EC")
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
                if (!(key instanceof ECPrivateKey ec) || Curve.forECParameterSpec(ec.getParams()) != Curve.P_256) {
                    throw new IllegalStateException(
                            "aiwos.security.signing-private-key-pem must be an EC key on the P-256 curve (prime256v1)");
                }
                return ec;
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new IllegalStateException(
                        "aiwos.security.signing-private-key-pem could not be read as an EC P-256 private key", e);
            }
        }
    }

    /** Password hashing cost. Raised as hardware improves; never lowered for an existing hash. */
    public record Argon2(
            @DefaultValue("3") @Positive int iterations,
            @DefaultValue("65536") @Positive int memoryKib,
            @DefaultValue("4") @Positive int parallelism,
            @DefaultValue("16") @Positive int saltLength,
            @DefaultValue("32") @Positive int hashLength) {}

    /**
     * Envelope encryption for integration credentials.
     *
     * <p>Each organisation holds its own data key, itself encrypted under the master key. Rotating
     * the master key therefore rewrites a few hundred wrapped keys rather than every stored
     * secret, and the key identifier recorded beside each value says which master key wrapped it.
     */
    public record Encryption(
            String masterKeyBase64,
            @DefaultValue("local-dev") String masterKeyId,
            @DefaultValue("AES/GCM/NoPadding") String transformation,
            @DefaultValue("12") @Positive int ivLengthBytes,
            @DefaultValue("128") @Positive int tagLengthBits) {}

    public record Http(
            @DefaultValue("PT5S") Duration connectTimeout,
            @DefaultValue("PT60S") Duration readTimeout,
            @DefaultValue("PT15S") Duration writeTimeout,
            @DefaultValue("100") @Positive int maxConnections,
            @DefaultValue("20") @Positive int maxIdleConnections,
            @DefaultValue("10485760") @Positive long maxRequestBytes,
            @DefaultValue("http://localhost:5173") List<String> corsAllowedOrigins,
            @DefaultValue("true") boolean corsAllowCredentials,
            @DefaultValue("PT30M") Duration corsMaxAge) {}

    /**
     * Edge limits.
     *
     * <p>These are the safety net. Per-route, per-organisation and per-role limits live in runtime
     * configuration so they can be tightened during an incident without a deployment.
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("600") @Positive int defaultRequestsPerMinute,
            @DefaultValue("60") @Positive int burst,
            @DefaultValue("30") @Positive int anonymousRequestsPerMinute,
            @DefaultValue("10") @Positive int authAttemptsPerMinute,
            /*
             * When Redis is unreachable the limiter cannot count. Failing closed would take the
             * whole platform down with the cache, so the default is to allow traffic and raise a
             * degraded-dependency alert. Deployments that prefer the opposite set this to false.
             */
            @DefaultValue("true") boolean failOpenWhenUnavailable) {}

    public record Events(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("aiwos") String topicPrefix,
            @DefaultValue("3") @Positive int partitions,
            @DefaultValue("1") @Positive short replicationFactor,
            @DefaultValue("PT30S") Duration sendTimeout,
            @DefaultValue("4") @Positive int maxDeliveryAttempts,
            @DefaultValue("PT5S,PT30S,PT5M") List<Duration> retryBackoff,
            @DefaultValue("true") boolean deadLetterEnabled,
            @DefaultValue("P7D") Duration idempotencyWindow) {}

    /** Defaults for every outbound dependency; a named dependency may override any of them. */
    public record Resilience(
            @DefaultValue("50") @Positive float failureRatePercentThreshold,
            @DefaultValue("10") @Positive int slidingWindowSize,
            @DefaultValue("5") @Positive int minimumNumberOfCalls,
            @DefaultValue("PT30S") Duration waitDurationInOpenState,
            @DefaultValue("3") @Positive int permittedCallsInHalfOpenState,
            @DefaultValue("3") @Positive int maxRetryAttempts,
            @DefaultValue("PT0.5S") Duration retryInitialBackoff,
            @DefaultValue("PT30S") Duration retryMaxBackoff,
            @DefaultValue("2.0") double retryBackoffMultiplier,
            /* Full jitter. Without it, a recovering dependency is hit by every client at once. */
            @DefaultValue("true") boolean retryJitter,
            @DefaultValue("25") @Positive int bulkheadMaxConcurrentCalls,
            @DefaultValue("PT60S") Duration defaultCallTimeout,
            Map<String, DependencyOverride> dependencies) {

        /* An absent map binds to null; normalising here spares every reader a null check. */
        public Resilience {
            dependencies = dependencies == null ? Map.of() : Map.copyOf(dependencies);
        }

        /**
         * The override for one dependency. A model provider's breaker is kept per workspace and
         * named {@code provider.<workspace>.<provider>}; an override is written once per provider,
         * as {@code provider.<provider>}, so that name is tried when there is no exact match.
         */
        public DependencyOverride forDependency(String name) {
            DependencyOverride exact = dependencies.get(name);
            if (exact != null) {
                return exact;
            }
            if (name != null && name.startsWith("provider.")) {
                String rest = name.substring("provider.".length());
                int dot = rest.indexOf('.');
                if (dot > 0) {
                    return dependencies.getOrDefault("provider." + rest.substring(dot + 1), EMPTY_OVERRIDE);
                }
            }
            return EMPTY_OVERRIDE;
        }
    }

    private static final DependencyOverride EMPTY_OVERRIDE =
            new DependencyOverride(null, null, null, null, null, null, null);

    public record DependencyOverride(
            Float failureRatePercentThreshold,
            Integer slidingWindowSize,
            Integer minimumNumberOfCalls,
            Duration waitDurationInOpenState,
            Integer maxRetryAttempts,
            Duration callTimeout,
            Integer bulkheadMaxConcurrentCalls) {}

    /**
     * What is kept out of logs and traces, and how much of the traffic is traced.
     *
     * <p>The log format, the tracing switch and the exporter's address are not here: Spring Boot
     * reads {@code logging.structured.format.console}, {@code management.tracing.enabled} and
     * {@code management.otlp.tracing.endpoint} itself, and a copy of each in this tree was read by
     * nothing. {@code traceSampleRatio} is the exception, kept so the startup validator can check
     * the value in force; it is bound to {@code management.tracing.sampling.probability}.
     */
    public record Observability(
            @DefaultValue("1.0") @DecimalMin("0.0") @DecimalMax("1.0") double traceSampleRatio,
            /*
             * Keys whose values are removed from logs, traces, audit payloads and stored request
             * captures. Matching is on the key, case-insensitively, at any depth.
             */
            @DefaultValue("password,secret,token,api_key,apiKey,authorization,refresh_token,"
                            + "access_token,client_secret,private_key,credential,cookie,"
                            + "set-cookie,x-api-key,ssn,card_number")
                    List<String> redactKeys,
            @DefaultValue("true") boolean redactEmailAddresses) {
    }

    /** How this process reaches, and caches, the settings an operator can change at runtime. */
    public record RuntimeConfig(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT60S") Duration cacheTtl,
            @DefaultValue("aiwos:config:invalidate") String invalidationChannel,
            /* A missing cache must not become a missing setting: fall back to the database. */
            @DefaultValue("true") boolean readThroughOnCacheFailure) {}

    /**
     * Where the sibling services live.
     *
     * <p>Compose and Kubernetes both resolve these names, and a developer running one service
     * against the rest overrides only the entry they need.
     */
    public record Services(
            @NotNull @DefaultValue("http://gateway:8080") String gateway,
            @NotNull @DefaultValue("http://identity:8081") String identity,
            @NotNull @DefaultValue("http://organisation:8082") String organisation,
            @NotNull @DefaultValue("http://orchestrator:8083") String orchestrator,
            @NotNull @DefaultValue("http://memory:8084") String memory,
            @NotNull @DefaultValue("http://knowledge:8085") String knowledge,
            @NotNull @DefaultValue("http://integrations:8086") String integrations,
            @NotNull @DefaultValue("http://analytics:8087") String analytics) {

        public String urlFor(String service) {
            return switch (service) {
                case "gateway" -> gateway;
                case "identity" -> identity;
                case "organisation" -> organisation;
                case "orchestrator" -> orchestrator;
                case "memory" -> memory;
                case "knowledge" -> knowledge;
                case "integrations" -> integrations;
                case "analytics" -> analytics;
                default -> throw new IllegalArgumentException("Unknown service: " + service);
            };
        }
    }

    /**
     * Configuration problems that must not reach a deployed environment.
     *
     * <p>Returns every problem rather than throwing on the first, so an operator fixes them in one
     * pass instead of discovering them one restart at a time. The caller decides whether to refuse
     * to start; {@code PlatformStartupValidator} does exactly that outside local and test.
     */
    public List<String> validateForEnvironment() {
        if (!environment.isDeployed()) {
            return List.of();
        }
        List<String> problems = new java.util.ArrayList<>();
        /*
         * Either key source keeps every replica and every restart on one key: the PEM directly, or
         * a key generated once and kept in the database, encrypted under the master key. A key id
         * on its own is not a key, so it no longer satisfies this check.
         */
        if (isBlank(security.signingPrivateKeyPem()) && isBlank(security.encryption().masterKeyBase64())) {
            problems.add("aiwos.security.signing-private-key-pem must be set outside local and test, or"
                    + " aiwos.security.encryption.master-key-base64 so a generated key can be kept encrypted");
        }
        if (!isBlank(security.signingPrivateKeyPem())) {
            try {
                security.signingPrivateKey();
            } catch (IllegalStateException e) {
                problems.add(e.getMessage());
            }
        }
        if (security.developmentSecret().startsWith("insecure-local-development-secret")) {
            problems.add("aiwos.security.development-secret still holds its development placeholder");
        }
        if (security.internalServiceSecret().startsWith("insecure-local-internal-secret")) {
            problems.add("aiwos.security.internal-service-secret still holds its development placeholder");
        }
        if (isBlank(security.encryption().masterKeyBase64())) {
            problems.add("aiwos.security.encryption.master-key-base64 must be set outside local and test");
        }
        if (http.corsAllowedOrigins().contains("*")) {
            problems.add("aiwos.http.cors-allowed-origins must not be a wildcard");
        }
        if (http.corsAllowedOrigins().stream().anyMatch(o -> o.startsWith("http://"))) {
            problems.add("aiwos.http.cors-allowed-origins must use https outside local and test");
        }
        if (observability.traceSampleRatio() >= 1.0 && environment == Environment.PRODUCTION) {
            problems.add("management.tracing.sampling.probability (AIWOS_TRACE_SAMPLE_RATIO) of 1.0 is unaffordable in"
                    + " production; set it below 1.0, for example 0.1");
        }
        return List.copyOf(problems);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
