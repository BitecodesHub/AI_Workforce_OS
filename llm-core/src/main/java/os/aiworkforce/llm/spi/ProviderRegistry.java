// @find: model router, LLM, model providers, provider registry, list providers and models, model catalogue lookup, ProviderRegistry
// @what: Interface the router uses to look up which providers and models exist.
package os.aiworkforce.llm.spi;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ProviderFailure;

/**
 * Where the router looks up what providers and models exist.
 *
 * <p>An interface rather than a class, so the router does not know whether the answer came from a
 * table, a cache or a test fixture. The implementation in the orchestrator reads the
 * {@code llm_providers} and {@code llm_models} tables, which is what makes adding a provider a
 * row rather than a release.
 */
public interface ProviderRegistry {

    /**
     * Every provider visible to a workspace, including platform-wide ones, with that workspace's
     * own view of them: {@link ProviderDescriptor#enabled()} is the workspace's effective state,
     * which a workspace can narrow but never widen beyond what the platform offers.
     */
    List<ProviderDescriptor> providers(String orgId);

    /** One provider as {@link #providers} describes it; empty when it is not visible to the workspace. */
    Optional<ProviderDescriptor> provider(String orgId, String providerId);

    /** One model, with the later of the platform's and this workspace's unavailability. */
    Optional<ModelSpec> model(String orgId, String providerId, String modelId);

    /** Every enabled model, for the console and for building a default policy, as this workspace sees it. */
    List<ModelSpec> models(String orgId);

    /**
     * Sets a model aside for a while after a failure that will repeat until somebody acts.
     *
     * <p>Where the note lands depends on {@code cause}, because the failures mean different
     * things. An account out of credit or quota is a fact about one workspace's account and must
     * only ever set the model aside for that workspace. A retired model
     * ({@link ProviderFailure#MODEL_NOT_FOUND}) is the same fact for every workspace, but one
     * workspace's account can produce the same 404, so it is noted for that workspace first and
     * may reach every workspace - briefly - only once more than one workspace has been told so.
     *
     * <p>Retiring a model is something vendors do on their own schedule, and the first the
     * platform hears of it is a 404 on a request a person is waiting for. Marking it here keeps
     * every later request from paying that round trip, and raises the notice an operator needs in
     * order to change the policy.
     */
    void markModelUnavailable(
            String orgId, String providerId, String modelId, ProviderFailure cause, Duration duration, String reason);

    /**
     * Records that a workspace's credential was rejected.
     *
     * <p>Separate from marking the model, because the remedy is different: one needs a new key,
     * the other needs a new model. Conflating them sends an operator to the wrong screen. Keys are
     * stored per workspace, so this only ever changes what {@code orgId} sees.
     */
    void markCredentialInvalid(String orgId, String providerId, String reason);
}
