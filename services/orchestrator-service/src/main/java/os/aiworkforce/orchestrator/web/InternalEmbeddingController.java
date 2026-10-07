package os.aiworkforce.orchestrator.web;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.llm.budget.BudgetGuard;
import os.aiworkforce.llm.model.AttemptRecord;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ProviderException;
import os.aiworkforce.llm.model.ProviderFailure;
import os.aiworkforce.llm.model.TokenUsage;
import os.aiworkforce.llm.router.ModelRouter;
import os.aiworkforce.llm.spi.ChatProvider;
import os.aiworkforce.llm.spi.CredentialResolver;
import os.aiworkforce.llm.spi.ProviderRegistry;
import os.aiworkforce.llm.usage.UsageRecorder;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Embeddings, for the knowledge base.
 *
 * <p>The orchestrator owns model routing, so it owns embeddings too. The alternative - letting
 * the knowledge service reach providers directly - would mean two services holding provider
 * configuration, two credential paths, and two places to change when a vendor is swapped. It also
 * means the knowledge service would need the orchestrator's tables, which is exactly the coupling
 * the service boundaries exist to prevent.
 *
 * <p>Internal only, and refused to a person's token: this endpoint spends a workspace's money.
 *
 * <p>Embedding is spending like any chat call, so it is held to the same account of it. The
 * workspace's budget is asked before the provider is, so a document upload cannot run past a cap
 * that a chat message would have been stopped by. Every attempt is written to the usage table,
 * the failures as well as the successes, so what ingestion costs shows in the spend report and
 * the provider's invoice reconciles. The caller can name the agent and run it embeds for, which
 * attributes a retrieval made during a run to that run instead of leaving it unowned.
 *
 * <p>The agent and the run are the caller's word, and they decide whose per-run total and whose
 * per-agent cap this spend counts toward, so they are checked before anything is spent: both must
 * belong to the workspace the token is for, and a run must be the named agent's. Without that, a
 * service token for one workspace could write rows against another workspace's run and move its
 * totals. A pair that does not check out is refused as not found, which confirms nothing about
 * another workspace's runs.
 *
 * <p>Embedding providers report no token counts, so the figure recorded is an estimate (about
 * one token per four characters) priced at the model's catalogue rate.
 */
@RestController
@RequestMapping("/internal/embeddings")
@Tag(name = "Internal")
public class InternalEmbeddingController {

    /** Kept modest: most providers reject a larger batch, and a rejected batch wastes the lot. */
    private static final int MAX_BATCH = 128;

    /** Characters to a token, for the estimate; embeddings report no usage of their own. */
    private static final int CHARS_PER_TOKEN = 4;

    private static final Logger log = LoggerFactory.getLogger(InternalEmbeddingController.class);

    private final List<ChatProvider> providers;
    private final ProviderRegistry registry;
    private final CredentialResolver credentials;
    private final BudgetGuard budget;
    private final UsageRecorder usage;
    private final Runs runs;
    private final Agents agents;

    public InternalEmbeddingController(
            List<ChatProvider> providers,
            ProviderRegistry registry,
            CredentialResolver credentials,
            BudgetGuard budget,
            UsageRecorder usage,
            Runs runs,
            Agents agents) {
        this.providers = providers;
        this.registry = registry;
        this.credentials = credentials;
        this.budget = budget;
        this.usage = usage;
        this.runs = runs;
        this.agents = agents;
    }

    /**
     * @param agentId the agent this embedding is made for, when it is made during a run; absent for
     *     ingesting a document
     * @param runId the run it is made for, with the same meaning
     */
    public record EmbedRequest(
            @NotBlank String providerId,
            @NotBlank String modelId,
            @NotEmpty @Size(max = MAX_BATCH) List<String> texts,
            UUID agentId,
            UUID runId) {

        public EmbedRequest(String providerId, String modelId, List<String> texts) {
            this(providerId, modelId, texts, null, null);
        }
    }

    /**
     * @param vectors one per input, in the same order
     * @param dimension the width, so the caller can refuse a collection built at another size
     */
    public record EmbedResponse(List<float[]> vectors, int dimension) {}

