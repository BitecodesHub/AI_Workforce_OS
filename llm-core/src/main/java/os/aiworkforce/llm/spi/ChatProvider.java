package os.aiworkforce.llm.spi;

import java.util.List;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ChatResponse;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;

/**
 * What every model provider must be able to do.
 *
 * <p>Kept small on purpose. An adapter's whole job is to translate this platform's vocabulary
 * into one vendor's API and to classify that vendor's failures into {@code ProviderFailure};
 * everything else - retrying, failing over, budgeting, accounting, caching - belongs to the
 * router and is written once rather than seven times.
 *
 * <p>An adapter must never retry internally. The router owns the retry budget, and a hidden retry
 * inside an adapter multiplies against it: three router attempts over three adapter retries is
 * nine calls and nine charges, arriving at a provider that is already struggling.
 */
public interface ChatProvider {

    /** Which descriptor kind this adapter serves. */
    ProviderDescriptor.Kind kind();

    /**
     * Sends one request and returns one answer.
     *
     * <p>Fails with {@code ProviderException} carrying a classified {@code ProviderFailure}. An
     * unclassified exception is a defect in the adapter: it leaves the router unable to tell a
     * throttle from a retired model, and it will retry the wrong things.
     */
    Mono<ChatResponse> complete(ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential);

    /**
     * Streams an answer as it is produced.
     *
     * <p>The final element always carries the terminal {@code finishReason} and the usage totals.
     * A stream that ends without one is reported as {@code STREAM_INTERRUPTED} rather than as a
     * complete answer, because a truncated reply that looks finished is worse than a visible
     * failure.
     */
    Flux<ChatChunk> stream(ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential);

    /** Embeds text, for the knowledge base. Providers that cannot embed return an empty result. */
    default Mono<List<float[]>> embed(
            ProviderDescriptor provider, ModelSpec model, List<String> inputs, String credential) {
        return Mono.error(os.aiworkforce.llm.model.ProviderException.of(
                os.aiworkforce.llm.model.ProviderFailure.INVALID_REQUEST,
                provider.id(),
                model.modelId(),
                "This provider does not offer embeddings."));
    }

    /**
     * A cheap call that proves the credential works and the provider is reachable.
     *
     * <p>Used by the health panel and by the breaker's half-open probe, so a recovering provider
     * is tested with something trivial rather than with a person's real request.
     */
    Mono<Boolean> healthCheck(ProviderDescriptor provider, String credential);

    /** Whether this adapter supports streaming at all, regardless of the model. */
    default boolean supportsStreaming() {
        return true;
    }
}
