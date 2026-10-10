// @find: tests for provider isolation, provider isolation, per-workspace providers, tenant separation, credentials not shared, /api/providers
// @what: Unit and integration tests (22 cases) for provider isolation, for example: disable in one workspace leaves another unchanged; enable in one workspace does not reach another; another workspaces private provider is not found; own provider is changed directly.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import os.aiworkforce.llm.budget.BudgetGuard;
import os.aiworkforce.llm.model.AttemptRecord;
import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ChatResponse;
import os.aiworkforce.llm.model.FinishReason;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ProviderException;
import os.aiworkforce.llm.model.ProviderFailure;
import os.aiworkforce.llm.model.TokenUsage;
import os.aiworkforce.llm.router.ModelRouter;
import os.aiworkforce.llm.router.RoutingPolicy;
import os.aiworkforce.llm.router.TranscriptCompactor;
import os.aiworkforce.llm.spi.ChatChunk;
import os.aiworkforce.llm.spi.ChatProvider;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.LlmModelEntity;
import os.aiworkforce.orchestrator.domain.LlmProviderEntity;
import os.aiworkforce.orchestrator.domain.ModelPolicyCandidate;
import os.aiworkforce.orchestrator.domain.ModelPolicyEntity;
import os.aiworkforce.orchestrator.domain.WorkspaceModelAvailability;
import os.aiworkforce.orchestrator.domain.WorkspaceProviderSetting;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ModelPolicies;
import os.aiworkforce.orchestrator.repository.Models;
import os.aiworkforce.orchestrator.repository.Providers;
import os.aiworkforce.orchestrator.repository.Usage;
import os.aiworkforce.orchestrator.repository.WorkspaceModelAvailabilities;
import os.aiworkforce.orchestrator.repository.WorkspaceProviderSettings;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry;
import os.aiworkforce.orchestrator.service.JpaUsageRecorder;
import os.aiworkforce.orchestrator.service.RoutingPolicyResolver;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.resilience.ResiliencePresets;

/**
 * Two workspaces share one provider catalogue, and nothing one does with it reaches the other.
 *
 * <p>Every piece between the HTTP endpoint and the router is real here - the controller, the
 * registry that merges each workspace's state into the shared rows, the router and the usage
 * recorder - and only the tables are kept in memory. So "workspace A's disable, refused key or
 * empty account leaves workspace B alone" is asserted through the same code a request runs.
 */
class ProviderIsolationTest {

    private static final UUID ORG_A = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID ORG_B = UUID.fromString("00000000-0000-7000-8000-0000000000b2");
    private static final UUID ORG_C = UUID.fromString("00000000-0000-7000-8000-0000000000c3");
    private static final String LLAMA = "meta-llama/llama-3.3-70b-instruct";

    // The tables, in memory.
    private final Map<String, LlmProviderEntity> providerRows = new LinkedHashMap<>();
    private final Map<LlmModelEntity.Key, LlmModelEntity> modelRows = new LinkedHashMap<>();
    private final Map<WorkspaceProviderSetting.Key, WorkspaceProviderSetting> settingRows = new HashMap<>();
    private final Map<WorkspaceModelAvailability.Key, WorkspaceModelAvailability> availabilityRows =
            new HashMap<>();

    /** What the vendor answers each workspace's key with; absent means a normal answer. */
    private final Map<String, ProviderFailure> vendorAnswers = new HashMap<>();

    /** The agents each workspace has, for the policy check that names one. */
    private final Map<UUID, List<Agent>> agentRows = new HashMap<>();

    /** The routing policies each workspace has stored, as the one query for all of them returns them. */
    private final Map<UUID, List<ModelPolicyEntity>> agentPolicyRows = new HashMap<>();

    private WorkspaceProviderSettings settings;
    private JpaProviderRegistry registry;
    private ModelRouter router;
    private RoutingPolicyResolver policies;
    private ModelPolicies modelPolicies;
    private AuditClient audit;
    private ProviderController controller;

