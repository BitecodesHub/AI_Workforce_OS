// @find: tests for internal embedding controller, internal embeddings, /internal/embeddings, embed text, embedding provider
// @what: Unit and integration tests (16 cases) for internal embedding controller, for example: other workspace refused; api key refused; budget checked with the estimate; other workspaces run refused.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import os.aiworkforce.llm.budget.BudgetGuard;
import os.aiworkforce.llm.model.AttemptRecord;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ProviderException;
import os.aiworkforce.llm.model.ProviderFailure;
import os.aiworkforce.llm.router.ModelRouter;
import os.aiworkforce.llm.spi.ChatProvider;
import os.aiworkforce.llm.spi.CredentialResolver;
import os.aiworkforce.llm.spi.ProviderRegistry;
import os.aiworkforce.llm.usage.UsageRecorder;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Embedding spends a workspace's provider keys, so only a service token for that workspace may ask,
 * and what it spends is held to a cap and written down like any chat call.
 */
class InternalEmbeddingControllerTest {

    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();

    private final ProviderRegistry registry = mock(ProviderRegistry.class);
    private final CredentialResolver credentials = mock(CredentialResolver.class);
    private final BudgetGuard budget = mock(BudgetGuard.class);
    private final UsageRecorder usage = mock(UsageRecorder.class);
    private final ChatProvider adapter = mock(ChatProvider.class);
    private final Runs runs = mock(Runs.class);
    private final Agents agents = mock(Agents.class);
    private final InternalEmbeddingController.EmbedRequest request =
            new InternalEmbeddingController.EmbedRequest("openrouter", "embed-1", List.of("hello"));

    private InternalEmbeddingController controller =
            new InternalEmbeddingController(List.of(), registry, credentials, budget, usage, runs, agents);

