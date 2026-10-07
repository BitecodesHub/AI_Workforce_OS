package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import reactor.core.publisher.Mono;

import com.fasterxml.jackson.databind.ObjectMapper;

import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ModelSpec;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.model.ProviderException;
import os.aiworkforce.llm.model.ProviderFailure;
import os.aiworkforce.llm.router.ModelRouter;
import os.aiworkforce.llm.spi.ChatProvider;
import os.aiworkforce.orchestrator.domain.LlmModelEntity;
import os.aiworkforce.orchestrator.domain.LlmProviderEntity;
import os.aiworkforce.orchestrator.domain.WorkspaceProviderSetting;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ModelPolicies;
import os.aiworkforce.orchestrator.repository.Models;
import os.aiworkforce.orchestrator.repository.Providers;
import os.aiworkforce.orchestrator.repository.WorkspaceModelAvailabilities;
import os.aiworkforce.orchestrator.repository.WorkspaceProviderSettings;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry;
import os.aiworkforce.orchestrator.service.RoutingPolicyResolver;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Checking a pasted key before it is stored: what each answer from the provider is called, that a
 * key which works is recorded for the asking workspace alone, that nothing the provider says back
 * is repeated, and that a workspace cannot use the check to guess at keys.
 *
 * <p>The registry is the real one over in-memory tables; only the provider's adapter is a stand-in.
 */
class ProviderKeyTestTest {

    private static final UUID ORG_A = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID ORG_B = UUID.fromString("00000000-0000-7000-8000-0000000000b2");
    private static final String KEY = "sk-live-do-not-echo-1234567890";

    private final Map<String, LlmProviderEntity> providerRows = new LinkedHashMap<>();
    private final Map<LlmModelEntity.Key, LlmModelEntity> modelRows = new LinkedHashMap<>();

    private Providers providers;
    private WorkspaceProviderSettings settings;
    private ChatProvider adapter;
    private AuditClient audit;
    private ProviderController controller;
    private final AtomicLong now = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        provider("openrouter", "OpenRouter", "OPENAI_COMPATIBLE", true, null);
        provider("bedrock", "AWS Bedrock", "BEDROCK", true, null);
        provider("sandbox", "Offline sandbox", "SANDBOX", true, null);
        provider("withdrawn", "Withdrawn", "OPENAI_COMPATIBLE", false, null);
        provider("b-private", "B's endpoint", "OPENAI_COMPATIBLE", true, ORG_B);
        model("openrouter", "pricey-chat", 4096, "3", "15");
        model("openrouter", "cheap-chat", 4096, "0.12", "0.30");
        model("openrouter", "embedder", 1, "0.02", "0");
        model("bedrock", "bedrock-chat", 4096, "1", "1");
        model("sandbox", "sandbox-1", 4096, "0", "0");
        model("withdrawn", "w-chat", 4096, "1", "1");
        model("b-private", "b-chat", 4096, "1", "1");

        providers = mock(Providers.class);
        when(providers.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(providerRows.get(call.<String>getArgument(0))));
        when(providers.findVisibleTo(any())).thenAnswer(call -> providerRows.values().stream()
                .filter(row -> row.isVisibleTo(call.getArgument(0)))
                .toList());
        Models models = mock(Models.class);
        when(models.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(modelRows.get(call.<LlmModelEntity.Key>getArgument(0))));
        when(models.findAllEnabled()).thenAnswer(call -> List.copyOf(modelRows.values()));
        when(models.findByProviderIdAndEnabledTrue(any())).thenAnswer(call -> modelRows.values().stream()
                .filter(row -> row.getProviderId().equals(call.getArgument(0)))
                .toList());
        settings = mock(WorkspaceProviderSettings.class);
        when(settings.findByOrgId(any())).thenReturn(List.of());
        when(settings.findById(any())).thenReturn(Optional.empty());
        WorkspaceModelAvailabilities availability = mock(WorkspaceModelAvailabilities.class);
        when(availability.findByOrgId(any())).thenReturn(List.of());
        when(availability.findById(any())).thenReturn(Optional.empty());

        JpaProviderRegistry registry = new JpaProviderRegistry(providers, models, settings, availability);
        audit = mock(AuditClient.class);
        controller = new ProviderController(
                providers,
                models,
                mock(ModelRouter.class),
                registry,
                settings,
                mock(RoutingPolicyResolver.class),
                mock(ModelPolicies.class),
                mock(Agents.class),
                audit);