    @BeforeEach
    void setUp() {
        // As V10 leaves the seed: every platform row offered, and only some on until a workspace
        // chooses. Bedrock stands for a provider the installation has withdrawn.
        provider("sandbox", "Offline sandbox", "SANDBOX", null, true, true, 99, null);
        provider("openrouter", "OpenRouter", "OPENAI_COMPATIBLE", "provider:openrouter", true, true, 10, null);
        provider("groq", "Groq", "OPENAI_COMPATIBLE", "provider:groq", true, false, 5, null);
        provider("bedrock", "AWS Bedrock", "OPENAI_COMPATIBLE", "provider:bedrock", false, false, 35, null);
        provider("c-private", "C's own endpoint", "OPENAI_COMPATIBLE", "provider:c-private", true, false, 50, ORG_C);
        model("sandbox", "sandbox-1");
        model("openrouter", LLAMA);
        model("groq", "llama-3.1-8b-instant");
        model("bedrock", "bedrock-model");
        model("c-private", "c-model");

        Providers providers = providersTable();
        Models models = modelsTable();
        settings = settingsTable();
        WorkspaceModelAvailabilities availability = availabilityTable();

        registry = new JpaProviderRegistry(providers, models, settings, availability);
        router = new ModelRouter(
                List.of(
                        new Vendor(ProviderDescriptor.Kind.OPENAI_COMPATIBLE),
                        new Vendor(ProviderDescriptor.Kind.SANDBOX)),
                registry,
                (orgId, credentialRef) -> Optional.of("key-" + orgId),
                BudgetGuard.UNLIMITED,
                new JpaUsageRecorder(mock(Usage.class), settings),
                new ResiliencePresets(properties()),
                new TranscriptCompactor());
        policies = mock(RoutingPolicyResolver.class);
        when(policies.resolve(any(), isNull())).thenReturn(chain(RoutingPolicy.ExhaustedBehaviour.FAIL_CLOSED,
                "openrouter/" + LLAMA, "sandbox/sandbox-1"));
        audit = mock(AuditClient.class);
        Agents agents = mock(Agents.class);
        when(agents.findByOrgIdOrderByName(any()))
                .thenAnswer(call -> agentRows.getOrDefault(call.<UUID>getArgument(0), List.of()));
        modelPolicies = mock(ModelPolicies.class);
        when(modelPolicies.findByOrgId(any()))
                .thenAnswer(call -> agentPolicyRows.getOrDefault(call.<UUID>getArgument(0), List.of()));
        controller = new ProviderController(
                providers, models, router, registry, settings, policies, modelPolicies, agents, audit);
        signInTo(ORG_A);
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    // ---- Turning a provider off ------------------------------------------------------------

    @Test
    @DisplayName("workspace A turning a provider off leaves workspace B's list and routing unchanged")
    void disableInOneWorkspaceLeavesAnotherUnchanged() {
        signInTo(ORG_B);
        List<ProviderController.ProviderView> before = controller.list();

        signInTo(ORG_A);
        ProviderController.ProviderView disabled = controller.disable("openrouter");

        assertThat(disabled.enabled()).isFalse();
        assertThat(view(ORG_A, "openrouter").enabled()).isFalse();
        // The shared catalogue row is never written by a workspace.
        assertThat(providerRows.get("openrouter").isEnabled()).isTrue();

        signInTo(ORG_B);
        assertThat(controller.list()).usingRecursiveComparison().isEqualTo(before);
        assertThat(view(ORG_B, "openrouter").enabled()).isTrue();

        assertThat(route(ORG_B, "openrouter/" + LLAMA).provider()).isEqualTo("openrouter");
        assertThatThrownBy(() -> route(ORG_A, "openrouter/" + LLAMA))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code())
                        .isEqualTo(ErrorCode.NO_MODEL_AVAILABLE));