    @PostMapping
    @Operation(summary = "Internal: embed text through the workspace's configured provider")
    public EmbedResponse embed(
            @Valid @RequestBody EmbedRequest request, @RequestHeader("X-Workspace-Id") UUID workspaceId) {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER || actor.kind() == Actor.Kind.API_KEY) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }
        // A service token minted for one workspace spends that workspace's keys alone, whatever
        // workspace the header names.
        if (actor.orgId() != null && !actor.orgId().equals(workspaceId.toString())) {
            throw new ApiException(
                    ErrorCode.ORGANISATION_MISMATCH, "This service token belongs to a different workspace.");
        }

        String orgId = workspaceId.toString();
        // Before the provider, the key or the budget is touched: nothing is spent for an
        // attribution that is not this workspace's to make.
        ModelRouter.CallContext context = attribution(workspaceId, request.agentId(), request.runId());
        ProviderDescriptor provider = registry.provider(orgId, request.providerId())
                .orElseThrow(() ->
                        new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED).with("provider", request.providerId()));
        ModelSpec model = registry.model(orgId, request.providerId(), request.modelId())
                .orElseThrow(() -> new ApiException(ErrorCode.MODEL_NOT_FOUND).with("model", request.modelId()));

        ChatProvider adapter = providers.stream()
                .filter(candidate -> candidate.kind() == provider.kind())
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED));

        String credential = null;
        if (provider.requiresCredential()) {
            CredentialResolver.Lookup lookup = credentials.lookup(orgId, provider.credentialRef());
            if (lookup instanceof CredentialResolver.Unavailable) {
                // Not "no key": the store did not answer, and the knowledge service is told to try
                // again rather than that the provider needs a credential it already has.
                throw new ApiException(
                                ErrorCode.DEPENDENCY_UNAVAILABLE,
                                "Could not reach the credential store, so nothing was embedded. Try again in a"
                                        + " minute.")
                        .with("provider", request.providerId());
            }
            if (!(lookup instanceof CredentialResolver.Found found)) {
                throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED)
                        .with("provider", request.providerId())
                        .with("hint", "Store a credential for this provider before ingesting.");
            }
            credential = found.value();
        }

        int estimatedTokens = estimateTokens(request.texts());
        BigDecimal estimate = model.estimateCost(estimatedTokens, 0);
        BudgetGuard.Decision decision = budget.check(context, estimate);
        if (!decision.allowed()) {
            // There is no offline model to fall back to for a vector, so a cap set to answer with
            // the sandbox still stops embedding: a placeholder vector would poison the index.
            throw new ApiException(ErrorCode.BUDGET_EXCEEDED, decision.reason());
        }

        Instant startedAt = Instant.now();
        List<float[]> vectors;
        try {
            vectors = adapter.embed(provider, model, request.texts(), credential)
                    .timeout(Duration.ofSeconds(60))
                    .block();
        } catch (RuntimeException e) {
            record(
                    context,
                    AttemptRecord.failed(
                            provider.id(),
                            model.modelId(),
                            failureOf(e),
                            "The embedding call failed.",
                            startedAt,
                            Duration.between(startedAt, Instant.now()),
                            e instanceof ProviderException provided ? provided.httpStatus() : null,
                            TokenUsage.NONE),
                    BigDecimal.ZERO);
            throw e;
        }

        if (vectors == null || vectors.size() != request.texts().size()) {
            record(
                    context,
                    AttemptRecord.failed(
                            provider.id(),
                            model.modelId(),
                            ProviderFailure.MALFORMED_RESPONSE,
                            "The provider returned an unexpected number of vectors.",
                            startedAt,
                            Duration.between(startedAt, Instant.now()),
                            200,
                            TokenUsage.of(estimatedTokens, 0)),
                    // The provider processed the texts and billed for them, whatever it returned.
                    estimate);
            // A provider returning a different number of vectors than texts would misalign every
            // citation after it, which is far worse than failing here.
            throw new ApiException(
                    ErrorCode.UPSTREAM_ERROR, "The embedding provider returned an unexpected number of vectors.");
        }

        record(
                context,
                AttemptRecord.succeeded(
                        provider.id(),
                        model.modelId(),
                        startedAt,
                        Duration.between(startedAt, Instant.now()),
                        TokenUsage.of(estimatedTokens, 0)),
                estimate);
        return new EmbedResponse(vectors, vectors.isEmpty() ? 0 : vectors.get(0).length);
    }

    /**
     * Who the spend is for, once the agent and the run the caller named are known to be this
     * workspace's. A run implies its agent, so a caller that names only the run still has the
     * spend counted toward that agent's daily cap; naming a different agent than the run's is
     * refused rather than guessed at.
     */
    private ModelRouter.CallContext attribution(UUID workspaceId, UUID agentId, UUID runId) {
        UUID agent = agentId;
        if (runId != null) {
            Run run = runs.findByIdAndOrgId(runId, workspaceId).orElseThrow(() -> ApiException.notFound("run", runId));
            if (agentId != null && !agentId.equals(run.getAgentId())) {
                throw ApiException.notFound("run", runId);
            }
            agent = run.getAgentId();
        } else if (agentId != null) {
            agents.findByIdAndOrgId(agentId, workspaceId).orElseThrow(() -> ApiException.notFound("agent", agentId));
        }
        return new ModelRouter.CallContext(
                workspaceId.toString(),
                agent == null ? null : agent.toString(),
                runId == null ? null : runId.toString());
    }

    /** About one token per four characters across every text in the batch. */
    static int estimateTokens(List<String> texts) {
        long characters = 0;
        for (String text : texts) {
            characters += text == null ? 0 : text.length();
        }
        return (int) Math.min(Integer.MAX_VALUE, characters / CHARS_PER_TOKEN);
    }

    private static ProviderFailure failureOf(RuntimeException error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ProviderException provided) {
                return provided.failure();
            }
            current = current.getCause();
        }
        return ProviderFailure.UNKNOWN;
    }

    /**
     * Writes down one attempt and what it cost. Never throws: the vectors are already paid for,
     * and a usage row that could not be written is a gap in a report, not a reason to lose them.
     */
    private void record(ModelRouter.CallContext context, AttemptRecord attempt, BigDecimal cost) {
        try {
            usage.record(context.orgId(), context.agentId(), context.runId(), attempt, cost);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not record usage for an embedding on {}/{} in workspace {}: {}",
                    attempt.provider(),
                    attempt.model(),
                    context.orgId(),
                    e.toString());
        }
        if (cost.signum() > 0) {
            try {
                budget.record(context, cost);
            } catch (RuntimeException e) {
                log.warn(
                        "Could not record an embedding's spend against the budget of workspace {}: {}",
                        context.orgId(),
                        e.toString());
            }
        }
    }
}
