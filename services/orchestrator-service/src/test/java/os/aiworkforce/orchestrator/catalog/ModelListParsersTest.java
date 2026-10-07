package os.aiworkforce.orchestrator.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Each provider's model list, from a trimmed copy of a real response, read into catalogue models. */
class ModelListParsersTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode fixture(String name) throws IOException {
        try (InputStream in = ModelListParsersTest.class.getResourceAsStream("/catalog/" + name + ".json")) {
            return JSON.readTree(in);
        }
    }

    private static Map<String, CatalogModel> byId(List<CatalogModel> models) {
        return models.stream().collect(Collectors.toMap(CatalogModel::id, Function.identity()));
    }

    @Test
    void openRouterReadsToolsFreePricesVisionAndNames() throws IOException {
        Map<String, CatalogModel> models = byId(ModelListParsers.openRouter(fixture("openrouter")));

        CatalogModel gemma = models.get("google/gemma-4-31b-it:free");
        assertThat(gemma.free()).isTrue();
        assertThat(gemma.toolCalling()).isTrue();
        assertThat(gemma.vision()).isTrue();
        assertThat(gemma.displayName()).isEqualTo("Google: Gemma 4 31B");
        assertThat(gemma.contextLength()).isEqualTo(262_144);
        assertThat(gemma.maxOutputTokens()).isEqualTo(32_768);
        assertThat(gemma.jsonMode()).isTrue();

        // Zero-priced without the ":free" suffix is free too.
        assertThat(models.get("inclusionai/ling-3.1-flash").free()).isTrue();

        CatalogModel sonnet = models.get("anthropic/claude-sonnet-4");
        assertThat(sonnet.free()).isFalse();
        assertThat(sonnet.pricePerMTokIn()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(sonnet.pricePerMTokOut()).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(sonnet.vision()).isTrue();

        assertThat(models.get("meta-llama/llama-3-8b-instruct").toolCalling()).isFalse();
        // An image generator lists "tools" but writes no text: not an agent model.
        assertThat(models.get("google/gemini-nano-banana-2.1").toolCalling()).isFalse();

        // A router's "-1" price is unknown, not free.
        CatalogModel auto = models.get("openrouter/auto");
        assertThat(auto.free()).isFalse();
        assertThat(auto.pricePerMTokIn()).isNull();
        assertThat(auto.toolCalling()).isTrue();
    }

    @Test
    void nvidiaKeepsKnownToolFamiliesAndDropsEmbeddingsGuardsAndRewardModels() throws IOException {
        List<CatalogModel> models = ModelListParsers.nvidia(fixture("nvidia"));
        List<String> tools = models.stream().filter(CatalogModel::toolCalling).map(CatalogModel::id).toList();

        assertThat(tools)
                .containsExactlyInAnyOrder(
                        "meta/llama-3.3-70b-instruct",
                        "nvidia/llama-3.1-nemotron-70b-instruct",
                        "qwen/qwen3-235b-a22b",
                        "deepseek-ai/deepseek-v4.1-flash",
                        "meta/llama-4-maverick-17b-128e-instruct",
                        "mistralai/mistral-large-2-instruct");
        assertThat(models).allMatch(CatalogModel::free);
        assertThat(models).allMatch(model -> ModelListParsers.NVIDIA_FREE_NOTE.equals(model.pricingNote()));
        Map<String, CatalogModel> byId = byId(models);
        assertThat(byId.get("meta/llama-3.3-70b-instruct").displayName()).isEqualTo("Llama 3.3 70B Instruct");
        assertThat(byId.get("meta/llama-4-maverick-17b-128e-instruct").vision()).isTrue();
        assertThat(byId.get("meta/llama-3.3-70b-instruct").vision()).isFalse();
    }

    @Test
    void groqSkipsInactiveSpeechAndCompoundModels() throws IOException {
        List<CatalogModel> models = ModelListParsers.openAiCompatible(fixture("groq"));
        Map<String, CatalogModel> byId = byId(models);

        assertThat(byId).doesNotContainKey("llama-3.1-8b-instant");
        assertThat(models.stream().filter(CatalogModel::toolCalling).map(CatalogModel::id))
                .containsExactlyInAnyOrder(
                        "llama-3.3-70b-versatile", "openai/gpt-oss-120b", "meta-llama/llama-4-scout-17b-16e-instruct");
        assertThat(byId.get("llama-3.3-70b-versatile").contextLength()).isEqualTo(131_072);
        assertThat(byId.get("llama-3.3-70b-versatile").free()).isFalse();
        assertThat(byId.get("openai/gpt-oss-120b").displayName()).isEqualTo("GPT OSS 120B");
    }

    @Test
    void openAiKeepsChatModelsAndNeverAnAccountsFineTunes() throws IOException {
        List<CatalogModel> models = ModelListParsers.openAi(fixture("openai"));

        assertThat(models.stream().filter(CatalogModel::toolCalling).map(CatalogModel::id))
                .containsExactlyInAnyOrder("gpt-4.1", "gpt-4o-mini", "gpt-5", "o4-mini", "gpt-3.5-turbo");
        assertThat(models).noneMatch(model -> model.id().startsWith("ft:"));
        Map<String, CatalogModel> byId = byId(models);
        assertThat(byId.get("gpt-4.1").contextLength()).isEqualTo(1_047_576);
        assertThat(byId.get("gpt-4.1").displayName()).isEqualTo("GPT 4.1");
        assertThat(byId.get("gpt-4o-mini").vision()).isTrue();
        assertThat(byId.get("gpt-3.5-turbo").vision()).isFalse();
    }

    @Test
    void anthropicUsesItsOwnNamesAndEveryClaudeTakesTools() throws IOException {
        List<CatalogModel> models = ModelListParsers.anthropic(fixture("anthropic"));

        assertThat(models).hasSize(3).allMatch(CatalogModel::toolCalling).allMatch(CatalogModel::vision);
        assertThat(byId(models).get("claude-sonnet-4-5-20250929").displayName()).isEqualTo("Claude Sonnet 4.5");
        assertThat(byId(models).get("claude-sonnet-4-5-20250929").contextLength()).isEqualTo(200_000);
    }

    @Test
    void geminiKeepsGenerateContentGeminiModelsOnly() throws IOException {
        List<CatalogModel> models = ModelListParsers.gemini(fixture("gemini"));

        assertThat(models.stream().filter(CatalogModel::toolCalling).map(CatalogModel::id))
                .containsExactlyInAnyOrder("gemini-2.5-flash", "gemini-2.5-pro");
        assertThat(byId(models).get("gemini-2.5-flash").contextLength()).isEqualTo(1_048_576);
        assertThat(byId(models).get("gemini-2.5-flash").displayName()).isEqualTo("Gemini 2.5 Flash");
    }

    @Test
    void codeModelsOfAToolFamilyNameAreNotMistakenForIt() {
        assertThat(ModelListParsers.familyCallsTools("ibm/granite-34b-code-instruct")).isFalse();
        assertThat(ModelListParsers.familyCallsTools("ibm/granite-3.0-8b-instruct")).isTrue();
        assertThat(ModelListParsers.familyCallsTools("mistralai/mistral-7b-instruct-v0.3")).isTrue();
        assertThat(ModelListParsers.familyCallsTools("nvidia/llama-3.2-nv-embedqa-1b-v1")).isFalse();
    }

    @Test
    void displayNamesReadLikeNames() {
        assertThat(ModelListParsers.displayName("nvidia/nemotron-3-super-120b-a12b"))
                .isEqualTo("Nemotron 3 Super 120B A12B");
        assertThat(ModelListParsers.displayName("google/gemma-4-31b-it:free")).isEqualTo("Gemma 4 31B IT");
        assertThat(ModelListParsers.displayName("deepseek-ai/deepseek-v4.1-flash")).isEqualTo("DeepSeek V4.1 Flash");
        assertThat(ModelListParsers.displayName("o4-mini")).isEqualTo("o4 Mini");
    }

    @Test
    void pricesAreConvertedToDollarsPerMillionTokens() {
        assertThat(ModelListParsers.perMillion(JSON.getNodeFactory().textNode("0.00000025")))
                .isEqualByComparingTo(new BigDecimal("0.25"));
        assertThat(ModelListParsers.perMillion(JSON.getNodeFactory().textNode("-1"))).isNull();
        assertThat(ModelListParsers.perMillion(JSON.getNodeFactory().textNode("n/a"))).isNull();
    }
}