        verify(audit)
                .record(
                        eq(ORG_A),
                        any(),
                        eq("provider.disable"),
                        eq("provider"),
                        eq("openrouter"),
                        eq("succeeded"),
                        anyMap());
    }

    @Test
    @DisplayName("workspace B turning a provider on again does not undo workspace A's choice")
    void enableInOneWorkspaceDoesNotReachAnother() {
        controller.disable("openrouter");

        signInTo(ORG_B);
        controller.disable("openrouter");
        controller.enable("openrouter");

        assertThat(view(ORG_B, "openrouter").enabled()).isTrue();
        assertThat(view(ORG_A, "openrouter").enabled()).isFalse();
        verify(audit)
                .record(
                        eq(ORG_B),
                        any(),
                        eq("provider.enable"),
                        eq("provider"),
                        eq("openrouter"),
                        eq("succeeded"),
                        anyMap());
    }

    @Test
    @DisplayName("another workspace's private provider is not found, and an unknown one neither")
    void anotherWorkspacesPrivateProviderIsNotFound() {
        assertThatThrownBy(() -> controller.disable("c-private"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(() -> controller.enable("c-private"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(() -> controller.disable("no-such-provider"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));

        assertThat(controller.list()).extracting(ProviderController.ProviderView::id).doesNotContain("c-private");
        assertThat(controller.models()).extracting(ProviderController.ModelView::modelId).doesNotContain("c-model");
        assertThat(registry.model(ORG_A.toString(), "c-private", "c-model")).isEmpty();
        assertThat(registry.models(ORG_A.toString())).extracting(ModelSpec::modelId).doesNotContain("c-model");
        signInTo(ORG_C);
        assertThat(controller.models()).extracting(ProviderController.ModelView::modelId).contains("c-model");
        signInTo(ORG_A);
        assertThat(providerRows.get("c-private").isEnabled()).isTrue();
        verify(settings, never()).upsertEnabled(any(), anyString(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("a workspace changes the provider it added for itself directly")
    void ownProviderIsChangedDirectly() {
        assertThat(view(ORG_C, "c-private").enabled()).isTrue();

        controller.disable("c-private");

        assertThat(providerRows.get("c-private").isEnabled()).isFalse();
        assertThat(view(ORG_C, "c-private").enabled()).isFalse();
        // Its own switch is off, but the provider is still offered to the workspace that owns it.
        assertThat(view(ORG_C, "c-private").platformEnabled()).isTrue();

        controller.enable("c-private");
        assertThat(view(ORG_C, "c-private").enabled()).isTrue();
        verify(settings, never()).upsertEnabled(any(), anyString(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("turning off the last provider the workspace routing can use is allowed, with a plain warning")
    void turningOffTheLastProviderWarns() {
        when(policies.resolve(eq(ORG_A), isNull()))
                .thenReturn(chain(RoutingPolicy.ExhaustedBehaviour.FAIL_CLOSED, "openrouter/" + LLAMA));

        ProviderController.ProviderView off = controller.disable("openrouter");

        assertThat(off.enabled()).isFalse();
        assertThat(off.warning()).contains("OpenRouter").contains("the workspace routing");
        assertThat(view(ORG_A, "openrouter").enabled()).isFalse();

        // A policy that falls back to the sandbox still has somewhere to go: nothing to warn about.
        signInTo(ORG_A);
        controller.enable("openrouter");
        when(policies.resolve(eq(ORG_A), isNull()))
                .thenReturn(chain(RoutingPolicy.ExhaustedBehaviour.DEGRADE_TO_SANDBOX, "openrouter/" + LLAMA));
        assertThat(controller.disable("openrouter").warning()).isNull();
    }

    @Test
    @DisplayName("a provider seeded off can be turned on by workspace A, and workspace B still has it off")
    void seededOffProviderTurnsOnForOneWorkspace() {
        String groq = "groq/llama-3.1-8b-instant";
        assertThat(view(ORG_A, "groq").enabled()).isFalse();
        assertThat(view(ORG_A, "groq").platformEnabled()).isTrue();

        signInTo(ORG_A);
        assertThat(controller.enable("groq").enabled()).isTrue();

        assertThat(view(ORG_A, "groq").enabled()).isTrue();
        assertThat(view(ORG_B, "groq").enabled()).isFalse();
        assertThat(providerRows.get("groq").isWorkspaceDefaultEnabled()).isFalse();
        assertThat(route(ORG_A, groq).provider()).isEqualTo("groq");
        assertThatThrownBy(() -> route(ORG_B, groq))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code())
                        .isEqualTo(ErrorCode.NO_MODEL_AVAILABLE));

        // And off again for A alone, which leaves B's default as it was.
        signInTo(ORG_A);
        controller.disable("groq");
        assertThat(view(ORG_A, "groq").enabled()).isFalse();
        assertThat(view(ORG_B, "groq").enabled()).isFalse();
    }

    @Test
    @DisplayName("a workspace cannot turn on a provider the installation has withdrawn")
    void cannotWidenThePlatform() {
        assertThatThrownBy(() -> controller.enable("bedrock"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("AWS Bedrock").contains("Contact support");
                });
        assertThat(view(ORG_A, "bedrock").enabled()).isFalse();
        assertThat(view(ORG_A, "bedrock").platformEnabled()).isFalse();
        assertThat(providerRows.get("bedrock").isEnabled()).isFalse();

        // A workspace's own "on" never outranks the withdrawal.
        settingRow(ORG_A, "bedrock").setEnabled(true);
        assertThat(view(ORG_A, "bedrock").enabled()).isFalse();
    }

    @Test
    @DisplayName("turning off a provider an agent's own routing depends on is allowed, naming the agent")
    void turningOffWarnsAboutAgents() {
        Agent ava = agent(ORG_A, "Ava", "active");
        Agent old = agent(ORG_A, "Old timer", "retired");
        agentPolicy(ORG_A, ava, "FAIL_CLOSED", "openrouter/" + LLAMA);
        agentPolicy(ORG_A, old, "FAIL_CLOSED", "openrouter/" + LLAMA);

        ProviderController.ProviderView off = controller.disable("openrouter");

        assertThat(off.enabled()).isFalse();
        assertThat(off.warning()).contains("OpenRouter").contains("Ava").doesNotContain("Old timer");
        // The chain keeps the model, so turning the provider back on restores it.
        assertThat(agentPolicyRows).isNotEmpty();
    }

    @Test
    @DisplayName("reads every agent's own routing in one query, however many agents there are")
    void readsAgentPoliciesInOneQuery() {
        for (int i = 0; i < 12; i++) {
            Agent agent = agent(ORG_A, "Agent " + i, "active");
            agentPolicy(ORG_A, agent, "FAIL_CLOSED", "openrouter/" + LLAMA, "sandbox/sandbox-1");
        }

        assertThat(controller.disable("openrouter").enabled()).isFalse();

        verify(modelPolicies, times(1)).findByOrgId(ORG_A);
        // The workspace's own policy is resolved once; no agent is resolved one by one.
        verify(policies, times(1)).resolve(any(), any());
        verify(policies).resolve(ORG_A, null);
    }

    @Test
    @DisplayName("an agent that follows the workspace policy, and one whose own chain is empty, are not refused over it")
    void agentsWithoutAChainOfTheirOwnAreLeftAlone() {
        agent(ORG_A, "Follower", "active");
        Agent emptied = agent(ORG_A, "Emptied", "active");
        agentPolicy(ORG_A, emptied, "FAIL_CLOSED");

        assertThat(controller.disable("openrouter").enabled()).isFalse();
    }

    @Test
    @DisplayName("an agent whose own chain falls back to the sandbox has somewhere to go")
    void agentPolicyThatDegradesIsNotGuarded() {
        Agent ava = agent(ORG_A, "Ava", "active");
        agentPolicy(ORG_A, ava, "DEGRADE_TO_SANDBOX", "openrouter/" + LLAMA);

        assertThat(controller.disable("openrouter").enabled()).isFalse();
    }

    @Test
    @DisplayName("takes the workspace's lock before it reads anything, so two toggles cannot both pass the check")
    void serialisesTogglesPerWorkspace() {
        controller.disable("openrouter");

        InOrder order = inOrder(settings);
        order.verify(settings).holdWorkspaceLock(ORG_A);
        order.verify(settings, atLeastOnce()).findById(any());
        order.verify(settings).upsertEnabled(eq(ORG_A), eq("openrouter"), eq(false), any(), any());
        verify(settings, never()).holdWorkspaceLock(ORG_B);
    }

    // ---- What a call teaches the platform --------------------------------------------------

    @Test
    @DisplayName("a 402 sets nothing aside: once the account has credit, the very next call uses the model")
    void creditFailureIsNotRemembered() {
        vendorAnswers.put("key-" + ORG_A, ProviderFailure.INSUFFICIENT_CREDIT);

        assertThat(route(ORG_A, "openrouter/" + LLAMA, "sandbox/sandbox-1").provider()).isEqualTo("sandbox");

        assertThat(registry.model(ORG_A.toString(), "openrouter", LLAMA).orElseThrow().isCurrentlyUnavailable())
                .isFalse();
        assertThat(modelView(ORG_A, LLAMA).unavailableUntil()).isNull();
        assertThat(availabilityRows).isEmpty();

        vendorAnswers.remove("key-" + ORG_A);
        assertThat(route(ORG_A, "openrouter/" + LLAMA).provider()).isEqualTo("openrouter");
    }

    @Test
    @DisplayName("an exhausted quota sets nothing aside and leaves the key alone")
    void quotaFailureIsNotRemembered() {
        vendorAnswers.put("key-" + ORG_A, ProviderFailure.QUOTA_EXHAUSTED);

        assertThat(route(ORG_A, "openrouter/" + LLAMA, "sandbox/sandbox-1").provider()).isEqualTo("sandbox");

        assertThat(modelView(ORG_A, LLAMA).unavailableUntil()).isNull();
        assertThat(view(ORG_A, "openrouter").credentialStatus()).isEqualTo("unknown");
        assertThat(route(ORG_B, "openrouter/" + LLAMA).provider()).isEqualTo("openrouter");
    }

    @Test
    @DisplayName("a 401 in workspace A shows the key as refused to A, never to B")
    void refusedKeyStaysInItsWorkspace() {
        vendorAnswers.put("key-" + ORG_A, ProviderFailure.AUTHENTICATION_FAILED);

        route(ORG_A, "openrouter/" + LLAMA, "sandbox/sandbox-1");

        assertThat(view(ORG_A, "openrouter").credentialStatus()).isEqualTo("rejected");
        assertThat(view(ORG_A, "openrouter").credentialCheckedAt()).isNotNull();
        assertThat(view(ORG_B, "openrouter").credentialStatus()).isEqualTo("unknown");
        assertThat(view(ORG_B, "openrouter").circuitState()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("a success in workspace B does not clear workspace A's refused key; A's own success does")
    void successElsewhereDoesNotClearARejection() {
        vendorAnswers.put("key-" + ORG_A, ProviderFailure.AUTHENTICATION_FAILED);
        route(ORG_A, "openrouter/" + LLAMA, "sandbox/sandbox-1");

        assertThat(route(ORG_B, "openrouter/" + LLAMA).provider()).isEqualTo("openrouter");
        assertThat(view(ORG_A, "openrouter").credentialStatus()).isEqualTo("rejected");

        vendorAnswers.remove("key-" + ORG_A);
        assertThat(route(ORG_A, "openrouter/" + LLAMA).provider()).isEqualTo("openrouter");
        assertThat(view(ORG_A, "openrouter").credentialStatus()).isEqualTo("valid");
    }

    @Test
    @DisplayName("repeated 404s set nothing aside: every call asks the model again")
    void notFoundIsNotRemembered() {
        vendorAnswers.put("key-" + ORG_A, ProviderFailure.MODEL_NOT_FOUND);

        for (int i = 0; i < 3; i++) {
            route(ORG_A, "openrouter/" + LLAMA, "sandbox/sandbox-1");
        }

        assertThat(modelRows.get(new LlmModelEntity.Key("openrouter", LLAMA)).getUnavailableUntil())
                .isNull();
        assertThat(availabilityRows).isEmpty();
        vendorAnswers.remove("key-" + ORG_A);
        assertThat(route(ORG_A, "openrouter/" + LLAMA).provider()).isEqualTo("openrouter");
    }

    @Test
    @DisplayName("404s from two workspaces set nothing aside for anyone")
    void twoWorkspacesNotFoundSetsNothingAside() {
        vendorAnswers.put("key-" + ORG_A, ProviderFailure.MODEL_NOT_FOUND);
        vendorAnswers.put("key-" + ORG_B, ProviderFailure.MODEL_NOT_FOUND);

        route(ORG_A, "openrouter/" + LLAMA, "sandbox/sandbox-1");
        route(ORG_B, "openrouter/" + LLAMA, "sandbox/sandbox-1");

        assertThat(modelRows.get(new LlmModelEntity.Key("openrouter", LLAMA)).getUnavailableUntil())
                .isNull();
        assertThat(registry.model(ORG_C.toString(), "openrouter", LLAMA).orElseThrow().isCurrentlyUnavailable())
                .isFalse();
    }

    @Test
    @DisplayName("an earlier 404 that has lapsed does not count towards a platform-wide note")
    void lapsedNotFoundDoesNotCount() {
        availabilityRows.put(
                new WorkspaceModelAvailability.Key(ORG_B, "openrouter", LLAMA),
                new WorkspaceModelAvailability(
                        ORG_B, "openrouter", LLAMA, Instant.now().minusSeconds(1), "MODEL_NOT_FOUND", "gone"));

        registry.markModelUnavailable(
                ORG_A.toString(), "openrouter", LLAMA, ProviderFailure.MODEL_NOT_FOUND, Duration.ofMinutes(15), "gone");

        assertThat(modelRows.get(new LlmModelEntity.Key("openrouter", LLAMA)).getUnavailableUntil())
                .isNull();
    }

    @Test
    @DisplayName("a platform-wide 404 note never shortens a longer one already on the platform row")
    void retiredModelNoteNeverShortensALongerOne() {
        Instant operatorNote = Instant.now().plus(Duration.ofHours(3));
        ReflectionTestUtils.setField(
                modelRows.get(new LlmModelEntity.Key("openrouter", LLAMA)), "unavailableUntil", operatorNote);

        registry.markModelUnavailable(
                ORG_A.toString(), "openrouter", LLAMA, ProviderFailure.MODEL_NOT_FOUND, Duration.ofHours(6), "gone");
        registry.markModelUnavailable(
                ORG_B.toString(), "openrouter", LLAMA, ProviderFailure.MODEL_NOT_FOUND, Duration.ofHours(6), "gone");

        assertThat(modelRows.get(new LlmModelEntity.Key("openrouter", LLAMA)).getUnavailableUntil())
                .isEqualTo(operatorNote);
    }

    @Test
    @DisplayName("an account failure with no workspace to name is never written platform-wide")
    void accountFailureWithoutAWorkspaceIsNotPlatformWide() {
        for (ProviderFailure cause : List.of(
                ProviderFailure.INSUFFICIENT_CREDIT, ProviderFailure.QUOTA_EXHAUSTED, ProviderFailure.MODEL_NOT_FOUND)) {
            registry.markModelUnavailable(null, "openrouter", LLAMA, cause, Duration.ofHours(6), "empty account");
        }

        assertThat(modelRows.get(new LlmModelEntity.Key("openrouter", LLAMA)).getUnavailableUntil())
                .isNull();
        assertThat(availabilityRows).isEmpty();
        assertThat(registry.model(ORG_B.toString(), "openrouter", LLAMA).orElseThrow().isCurrentlyUnavailable())
                .isFalse();
    }

    @Test
    @DisplayName("notes left from before are ignored: the model is offered and shown as available")
    void oldNotesAreIgnored() {
        Instant later = Instant.now().plus(Duration.ofHours(2));
        ReflectionTestUtils.setField(
                modelRows.get(new LlmModelEntity.Key("openrouter", LLAMA)), "unavailableUntil", later);
        availabilityRows.put(
                new WorkspaceModelAvailability.Key(ORG_A, "openrouter", LLAMA),
                new WorkspaceModelAvailability(
                        ORG_A, "openrouter", LLAMA, later, "INSUFFICIENT_CREDIT", "out of credit"));

        assertThat(modelView(ORG_A, LLAMA).unavailableUntil()).isNull();
        assertThat(registry.model(ORG_A.toString(), "openrouter", LLAMA).orElseThrow().isCurrentlyUnavailable())
                .isFalse();
        assertThat(route(ORG_A, "openrouter/" + LLAMA).provider()).isEqualTo("openrouter");
    }

    // ---- Fixtures --------------------------------------------------------------------------

    private static void signInTo(UUID orgId) {
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), orgId.toString(), "owner", Set.of(), 0L));
    }

    private ProviderController.ProviderView view(UUID orgId, String providerId) {
        signInTo(orgId);
        return controller.list().stream()
                .filter(view -> view.id().equals(providerId))
                .findFirst()
                .orElseThrow();
    }

    private ProviderController.ModelView modelView(UUID orgId, String modelId) {
        signInTo(orgId);
        return controller.models().stream()
                .filter(view -> view.modelId().equals(modelId))
                .findFirst()
                .orElseThrow();
    }

    private ChatResponse route(UUID orgId, String... candidates) {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user("hello")))
                .timeout(Duration.ofSeconds(5))
                .build();
        return router.route(
                request,
                chain(RoutingPolicy.ExhaustedBehaviour.FAIL_CLOSED, candidates),
                new ModelRouter.CallContext(orgId.toString(), null, null));
    }

    private static RoutingPolicy chain(RoutingPolicy.ExhaustedBehaviour exhausted, String... candidates) {
        List<RoutingPolicy.Candidate> list = java.util.Arrays.stream(candidates)
                .map(candidate -> candidate.split("/", 2))
                .map(parts -> RoutingPolicy.Candidate.of(parts[0], parts[1]))
                .toList();
        return new RoutingPolicy(list, exhausted, 1, Duration.ofSeconds(20), true);
    }

    private void provider(
            String id,
            String name,
            String kind,
            String credentialRef,
            boolean enabled,
            boolean workspaceDefault,
            int priority,
            UUID orgId) {
        LlmProviderEntity entity = new LlmProviderEntity();
        entity.setId(id);
        entity.setDisplayName(name);
        entity.setKind(kind);
        entity.setCredentialRef(credentialRef);
        entity.setEnabled(enabled);
        entity.setWorkspaceDefaultEnabled(workspaceDefault);
        ReflectionTestUtils.setField(entity, "priority", priority);
        ReflectionTestUtils.setField(entity, "orgId", orgId);
        providerRows.put(id, entity);
    }

    private void model(String providerId, String modelId) {
        LlmModelEntity entity = new LlmModelEntity();
        ReflectionTestUtils.setField(entity, "providerId", providerId);
        ReflectionTestUtils.setField(entity, "modelId", modelId);
        ReflectionTestUtils.setField(entity, "displayName", modelId);
        ReflectionTestUtils.setField(entity, "contextWindow", 128_000);
        ReflectionTestUtils.setField(entity, "maxOutputTokens", 4096);
        ReflectionTestUtils.setField(entity, "supportsTools", true);
        ReflectionTestUtils.setField(entity, "supportsJsonMode", true);
        modelRows.put(new LlmModelEntity.Key(providerId, modelId), entity);
    }

    /** Stores an agent's own routing policy, as the one query for a workspace's policies returns it. */
    private void agentPolicy(UUID orgId, Agent agent, String exhausted, String... candidates) {
        ModelPolicyEntity policy = new ModelPolicyEntity();
        policy.setOrgId(orgId);
        policy.setAgentId(agent.getId());
        policy.setExhaustedBehaviour(exhausted);
        List<ModelPolicyCandidate> chain = new ArrayList<>();
        for (int position = 0; position < candidates.length; position++) {
            String[] parts = candidates[position].split("/", 2);
            chain.add(ModelPolicyCandidate.of(policy.getId(), position, parts[0], parts[1]));
        }
        policy.setCandidates(chain);
        agentPolicyRows.computeIfAbsent(orgId, key -> new ArrayList<>()).add(policy);
    }

    private Agent agent(UUID orgId, String name, String status) {
        Agent agent = new Agent();
        ReflectionTestUtils.setField(agent, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(agent, "orgId", orgId);
        ReflectionTestUtils.setField(agent, "name", name);
        agent.setStatus(status);
        agentRows.computeIfAbsent(orgId, key -> new ArrayList<>()).add(agent);
        return agent;
    }

    private Providers providersTable() {
        Providers providers = mock(Providers.class);
        when(providers.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(providerRows.get(call.<String>getArgument(0))));
        when(providers.findVisibleTo(any())).thenAnswer(call -> providerRows.values().stream()
                .filter(row -> row.isVisibleTo(call.getArgument(0)))
                .sorted(java.util.Comparator.comparingInt(LlmProviderEntity::getPriority))
                .toList());
        when(providers.save(any())).thenAnswer(call -> call.getArgument(0));
        return providers;
    }

    private Models modelsTable() {
        Models models = mock(Models.class);
        when(models.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(modelRows.get(call.<LlmModelEntity.Key>getArgument(0))));
        when(models.findAllEnabled()).thenAnswer(call -> modelRows.values().stream()
                .filter(LlmModelEntity::isEnabled)
                .toList());
        when(models.findByProviderIdAndEnabledTrue(any())).thenAnswer(call -> modelRows.values().stream()
                .filter(row -> row.getProviderId().equals(call.getArgument(0)) && row.isEnabled())
                .toList());
        when(models.save(any())).thenAnswer(call -> call.getArgument(0));
        return models;
    }

    private WorkspaceProviderSettings settingsTable() {
        WorkspaceProviderSettings table = mock(WorkspaceProviderSettings.class);
        when(table.findByOrgId(any())).thenAnswer(call -> settingRows.values().stream()
                .filter(row -> row.getOrgId().equals(call.getArgument(0)))
                .toList());
        when(table.findById(any()))
                .thenAnswer(call ->
                        Optional.ofNullable(settingRows.get(call.<WorkspaceProviderSetting.Key>getArgument(0))));
        when(table.upsertEnabled(any(), anyString(), anyBoolean(), any(), any())).thenAnswer(call -> {
            WorkspaceProviderSetting row = settingRow(call.getArgument(0), call.getArgument(1));
            row.setEnabled(call.getArgument(2));
            row.setUpdatedAt(call.getArgument(3));
            row.setUpdatedBy(call.getArgument(4));
            return 1;
        });
        when(table.markCredentialRejected(any(), anyString(), any())).thenAnswer(call -> {
            WorkspaceProviderSetting row = settingRow(call.getArgument(0), call.getArgument(1));
            row.setCredentialStatus(WorkspaceProviderSetting.REJECTED);
            row.setCredentialCheckedAt(call.getArgument(2));
            return 1;
        });
        when(table.clearRejectedCredential(any(), anyString(), any())).thenAnswer(call -> {
            WorkspaceProviderSetting row =
                    settingRows.get(new WorkspaceProviderSetting.Key(call.getArgument(0), call.getArgument(1)));
            if (row == null || !WorkspaceProviderSetting.REJECTED.equals(row.getCredentialStatus())) {
                return 0;
            }
            row.setCredentialStatus(WorkspaceProviderSetting.VALID);
            row.setCredentialCheckedAt(call.getArgument(2));
            return 1;
        });
        return table;
    }

    private WorkspaceProviderSetting settingRow(UUID orgId, String providerId) {
        return settingRows.computeIfAbsent(
                new WorkspaceProviderSetting.Key(orgId, providerId),
                key -> new WorkspaceProviderSetting(orgId, providerId));
    }

    private WorkspaceModelAvailabilities availabilityTable() {
        WorkspaceModelAvailabilities table = mock(WorkspaceModelAvailabilities.class);
        when(table.findByOrgId(any())).thenAnswer(call -> availabilityRows.values().stream()
                .filter(row -> row.getOrgId().equals(call.getArgument(0)))
                .toList());
        when(table.findById(any())).thenAnswer(
                call -> Optional.ofNullable(availabilityRows.get(call.<WorkspaceModelAvailability.Key>getArgument(0))));
        when(table.upsert(any(), anyString(), anyString(), any(), anyString(), any())).thenAnswer(call -> {
            UUID orgId = call.getArgument(0);
            String providerId = call.getArgument(1);
            String modelId = call.getArgument(2);
            Instant until = call.getArgument(3);
            WorkspaceModelAvailability.Key key = new WorkspaceModelAvailability.Key(orgId, providerId, modelId);
            WorkspaceModelAvailability existing = availabilityRows.get(key);
            // As the SQL does: a note still in force is never shortened.
            if (existing != null && existing.getUnavailableUntil().isAfter(until)) {
                return 0;
            }
            availabilityRows.put(
                    key,
                    new WorkspaceModelAvailability(
                            orgId, providerId, modelId, until, call.getArgument(4), call.getArgument(5)));
            return 1;
        });
        when(table.findOtherWorkspacesReporting(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(call -> availabilityRows.values().stream()
                        .filter(row -> row.getProviderId().equals(call.getArgument(0))
                                && row.getModelId().equals(call.getArgument(1))
                                && row.getCause().equals(call.getArgument(2))
                                && !row.getOrgId().equals(call.getArgument(3))
                                && row.getUnavailableUntil().isAfter(call.getArgument(4)))
                        .map(WorkspaceModelAvailability::getOrgId)
                        .distinct()
                        .toList());
        return table;
    }

    private static PlatformProperties properties() {
        PlatformProperties properties = mock(PlatformProperties.class);
        when(properties.resilience()).thenReturn(new PlatformProperties.Resilience(
                50,
                10,
                5,
                Duration.ofSeconds(30),
                3,
                3,
                Duration.ofMillis(1),
                Duration.ofMillis(5),
                2.0,
                false,
                25,
                Duration.ofSeconds(60),
                Map.of()));
        return properties;
    }

    /** One vendor's adapter, answering each workspace's key as {@link #vendorAnswers} says. */
    private final class Vendor implements ChatProvider {
        private final ProviderDescriptor.Kind kind;

        Vendor(ProviderDescriptor.Kind kind) {
            this.kind = kind;
        }

        @Override
        public ProviderDescriptor.Kind kind() {
            return kind;
        }

        @Override
        public Mono<ChatResponse> complete(
                ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
            ProviderFailure failure = credential == null ? null : vendorAnswers.get(credential);
            if (failure != null) {
                return Mono.error(ProviderException.of(failure, provider.id(), model.modelId(), "scripted"));
            }
            return Mono.just(new ChatResponse(
                    "answer from " + provider.id(),
                    List.of(),
                    FinishReason.STOP,
                    TokenUsage.of(10, 5),
                    provider.id(),
                    model.modelId(),
                    Duration.ofMillis(1),
                    List.<AttemptRecord>of(),
                    Map.of()));
        }

        @Override
        public Flux<ChatChunk> stream(
                ProviderDescriptor provider, ModelSpec model, ChatRequest request, String credential) {
            return Flux.just(ChatChunk.terminal(FinishReason.STOP, TokenUsage.NONE));
        }

        @Override
        public Mono<Boolean> healthCheck(ProviderDescriptor provider, String credential) {
            return Mono.just(true);
        }
    }
}
