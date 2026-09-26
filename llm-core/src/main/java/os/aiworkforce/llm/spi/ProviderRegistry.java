package os.aiworkforce.llm.spi;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;

/**
 * Where the router looks up what providers and models exist.
 *
 * <p>An interface rather than a class, so the router does not know whether the answer came from a
 * table, a cache or a test fixture. The implementation in the orchestrator reads the
 * {@code llm_providers} and {@code llm_models} tables, which is what makes adding a provider a
 * row rather than a release.
 */
public interface ProviderRegistry {

    /** Every provider visible to a workspace, including platform-wide ones. */
    List<ProviderDescriptor> providers(String orgId);

    Optional<ProviderDescriptor> provider(String orgId, String providerId);

    Optional<ModelSpec> model(String orgId, String providerId, String modelId);

    /** Every enabled model, for the console and for building a default policy. */
    List<ModelSpec> models(String orgId);

    /**
     * Records that a provider says a model does not exist.
     *
     * <p>Retiring a model is something vendors do on their own schedule, and the first the
     * platform hears of it is a 404 on a request a person is waiting for. Marking it here keeps
     * every later request from paying that round trip, and raises the notice an operator needs in
     * order to change the policy.
     */
    void markModelUnavailable(String orgId, String providerId, String modelId, Duration duration, String reason);

    /**
     * Records that a credential was rejected.
     *
     * <p>Separate from marking the model, because the remedy is different: one needs a new key,
     * the other needs a new model. Conflating them sends an operator to the wrong screen.
     */
    void markCredentialInvalid(String orgId, String providerId, String reason);
}