        adapter = mock(ChatProvider.class);
        when(adapter.kind()).thenReturn(ProviderDescriptor.Kind.OPENAI_COMPATIBLE);
        when(adapter.complete(any(), any(), any(), any())).thenReturn(Mono.empty());
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        controller.setKeyTesting(List.of(adapter), transactions);
        controller.clock = now::get;

        signInTo(ORG_A);
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    // ---- What each answer is called ----------------------------------------------------------

    @Test
    @DisplayName("a key the provider takes is valid, checked with the cheapest chat model and one token")
    void aKeyThatWorksIsValid() {
        ProviderController.KeyTestResult result = controller.test("openrouter", request("  " + KEY + "  "));

        assertThat(result.result()).isEqualTo(ProviderController.KeyCheck.VALID);
        assertThat(result.message()).isEqualTo("OpenRouter accepted the key.");

        ArgumentCaptor<ModelSpec> model = ArgumentCaptor.forClass(ModelSpec.class);
        ArgumentCaptor<ChatRequest> call = ArgumentCaptor.forClass(ChatRequest.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(adapter).complete(any(), model.capture(), call.capture(), key.capture());
        assertThat(model.getValue().modelId()).isEqualTo("cheap-chat");
        assertThat(call.getValue().maxOutputTokens()).isEqualTo(1);
        assertThat(key.getValue()).isEqualTo(KEY);
    }

    @Test
    @DisplayName("a key the provider refuses is rejected, and nothing is recorded for it")
    void aRefusedKeyIsRejectedAndSavesNothing() {
        failWith(ProviderFailure.AUTHENTICATION_FAILED, "{\"error\":\"bad key " + KEY + "\"}");

        ProviderController.KeyTestResult result = controller.test("openrouter", request(KEY));

        assertThat(result.result()).isEqualTo(ProviderController.KeyCheck.REJECTED);
        assertThat(result.message()).startsWith("Key refused.");
        verify(settings, never()).saveAndFlush(any());
        verify(providers, never()).save(any());
    }

    @Test
    @DisplayName("an account with no credit is told apart from a refused key")
    void noCreditIsNotARefusal() {
        failWith(ProviderFailure.INSUFFICIENT_CREDIT, "out of credit");

        ProviderController.KeyTestResult result = controller.test("openrouter", request(KEY));

        assertThat(result.result()).isEqualTo(ProviderController.KeyCheck.NO_CREDIT);
        assertThat(result.message()).contains("no credit left");
        verify(settings, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a provider that cannot be reached, a stalled call and a throttle are a network error, saved as nothing")
    void troubleThatIsNotTheKeysIsANetworkError() {
        for (ProviderFailure failure : List.of(
                ProviderFailure.NETWORK_ERROR,
                ProviderFailure.TIMEOUT,
                ProviderFailure.SERVER_ERROR,
                ProviderFailure.RATE_LIMITED)) {
            failWith(failure, "trouble");
            signInTo(ORG_A);
            now.addAndGet(120_000L);

            ProviderController.KeyTestResult result = controller.test("openrouter", request(KEY));

            assertThat(result.result()).as(failure.name()).isEqualTo(ProviderController.KeyCheck.NETWORK_ERROR);
        }
        // A call that ran out of time inside the blocking read has no classified failure at all.
        when(adapter.complete(any(), any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("Timeout on blocking read for 20000000000 NANOSECONDS")));
        now.addAndGet(120_000L);
        assertThat(controller.test("openrouter", request(KEY)).result())
                .isEqualTo(ProviderController.KeyCheck.NETWORK_ERROR);
        verify(settings, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a provider that complained about the call after taking the key had accepted it; a bare 400 says nothing")
    void aComplaintAboutTheRequestMeansTheKeyWasAccepted() {
        assertThat(ProviderController.classify(ProviderFailure.MODEL_NOT_FOUND)).isEqualTo(ProviderController.KeyCheck.VALID);
        // A catch-all 400 is where a refused key can hide, so with nothing more said it is not called valid.
        assertThat(ProviderController.classify(ProviderFailure.INVALID_REQUEST))
                .isEqualTo(ProviderController.KeyCheck.NETWORK_ERROR);
        assertThat(ProviderController.classify(ProviderFailure.AUTHORISATION_FAILED))
                .isEqualTo(ProviderController.KeyCheck.REJECTED);
        assertThat(ProviderController.classify(ProviderFailure.QUOTA_EXHAUSTED))
                .isEqualTo(ProviderController.KeyCheck.NO_CREDIT);
        assertThat(ProviderController.classify(null)).isEqualTo(ProviderController.KeyCheck.VALID);
    }

    // ---- Where a good key is recorded ---------------------------------------------------------

    @Test
    @DisplayName("a key that works is recorded as valid in the asking workspace's own row, never on the shared one")
    void validIsRecordedForTheAskingWorkspaceOnly() {
        controller.test("openrouter", request(KEY));

        ArgumentCaptor<WorkspaceProviderSetting> saved = ArgumentCaptor.forClass(WorkspaceProviderSetting.class);
        verify(settings).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOrgId()).isEqualTo(ORG_A);
        assertThat(saved.getValue().getProviderId()).isEqualTo("openrouter");
        assertThat(saved.getValue().getCredentialStatus()).isEqualTo("valid");
        assertThat(saved.getValue().getCredentialCheckedAt()).isNotNull();
        // The workspace's on/off choice is left as it was: a check says nothing about it.
        assertThat(saved.getValue().getEnabled()).isNull();
        verify(settings).holdWorkspaceLock(ORG_A);
        verify(providers, never()).save(any());
        verify(audit)
                .record(eq(ORG_A), any(Actor.class), eq("provider.test_key"), eq("provider"), eq("openrouter"), eq("succeeded"), anyMap());
    }

    @Test
    @DisplayName("a key that works keeps what the workspace's row already says about the provider")
    void validKeepsTheRowsOtherColumns() {
        WorkspaceProviderSetting existing = new WorkspaceProviderSetting(ORG_A, "openrouter");
        existing.setEnabled(false);
        existing.setCredentialStatus("rejected");
        when(settings.findById(new WorkspaceProviderSetting.Key(ORG_A, "openrouter"))).thenReturn(Optional.of(existing));

        controller.test("openrouter", request(KEY));

        verify(settings).saveAndFlush(existing);
        assertThat(existing.getEnabled()).isFalse();
        assertThat(existing.getCredentialStatus()).isEqualTo("valid");
    }

    // ---- What is never repeated ---------------------------------------------------------------

    @Test
    @DisplayName("neither the key nor the provider's own words are ever in the answer")
    void theAnswerNeverEchoesTheKeyOrTheProvidersBody() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (ProviderFailure failure : ProviderFailure.values()) {
            failWith(failure, "Incorrect API key provided: " + KEY + ". You can find it at https://example.test/keys");
            signInTo(ORG_A);
            now.addAndGet(120_000L);

            String body = json.writeValueAsString(controller.test("openrouter", request(KEY)));

            assertThat(body).as(failure.name()).doesNotContain(KEY).doesNotContain("Incorrect API key").doesNotContain("example.test");
        }
    }

    @Test
    @DisplayName("the answer names its result in the words the console switches on")
    void theResultIsWrittenInSnakeCase() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThat(json.writeValueAsString(new ProviderController.KeyTestResult(ProviderController.KeyCheck.NO_CREDIT, "m")))
                .contains("\"result\":\"no_credit\"");
        assertThat(json.writeValueAsString(new ProviderController.KeyTestResult(ProviderController.KeyCheck.NETWORK_ERROR, "m")))
                .contains("\"result\":\"network_error\"");
        assertThat(json.writeValueAsString(new ProviderController.KeyTestResult(ProviderController.KeyCheck.VALID, "m")))
                .contains("\"result\":\"valid\"");
        assertThat(json.writeValueAsString(new ProviderController.KeyTestResult(ProviderController.KeyCheck.REJECTED, "m")))
                .contains("\"result\":\"rejected\"");
    }

    // ---- Which providers can be checked -------------------------------------------------------

    @Test
    @DisplayName("Bedrock and the offline sandbox are not checked with one key")
    void bedrockAndTheSandboxAreRefused() {
        assertThatThrownBy(() -> controller.test("bedrock", request(KEY)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> controller.test("sandbox", request(KEY)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(adapter, never()).complete(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a provider the installation has withdrawn, and one that does not exist, are refused without a call")
    void withdrawnAndMissingProvidersAreRefused() {
        assertThatThrownBy(() -> controller.test("withdrawn", request(KEY)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_NOT_CONFIGURED));
        assertThatThrownBy(() -> controller.test("nope", request(KEY)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(adapter, never()).complete(any(), any(), any(), any());
    }

    @Test
    @DisplayName("another workspace's own provider is a 404, exactly as if it did not exist")
    void anotherWorkspacesProviderIsNotFound() {
        assertThatThrownBy(() -> controller.test("b-private", request(KEY)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(adapter, never()).complete(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("a paste with a space or a line break inside it is refused before any call, and uses no check")
    void aKeyWithWhitespaceInsideIsRefused() {
        for (String pasted : List.of("sk-one two", "sk-one\ntwo", "sk-one\ttwo")) {
            assertThatThrownBy(() -> controller.test("openrouter", request(pasted)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(e.details()).containsEntry("field", "value");
                    });
        }
        verify(adapter, never()).complete(any(), any(), any(), any());
        // None of those counted against the workspace's checks.
        for (int i = 0; i < ProviderController.TEST_LIMIT; i++) {
            controller.test("openrouter", request(KEY));
        }
    }

    // ---- The allowance -------------------------------------------------------------------------

    @Test
    @DisplayName("a workspace gets five checks a minute; the sixth is refused with how long to wait, and the window then reopens")
    void aWorkspaceIsLimitedToFiveChecksAMinute() {
        for (int i = 0; i < ProviderController.TEST_LIMIT; i++) {
            now.addAndGet(1_000L);
            controller.test("openrouter", request(KEY));
        }
        now.addAndGet(1_000L);

        assertThatThrownBy(() -> controller.test("openrouter", request(KEY))).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
            // The oldest check was 5 seconds ago, so 55 are left in the window.
            assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(55));
        });
        verify(adapter, org.mockito.Mockito.times(ProviderController.TEST_LIMIT)).complete(any(), any(), any(), any());

        now.addAndGet(60_000L);
        assertThat(controller.test("openrouter", request(KEY)).result()).isEqualTo(ProviderController.KeyCheck.VALID);
    }

    @Test
    @DisplayName("one workspace using up its checks leaves another's untouched")
    void theAllowanceIsPerWorkspace() {
        for (int i = 0; i < ProviderController.TEST_LIMIT; i++) {
            controller.test("openrouter", request(KEY));
        }
        assertThatThrownBy(() -> controller.test("openrouter", request(KEY))).isInstanceOf(ApiException.class);

        signInTo(ORG_B);
        assertThat(controller.test("openrouter", request(KEY)).result()).isEqualTo(ProviderController.KeyCheck.VALID);
    }

    @Test
    @DisplayName("a refused request does not use up a check")
    void aRefusedRequestIsNotCounted() {
        for (int i = 0; i < ProviderController.TEST_LIMIT + 2; i++) {
            assertThatThrownBy(() -> controller.test("bedrock", request(KEY))).isInstanceOf(ApiException.class);
        }
        assertThat(controller.test("openrouter", request(KEY)).result()).isEqualTo(ProviderController.KeyCheck.VALID);
    }

    // ---- Fixtures ------------------------------------------------------------------------------

    private static ProviderController.KeyTestRequest request(String value) {
        return new ProviderController.KeyTestRequest(value);
    }

    private void failWith(ProviderFailure failure, String rawBody) {
        when(adapter.complete(any(), any(), any(), any()))
                .thenReturn(Mono.error(new ProviderException(
                        failure, "openrouter", "cheap-chat", "Provider responded", 401, null, rawBody, null)));
    }

    private static void signInTo(UUID orgId) {
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), orgId.toString(), "owner", Set.of(), 0L));
    }

    private void provider(String id, String name, String kind, boolean enabled, UUID orgId) {
        LlmProviderEntity entity = new LlmProviderEntity();
        entity.setId(id);
        entity.setDisplayName(name);
        entity.setKind(kind);
        entity.setCredentialRef("provider:" + id);
        entity.setEnabled(enabled);
        entity.setWorkspaceDefaultEnabled(true);
        ReflectionTestUtils.setField(entity, "orgId", orgId);
        providerRows.put(id, entity);
    }

    private void model(String providerId, String modelId, int maxOutput, String inputCost, String outputCost) {
        LlmModelEntity entity = new LlmModelEntity();
        ReflectionTestUtils.setField(entity, "providerId", providerId);
        ReflectionTestUtils.setField(entity, "modelId", modelId);
        ReflectionTestUtils.setField(entity, "displayName", modelId);
        ReflectionTestUtils.setField(entity, "contextWindow", 128_000);
        ReflectionTestUtils.setField(entity, "maxOutputTokens", maxOutput);
        ReflectionTestUtils.setField(entity, "supportsTools", true);
        ReflectionTestUtils.setField(entity, "inputCostPerMillion", new BigDecimal(inputCost));
        ReflectionTestUtils.setField(entity, "outputCostPerMillion", new BigDecimal(outputCost));
        modelRows.put(new LlmModelEntity.Key(providerId, modelId), entity);
    }
}
