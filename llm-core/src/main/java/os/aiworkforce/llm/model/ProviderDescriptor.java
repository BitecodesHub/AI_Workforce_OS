package os.aiworkforce.llm.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A configured provider, as stored in the database.
 *
 * <p>Adding OpenRouter, NVIDIA, Groq or a self-hosted endpoint is a row here plus a credential -
 * never a deployment. The adapter is chosen by {@link #kind()}, and four of the seven share one
 * adapter because they speak the same protocol and differ only in base URL and header.
 *
 * @param id stable identifier, for example {@code openrouter}
 * @param displayName what the console shows
 * @param kind which adapter speaks to it
 * @param baseUrl root URL; overridable so a self-hosted or proxied endpoint works unchanged
 * @param credentialRef pointer into the credential store, never the credential itself
 * @param enabled whether the router may consider it
 * @param defaultHeaders extra headers this provider expects, such as attribution headers
 * @param regions ordered regions to try, for providers that are regional
 * @param requestsPerMinute client-side limit, kept below the contracted one
 * @param maxConcurrentRequests bulkhead, so one provider cannot exhaust the connection pool
 * @param priority tie-break when two candidates are otherwise equal; lower is preferred
 */
public record ProviderDescriptor(
        String id,
        String displayName,
        Kind kind,
        String baseUrl,
        String credentialRef,
        boolean enabled,
        Map<String, String> defaultHeaders,
        List<String> regions,
        Integer requestsPerMinute,
        Integer maxConcurrentRequests,
        int priority) {

    /**
     * Which adapter handles this provider.
     *
     * <p>{@link #OPENAI_COMPATIBLE} covers OpenRouter, NVIDIA NIM, Groq, OpenAI itself and any
     * self-hosted server that implements the same API. They are one adapter because they are one
     * protocol; treating them as four would mean fixing every streaming bug four times.
     */
    public enum Kind {
        OPENAI_COMPATIBLE,
        ANTHROPIC,
        GEMINI,
        BEDROCK,
        /** Deterministic, offline. Makes the whole platform demonstrable with no credentials. */
        SANDBOX
    }

    public ProviderDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    public boolean requiresCredential() {
        return kind != Kind.SANDBOX;
    }

    public boolean isRegional() {
        return kind == Kind.BEDROCK;
    }
}
