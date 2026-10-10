// @find: tests for model catalog service, catalog, returns only tool capable models free first and saves them, all includes models that cannot call tools, sends the workspace key as the provider expects, serves the cache for six hours and refresh asks again, caches per workspace, falls back to saved models when the provider fails, falls back when the provider cannot be reached, asks for akey before calling aprovider that needs one, ModelCatalogServiceTest, ModelCatalogService
// @what: Tests for ModelCatalogService in the orchestrator catalog package (13 test methods).
// @flow: Exercises ModelCatalogService
package os.aiworkforce.orchestrator.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import os.aiworkforce.llm.spi.CredentialResolver;
import os.aiworkforce.orchestrator.catalog.ModelCatalogService.CatalogueView;
import os.aiworkforce.orchestrator.catalog.ModelCatalogService.ModelOption;
import os.aiworkforce.orchestrator.catalog.ModelCatalogService.Source;
import os.aiworkforce.orchestrator.domain.LlmModelEntity;
import os.aiworkforce.orchestrator.domain.LlmProviderEntity;
import os.aiworkforce.orchestrator.repository.Models;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry.WorkspaceProvider;

/** Fetching, caching and falling back, with the network replaced by fixtures. */
class ModelCatalogServiceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String KEY = "sk-test-not-a-real-key";

    private final JpaProviderRegistry registry = mock(JpaProviderRegistry.class);
    private final Models models = mock(Models.class);
    private final CredentialResolver credentials = mock(CredentialResolver.class);
    private final ModelCatalogStore store = mock(ModelCatalogStore.class);

    /** The responses to serve, by URL prefix, and every request made. */
    private final Map<String, ModelListHttp.Response> responses = new java.util.HashMap<>();
    private final List<URI> requests = new ArrayList<>();
    private final List<Map<String, String>> headersSent = new ArrayList<>();
    private boolean unreachable;

    private Instant now = Instant.parse("2026-10-06T00:00:00Z");
    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        ModelListHttp http = (uri, headers) -> {
            requests.add(uri);
            headersSent.add(headers);
            if (unreachable) {
                throw new IOException("connect timed out");
            }
            return responses.entrySet().stream()
                    .filter(entry -> uri.toString().startsWith(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(new ModelListHttp.Response(404, "{}"));
        };
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        service = new ModelCatalogService(registry, models, credentials, store, new ObjectMapper(), http, clock);
        provider("openrouter", "OpenRouter", "OPENAI_COMPATIBLE", "https://openrouter.ai/api/v1");
        provider("nvidia", "NVIDIA NIM", "OPENAI_COMPATIBLE", "https://integrate.api.nvidia.com/v1");
        provider("groq", "Groq", "OPENAI_COMPATIBLE", "https://api.groq.com/openai/v1");
        provider("anthropic", "Anthropic", "ANTHROPIC", "https://api.anthropic.com");
        provider("gemini", "Google Gemini", "GEMINI", "https://generativelanguage.googleapis.com");
        when(credentials.lookup(anyString(), anyString())).thenReturn(CredentialResolver.NotFound.INSTANCE);
        when(models.findByProviderIdAndEnabledTrue(anyString())).thenReturn(List.of());
        when(models.findByProviderIdAndEnabledTrueAndSource(anyString(), anyString())).thenReturn(List.of());
    }

    private void provider(String id, String name, String kind, String baseUrl) {
        LlmProviderEntity entity = new LlmProviderEntity();
        entity.setId(id);
        entity.setDisplayName(name);
        entity.setKind(kind);
        entity.setBaseUrl(baseUrl);
        entity.setCredentialRef("provider:" + id);
        entity.setEnabled(true);
        when(registry.workspaceProvider(ORG, id)).thenReturn(Optional.of(new WorkspaceProvider(entity, true, "unknown", null)));
    }

    private void serve(String urlPrefix, String fixture) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/catalog/" + fixture + ".json")) {
            responses.put(urlPrefix, new ModelListHttp.Response(200, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    private void storeKey(String provider) {
        when(credentials.lookup(ORG.toString(), "provider:" + provider)).thenReturn(new CredentialResolver.Found(KEY));
    }

    // @find: test returns only tool capable models free first and saves them, model catalog service
    @Test
    void returnsOnlyToolCapableModelsFreeFirstAndSavesThem() throws IOException {
        serve("https://openrouter.ai/api/v1/models", "openrouter");

        CatalogueView view = service.list(ORG, "openrouter", false, false);

        assertThat(view.source()).isEqualTo(Source.live);
        assertThat(view.message()).isNull();
        assertThat(view.models()).extracting(ModelOption::id)
                .containsExactly(
                        "google/gemma-4-31b-it:free",
                        "inclusionai/ling-3.1-flash",
                        "anthropic/claude-sonnet-4",
                        "openrouter/auto");
        assertThat(view.freeCount()).isEqualTo(2);
        assertThat(view.total()).isEqualTo(4);
        verify(store).saveDiscovered(eq("openrouter"), anyList(), eq(now));
        // OpenRouter lists its models to anybody, so no stored key is not a reason to stop.
        assertThat(headersSent.get(0)).doesNotContainKey("Authorization");
    }

    // @find: test all includes models that cannot call tools, model catalog service
    @Test
    void allIncludesModelsThatCannotCallTools() throws IOException {
        serve("https://openrouter.ai/api/v1/models", "openrouter");

        CatalogueView view = service.list(ORG, "openrouter", false, true);

        assertThat(view.models()).extracting(ModelOption::id).contains("meta-llama/llama-3-8b-instruct");
        assertThat(view.models()).anyMatch(option -> !option.toolCalling());
    }

    // @find: test sends the workspace key as the provider expects, model catalog service
    @Test
    void sendsTheWorkspaceKeyAsTheProviderExpects() throws IOException {
        serve("https://api.groq.com/openai/v1/models", "groq");
        serve("https://api.anthropic.com/v1/models", "anthropic");
        serve("https://generativelanguage.googleapis.com/v1beta/models", "gemini");
        storeKey("groq");
        storeKey("anthropic");
        storeKey("gemini");

        service.list(ORG, "groq", false, false);
        service.list(ORG, "anthropic", false, false);
        service.list(ORG, "gemini", false, false);

        assertThat(headersSent.get(0)).containsEntry("Authorization", "Bearer " + KEY);
        assertThat(headersSent.get(1)).containsEntry("x-api-key", KEY).containsKey("anthropic-version");
        assertThat(headersSent.get(2)).containsEntry("x-goog-api-key", KEY);
        // Never in a query string, where it would be logged by every proxy on the way.
        assertThat(requests).noneMatch(uri -> uri.toString().contains(KEY));
    }

    // @find: test serves the cache for six hours and refresh asks again, model catalog service
    @Test
    void servesTheCacheForSixHoursAndRefreshAsksAgain() throws IOException {
        serve("https://integrate.api.nvidia.com/v1/models", "nvidia");

        assertThat(service.list(ORG, "nvidia", false, false).source()).isEqualTo(Source.live);
        now = now.plus(Duration.ofHours(5));
        assertThat(service.list(ORG, "nvidia", false, false).source()).isEqualTo(Source.cached);
        assertThat(requests).hasSize(1);

        // A refresh asks again.
        assertThat(service.list(ORG, "nvidia", true, false).source()).isEqualTo(Source.live);
        assertThat(requests).hasSize(2);
        // ...but not twice within the refresh floor.
        now = now.plusSeconds(5);
        assertThat(service.list(ORG, "nvidia", true, false).source()).isEqualTo(Source.cached);
        assertThat(requests).hasSize(2);

        now = now.plus(Duration.ofHours(7));
        assertThat(service.list(ORG, "nvidia", false, false).source()).isEqualTo(Source.live);
        assertThat(requests).hasSize(3);
    }

    // @find: test caches per workspace, model catalog service
    @Test
    void cachesPerWorkspace() throws IOException {
        serve("https://integrate.api.nvidia.com/v1/models", "nvidia");
        UUID other = UUID.randomUUID();
        Optional<WorkspaceProvider> nvidia = registry.workspaceProvider(ORG, "nvidia");
        when(registry.workspaceProvider(other, "nvidia")).thenReturn(nvidia);

        service.list(ORG, "nvidia", false, false);
        service.list(other, "nvidia", false, false);

        assertThat(requests).hasSize(2);
    }

    // @find: test falls back to saved models when the provider fails, model catalog service
    @Test
    void fallsBackToSavedModelsWhenTheProviderFails() {
        responses.put("https://openrouter.ai/api/v1/models", new ModelListHttp.Response(503, "{\"error\":\"" + KEY + "\"}"));
        LlmModelEntity seeded = model("openrouter", "google/gemini-2.5-flash", "Gemini 2.5 Flash", "seed", false, "0.30", "2.50");
        LlmModelEntity discovered = model("openrouter", "x/unpriced", "Unpriced", "discovered", false, "0", "0");
        LlmModelEntity freebie = model("openrouter", "x/free:free", "Free one", "discovered", true, "0", "0");
        when(models.findByProviderIdAndEnabledTrue("openrouter")).thenReturn(List.of(seeded));
        when(models.findByProviderIdAndEnabledTrueAndSource("openrouter", "discovered"))
                .thenReturn(List.of(discovered, freebie));

        CatalogueView view = service.list(ORG, "openrouter", true, false);

        assertThat(view.source()).isEqualTo(Source.saved);
        assertThat(view.message()).isEqualTo("Couldn't refresh the list from OpenRouter; showing saved models.");
        assertThat(view.models()).extracting(ModelOption::id)
                .containsExactly("x/free:free", "google/gemini-2.5-flash", "x/unpriced");
        ModelOption unpriced = view.models().get(2);
        // A discovered row's zero price is "not listed", not "free".
        assertThat(unpriced.free()).isFalse();
        assertThat(unpriced.pricePerMTokIn()).isNull();
        assertThat(view.models().get(1).pricePerMTokIn()).isEqualByComparingTo(new BigDecimal("0.30"));
        verify(store, never()).saveDiscovered(any(), any(), any());
    }

    // @find: test falls back when the provider cannot be reached, model catalog service
    @Test
    void fallsBackWhenTheProviderCannotBeReached() {
        unreachable = true;

        CatalogueView view = service.list(ORG, "nvidia", false, false);

        assertThat(view.source()).isEqualTo(Source.saved);
        assertThat(view.message()).isEqualTo("Couldn't refresh the list from NVIDIA NIM; showing saved models.");
    }

    // @find: test asks for akey before calling aprovider that needs one, model catalog service
    @Test
    void asksForAKeyBeforeCallingAProviderThatNeedsOne() {
        CatalogueView view = service.list(ORG, "groq", false, false);

        assertThat(view.source()).isEqualTo(Source.saved);
        assertThat(view.message()).isEqualTo("Store a key for Groq to load its full model list. Showing saved models.");
        assertThat(requests).isEmpty();
    }

    // @find: test borrows seeded names and prices when the listing has none, model catalog service
    @Test
    void borrowsSeededNamesAndPricesWhenTheListingHasNone() throws IOException {
        serve("https://api.groq.com/openai/v1/models", "groq");
        storeKey("groq");
        when(models.findByProviderIdAndEnabledTrue("groq"))
                .thenReturn(List.of(model("groq", "llama-3.3-70b-versatile", "Llama 3.3 70B", "seed", false, "0.59", "0.79")));

        CatalogueView view = service.list(ORG, "groq", false, false);

        ModelOption llama = view.models().stream()
                .filter(option -> option.id().equals("llama-3.3-70b-versatile"))
                .findFirst()
                .orElseThrow();
        assertThat(llama.displayName()).isEqualTo("Llama 3.3 70B");
        assertThat(llama.pricePerMTokIn()).isEqualByComparingTo(new BigDecimal("0.59"));
    }

    // ---- Amazon Bedrock ------------------------------------------------------------------------

    private static final String BEDROCK_KEYS = "{\"type\":\"access_key\",\"accessKeyId\":\"AKIAIOSFODNN7EXAMPLE\","
            + "\"secretAccessKey\":\"wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY\",\"region\":\"eu-west-1\"}";

    private void bedrock() {
        provider("bedrock", "AWS Bedrock", "BEDROCK", "");
        when(credentials.lookup(ORG.toString(), "provider:bedrock")).thenReturn(new CredentialResolver.Found(BEDROCK_KEYS));
    }

    // @find: test reads bedrocks live list for the credentials region signed, model catalog service
    @Test
    void readsBedrocksLiveListForTheCredentialsRegionSigned() throws IOException {
        bedrock();
        serve("https://bedrock.eu-west-1.amazonaws.com/foundation-models", "bedrock-foundation");
        serve("https://bedrock.eu-west-1.amazonaws.com/inference-profiles", "bedrock-profiles");

        CatalogueView view = service.list(ORG, "bedrock", false, false);

        assertThat(view.source()).isEqualTo(Source.live);
        assertThat(view.freeCount()).isZero();
        assertThat(view.total()).isEqualTo(10);
        assertThat(view.models()).extracting(ModelOption::id).contains("eu.anthropic.claude-sonnet-4-20250514-v1:0");
        assertThat(requests).extracting(URI::toString).containsExactly(
                "https://bedrock.eu-west-1.amazonaws.com/foundation-models?byOutputModality=TEXT",
                "https://bedrock.eu-west-1.amazonaws.com/inference-profiles?maxResults=1000&typeEquals=SYSTEM_DEFINED");
        assertThat(headersSent.get(0).get("Authorization"))
                .startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20261006/eu-west-1/bedrock/aws4_request");
        assertThat(headersSent).allSatisfy(headers -> assertThat(headers.values().toString()).doesNotContain("wJalr"));
        verify(store).saveDiscovered(eq("bedrock"), anyList(), eq(now));
    }

    // @find: test lists bedrocks on demand models when profiles cannot be read, model catalog service
    @Test
    void listsBedrocksOnDemandModelsWhenProfilesCannotBeRead() throws IOException {
        bedrock();
        serve("https://bedrock.eu-west-1.amazonaws.com/foundation-models", "bedrock-foundation");
        responses.put("https://bedrock.eu-west-1.amazonaws.com/inference-profiles", new ModelListHttp.Response(403, "{}"));

        CatalogueView view = service.list(ORG, "bedrock", false, false);

        assertThat(view.source()).isEqualTo(Source.live);
        assertThat(view.models()).extracting(ModelOption::id)
                .contains("amazon.nova-micro-v1:0")
                .noneMatch(id -> id.startsWith("eu."));
    }

    // @find: test falls back to saved bedrock models when aws refuses the list, model catalog service
    @Test
    void fallsBackToSavedBedrockModelsWhenAwsRefusesTheList() {
        bedrock();
        responses.put("https://bedrock.eu-west-1.amazonaws.com/foundation-models", new ModelListHttp.Response(403, "{}"));
        when(models.findByProviderIdAndEnabledTrue("bedrock"))
                .thenReturn(List.of(model("bedrock", "amazon.nova-micro-v1:0", "Nova Micro", "seed", false, "0.035", "0.14")));

        CatalogueView view = service.list(ORG, "bedrock", true, false);

        assertThat(view.source()).isEqualTo(Source.saved);
        assertThat(view.message()).isEqualTo("Couldn't refresh the list from AWS Bedrock; showing saved models.");
        assertThat(view.models()).extracting(ModelOption::id).containsExactly("amazon.nova-micro-v1:0");
    }

    // @find: test offers bedrock embedding models and falls back to the saved ones, model catalog service
    @Test
    void offersBedrockEmbeddingModelsAndFallsBackToTheSavedOnes() throws IOException {
        bedrock();
        LlmProviderEntity entity = registry.workspaceProvider(ORG, "bedrock").orElseThrow().entity();
        when(registry.workspaceProviders(ORG)).thenReturn(List.of(new WorkspaceProvider(entity, true, "valid", null)));
        serve("https://bedrock.eu-west-1.amazonaws.com/foundation-models?byOutputModality=EMBEDDING", "bedrock-embeddings");

        ModelCatalogService.EmbeddingCatalogue live = service.embeddingModels(ORG, false);
        assertThat(live.models()).extracting(ModelCatalogService.EmbeddingOption::id)
                .containsExactlyInAnyOrder("amazon.titan-embed-text-v2:0", "cohere.embed-multilingual-v3");
        assertThat(live.notes()).isEmpty();

        // Refused (an API key or a narrow policy without bedrock:ListFoundationModels): the saved rows.
        service.clear();
        responses.clear();
        LlmModelEntity titan = model("bedrock", "amazon.titan-embed-text-v2:0", "Titan Text Embeddings V2", "seed", false, "0.02", "0");
        ReflectionTestUtils.setField(titan, "maxOutputTokens", 1);
        ReflectionTestUtils.setField(titan, "supportsTools", false);
        when(models.findByProviderIdAndEnabledTrue("bedrock")).thenReturn(List.of(titan));

        ModelCatalogService.EmbeddingCatalogue saved = service.embeddingModels(ORG, true);
        assertThat(saved.models()).extracting(ModelCatalogService.EmbeddingOption::id)
                .containsExactly("amazon.titan-embed-text-v2:0");
        assertThat(saved.notes()).containsExactly("Couldn't read the embedding models of AWS Bedrock just now.");
    }

    private static LlmModelEntity model(
            String provider, String id, String name, String source, boolean free, String in, String out) {
        LlmModelEntity entity = new LlmModelEntity();
        ReflectionTestUtils.setField(entity, "providerId", provider);
        ReflectionTestUtils.setField(entity, "modelId", id);
        ReflectionTestUtils.setField(entity, "displayName", name);
        ReflectionTestUtils.setField(entity, "contextWindow", 128_000);
        ReflectionTestUtils.setField(entity, "maxOutputTokens", 4096);
        ReflectionTestUtils.setField(entity, "supportsTools", true);
        ReflectionTestUtils.setField(entity, "inputCostPerMillion", new BigDecimal(in));
        ReflectionTestUtils.setField(entity, "outputCostPerMillion", new BigDecimal(out));
        ReflectionTestUtils.setField(entity, "source", source);
        ReflectionTestUtils.setField(entity, "free", free);
        return entity;
    }
}