    @BeforeEach
    void wireAProvider() {
        ProviderDescriptor provider = new ProviderDescriptor(
                "openrouter",
                "OpenRouter",
                ProviderDescriptor.Kind.OPENAI_COMPATIBLE,
                "http://localhost",
                "openrouter",
                true,
                Map.of(),
                List.of(),
                null,
                null,
                0);
        // Two dollars per million prompt tokens, so a 400-character text (100 tokens) is a fixed price.
        ModelSpec model = new ModelSpec(
                "openrouter",
                "embed-1",
                "Embed",
                8_000,
                0,
                false,
                false,
                false,
                false,
                new BigDecimal("2"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true,
                null);
        when(registry.provider(ORG_A.toString(), "openrouter")).thenReturn(Optional.of(provider));
        when(registry.model(ORG_A.toString(), "openrouter", "embed-1")).thenReturn(Optional.of(model));
        when(adapter.kind()).thenReturn(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        when(credentials.lookup(ORG_A.toString(), "openrouter")).thenReturn(new CredentialResolver.Found("sk-test"));
        when(budget.check(any(), any())).thenReturn(BudgetGuard.Decision.allow(null));
        controller = new InternalEmbeddingController(List.of(adapter), registry, credentials, budget, usage, runs, agents);
        RequestContext.setActor(service(Actor.Kind.SYSTEM, ORG_A));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private static Actor service(Actor.Kind kind, UUID orgId) {
        return new Actor(
                "knowledge",
                kind,
                orgId.toString(),
                null,
                Set.of(),
                0L,
                UUID.randomUUID().toString(),
                null,
                null,
                Map.of());
    }

    /** A run of workspace A, made by {@code agentId}. */
    private void ownRun(UUID runId, UUID agentId) {
        Run run = mock(Run.class);
        when(run.getAgentId()).thenReturn(agentId);
        when(runs.findByIdAndOrgId(runId, ORG_A)).thenReturn(Optional.of(run));
    }

    private static String text(int characters) {
        return "x".repeat(characters);
    }

    @Test
    @DisplayName("a service token for one workspace cannot embed with another workspace's keys")
    void otherWorkspaceRefused() {
        RequestContext.setActor(service(Actor.Kind.SYSTEM, ORG_A));

        ApiException refused = catchThrowableOfType(() -> controller.embed(request, ORG_B), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.ORGANISATION_MISMATCH);
        verifyNoInteractions(credentials, budget, usage);
    }

    @Test
    @DisplayName("a machine key is refused, as a person's token is")
    void apiKeyRefused() {
        RequestContext.setActor(service(Actor.Kind.API_KEY, ORG_A));

        ApiException refused = catchThrowableOfType(() -> controller.embed(request, ORG_A), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        verifyNoInteractions(credentials, budget, usage);
    }

    @Test
    @DisplayName("the budget is asked first, with characters over four times the model's price per million")
    void budgetCheckedWithTheEstimate() {
        when(adapter.embed(any(), any(), any(), any(), any())).thenReturn(Mono.just(List.of(new float[] {1f, 2f})));
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ownRun(runId, agentId);

        controller.embed(
                new InternalEmbeddingController.EmbedRequest("openrouter", "embed-1", List.of(text(400)), agentId, runId),
                ORG_A);

        ArgumentCaptor<ModelRouter.CallContext> context = ArgumentCaptor.forClass(ModelRouter.CallContext.class);
        ArgumentCaptor<BigDecimal> estimate = ArgumentCaptor.forClass(BigDecimal.class);
        verify(budget).check(context.capture(), estimate.capture());
        // 400 characters is 100 tokens, at two dollars per million tokens.
        assertThat(estimate.getValue()).isEqualByComparingTo("0.0002");
        // The spend is charged to the run it was made for.
        assertThat(context.getValue())
                .isEqualTo(new ModelRouter.CallContext(ORG_A.toString(), agentId.toString(), runId.toString()));
    }

    @Test
    @DisplayName("a run of another workspace is refused as not found, before anything is spent")
    void otherWorkspacesRunRefused() {
        UUID foreignRun = UUID.randomUUID();
        // Nothing is stubbed for ORG_A: the run exists only in some other workspace.

        ApiException refused = catchThrowableOfType(
                () -> controller.embed(
                        new InternalEmbeddingController.EmbedRequest(
                                "openrouter", "embed-1", List.of("hello"), null, foreignRun),
                        ORG_A),
                ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(runs).findByIdAndOrgId(foreignRun, ORG_A);
        verifyNoInteractions(credentials, budget, usage);
        verify(adapter, never()).embed(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an agent of another workspace is refused as not found, before anything is spent")
    void otherWorkspacesAgentRefused() {
        UUID foreignAgent = UUID.randomUUID();
        when(agents.findByIdAndOrgId(foreignAgent, ORG_A)).thenReturn(Optional.empty());

        ApiException refused = catchThrowableOfType(
                () -> controller.embed(
                        new InternalEmbeddingController.EmbedRequest(
                                "openrouter", "embed-1", List.of("hello"), foreignAgent, null),
                        ORG_A),
                ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND);
        verifyNoInteractions(credentials, budget, usage);
    }

    @Test
    @DisplayName("a run named with some other agent than its own is refused")
    void runOfAnotherAgentRefused() {
        UUID runId = UUID.randomUUID();
        ownRun(runId, UUID.randomUUID());

        ApiException refused = catchThrowableOfType(
                () -> controller.embed(
                        new InternalEmbeddingController.EmbedRequest(
                                "openrouter", "embed-1", List.of("hello"), UUID.randomUUID(), runId),
                        ORG_A),
                ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND);
        verifyNoInteractions(credentials, budget, usage);
    }

    @Test
    @DisplayName("naming only the run still counts the spend toward the run's own agent")
    void runImpliesItsAgent() {
        when(adapter.embed(any(), any(), any(), any(), any())).thenReturn(Mono.just(List.of(new float[] {1f})));
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ownRun(runId, agentId);

        controller.embed(
                new InternalEmbeddingController.EmbedRequest("openrouter", "embed-1", List.of("hello"), null, runId),
                ORG_A);

        verify(usage).record(eq(ORG_A.toString()), eq(agentId.toString()), eq(runId.toString()), any(), any());
    }

    @Test
    @DisplayName("an agent of this workspace is accepted and the spend is counted toward it")
    void ownAgentAccepted() {
        when(adapter.embed(any(), any(), any(), any(), any())).thenReturn(Mono.just(List.of(new float[] {1f})));
        UUID agentId = UUID.randomUUID();
        when(agents.findByIdAndOrgId(agentId, ORG_A)).thenReturn(Optional.of(mock(Agent.class)));

        controller.embed(
                new InternalEmbeddingController.EmbedRequest("openrouter", "embed-1", List.of("hello"), agentId, null),
                ORG_A);

        verify(usage).record(eq(ORG_A.toString()), eq(agentId.toString()), eq(null), any(), any());
    }

    @Test
    @DisplayName("a cap that has been reached stops the embedding before the provider is called")
    void budgetRefusalStopsEmbedding() {
        when(budget.check(any(), any()))
                .thenReturn(BudgetGuard.Decision.deny(
                        "The workspace has reached its monthly model budget of $5.00.", BigDecimal.ZERO));

        ApiException refused = catchThrowableOfType(() -> controller.embed(request, ORG_A), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.BUDGET_EXCEEDED);
        assertThat(refused.getMessage()).contains("monthly model budget");
        verify(adapter, never()).embed(any(), any(), any(), any(), any());
        verifyNoInteractions(usage);
    }

    @Test
    @DisplayName("a cap set to answer with the offline model still stops embedding, which has no offline model")
    void sandboxPolicyStillStops() {
        when(budget.check(any(), any()))
                .thenReturn(BudgetGuard.Decision.denyToSandbox("The workspace is at its cap.", BigDecimal.ZERO));

        ApiException refused = catchThrowableOfType(() -> controller.embed(request, ORG_A), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.BUDGET_EXCEEDED);
        verify(adapter, never()).embed(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a successful embedding is recorded with its estimated cost and counted against the budget")
    void successIsRecorded() {
        when(adapter.embed(any(), any(), any(), any(), any())).thenReturn(Mono.just(List.of(new float[] {1f, 2f})));

        InternalEmbeddingController.EmbedResponse response =
                controller.embed(
                        new InternalEmbeddingController.EmbedRequest("openrouter", "embed-1", List.of(text(400))),
                        ORG_A);

        assertThat(response.dimension()).isEqualTo(2);
        ArgumentCaptor<AttemptRecord> attempt = ArgumentCaptor.forClass(AttemptRecord.class);
        ArgumentCaptor<BigDecimal> cost = ArgumentCaptor.forClass(BigDecimal.class);
        verify(usage).record(eq(ORG_A.toString()), eq(null), eq(null), attempt.capture(), cost.capture());
        assertThat(attempt.getValue().outcome()).isEqualTo(AttemptRecord.Outcome.SUCCEEDED);
        assertThat(attempt.getValue().provider()).isEqualTo("openrouter");
        assertThat(attempt.getValue().model()).isEqualTo("embed-1");
        assertThat(attempt.getValue().usage().promptTokens()).isEqualTo(100);
        assertThat(cost.getValue()).isEqualByComparingTo("0.0002");
        verify(budget).record(any(ModelRouter.CallContext.class), eq(cost.getValue()));
    }

    @Test
    @DisplayName("a failed embedding is recorded as a failed attempt, and the failure still reaches the caller")
    void failureIsRecorded() {
        when(adapter.embed(any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(
                        ProviderException.of(ProviderFailure.RATE_LIMITED, "openrouter", "embed-1", "slow down")));

        ApiException failed = catchThrowableOfType(() -> controller.embed(request, ORG_A), ApiException.class);
        // In words the knowledge service can show, not "Something went wrong".
        assertThat(failed).isNotNull();
        assertThat(failed.code()).isEqualTo(os.aiworkforce.platform.error.ErrorCode.RATE_LIMITED);
        assertThat(failed.getMessage()).contains("limiting how fast");
        assertThat(failed.getCause()).isInstanceOf(ProviderException.class);

        ArgumentCaptor<AttemptRecord> attempt = ArgumentCaptor.forClass(AttemptRecord.class);
        verify(usage).record(eq(ORG_A.toString()), eq(null), eq(null), attempt.capture(), any());
        assertThat(attempt.getValue().outcome()).isEqualTo(AttemptRecord.Outcome.FAILED);
        assertThat(attempt.getValue().failure()).isEqualTo(ProviderFailure.RATE_LIMITED);
        verify(budget, never()).record(any(), any());
    }

    @Test
    @DisplayName("a usage row that cannot be written never costs the caller its vectors")
    void accountingNeverFailsTheCall() {
        when(adapter.embed(any(), any(), any(), any(), any())).thenReturn(Mono.just(List.of(new float[] {1f})));
        doThrow(new IllegalStateException("database is down"))
                .when(usage)
                .record(any(), any(), any(), any(), any());
        doThrow(new IllegalStateException("database is down")).when(budget).record(any(), any());

        InternalEmbeddingController.EmbedResponse response =
                controller.embed(
                        new InternalEmbeddingController.EmbedRequest("openrouter", "embed-1", List.of(text(400))),
                        ORG_A);

        assertThat(response.vectors()).hasSize(1);
    }

    @Test
    @DisplayName("a credential store that did not answer is a retryable dependency error, not a missing key")
    void credentialStoreUnavailable() {
        when(credentials.lookup(ORG_A.toString(), "openrouter"))
                .thenReturn(new CredentialResolver.Unavailable("HTTP 503 from the credential store"));

        ApiException refused = catchThrowableOfType(() -> controller.embed(request, ORG_A), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
        assertThat(refused.retryable()).isTrue();
        verifyNoInteractions(budget, usage);
    }

    @Test
    @DisplayName("no stored key is still reported as a provider that is not set up")
    void noCredentialStored() {
        when(credentials.lookup(ORG_A.toString(), "openrouter")).thenReturn(CredentialResolver.NotFound.INSTANCE);

        ApiException refused = catchThrowableOfType(() -> controller.embed(request, ORG_A), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.PROVIDER_NOT_CONFIGURED);
    }

    @Test
    @DisplayName("the token estimate is about one per four characters across the whole batch")
    void estimateIsCharactersOverFour() {
        assertThat(InternalEmbeddingController.estimateTokens(List.of(text(400), text(100)))).isEqualTo(125);
        assertThat(InternalEmbeddingController.estimateTokens(List.of(""))).isZero();
    }
}
