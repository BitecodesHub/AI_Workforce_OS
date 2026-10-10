// @find: tests for model list parsers, catalog, open router reads tools free prices vision and names, nvidia keeps known tool families and drops embeddings guards and reward models, groq skips inactive speech and compound models, open ai keeps chat models and never an accounts fine tunes, anthropic uses its own names and every claude takes tools, gemini keeps generate content gemini models only, code models of atool family name are not mistaken for it, display names read like names, ModelListParsersTest, ModelListParsers
// @what: Tests for ModelListParsers in the orchestrator catalog package (13 test methods).
// @flow: Exercises ModelListParsers
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

    // @find: test open router reads tools free prices vision and names, model list parsers
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

    // @find: test nvidia keeps known tool families and drops embeddings guards and reward models, model list parsers
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

    // @find: test groq skips inactive speech and compound models, model list parsers
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

    // @find: test open ai keeps chat models and never an accounts fine tunes, model list parsers
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

    // @find: test anthropic uses its own names and every claude takes tools, model list parsers
    @Test
    void anthropicUsesItsOwnNamesAndEveryClaudeTakesTools() throws IOException {
        List<CatalogModel> models = ModelListParsers.anthropic(fixture("anthropic"));

        assertThat(models).hasSize(3).allMatch(CatalogModel::toolCalling).allMatch(CatalogModel::vision);
        assertThat(byId(models).get("claude-sonnet-4-5-20250929").displayName()).isEqualTo("Claude Sonnet 4.5");
        assertThat(byId(models).get("claude-sonnet-4-5-20250929").contextLength()).isEqualTo(200_000);
    }

    // @find: test gemini keeps generate content gemini models only, model list parsers
    @Test
    void geminiKeepsGenerateContentGeminiModelsOnly() throws IOException {
        List<CatalogModel> models = ModelListParsers.gemini(fixture("gemini"));

        assertThat(models.stream().filter(CatalogModel::toolCalling).map(CatalogModel::id))
                .containsExactlyInAnyOrder("gemini-2.5-flash", "gemini-2.5-pro");
        assertThat(byId(models).get("gemini-2.5-flash").contextLength()).isEqualTo(1_048_576);
        assertThat(byId(models).get("gemini-2.5-flash").displayName()).isEqualTo("Gemini 2.5 Flash");
    }

    // @find: test code models of atool family name are not mistaken for it, model list parsers
    @Test
    void codeModelsOfAToolFamilyNameAreNotMistakenForIt() {
        assertThat(ModelListParsers.familyCallsTools("ibm/granite-34b-code-instruct")).isFalse();
        assertThat(ModelListParsers.familyCallsTools("ibm/granite-3.0-8b-instruct")).isTrue();
        assertThat(ModelListParsers.familyCallsTools("mistralai/mistral-7b-instruct-v0.3")).isTrue();
        assertThat(ModelListParsers.familyCallsTools("nvidia/llama-3.2-nv-embedqa-1b-v1")).isFalse();
    }

    // @find: test display names read like names, model list parsers
    @Test
    void displayNamesReadLikeNames() {
        assertThat(ModelListParsers.displayName("nvidia/nemotron-3-super-120b-a12b"))
                .isEqualTo("Nemotron 3 Super 120B A12B");
        assertThat(ModelListParsers.displayName("google/gemma-4-31b-it:free")).isEqualTo("Gemma 4 31B IT");
        assertThat(ModelListParsers.displayName("deepseek-ai/deepseek-v4.1-flash")).isEqualTo("DeepSeek V4.1 Flash");
        assertThat(ModelListParsers.displayName("o4-mini")).isEqualTo("o4 Mini");
    }

    // @find: test prices are converted to dollars per million tokens, model list parsers
    @Test
    void pricesAreConvertedToDollarsPerMillionTokens() {
        assertThat(ModelListParsers.perMillion(JSON.getNodeFactory().textNode("0.00000025")))
                .isEqualByComparingTo(new BigDecimal("0.25"));
        assertThat(ModelListParsers.perMillion(JSON.getNodeFactory().textNode("-1"))).isNull();
        assertThat(ModelListParsers.perMillion(JSON.getNodeFactory().textNode("n/a"))).isNull();
    }

    // @find: test embedding models are picked out of each listing, model list parsers
    @Test
    void embeddingModelsArePickedOutOfEachListing() throws IOException {
        JsonNode nvidia = JSON.readTree("{\"data\":[{\"id\":\"nvidia/nemotron-3-embed-1b\"},"
                + "{\"id\":\"meta/llama-3.3-70b-instruct\"},{\"id\":\"snowflake/arctic-embed-l\"}]}");
        assertThat(ModelListParsers.embeddingIds(nvidia, true))
                .extracting(CatalogModel::id)
                .containsExactly("nvidia/nemotron-3-embed-1b", "snowflake/arctic-embed-l");
        assertThat(ModelListParsers.embeddingIds(nvidia, true).get(0).free()).isTrue();
        assertThat(ModelListParsers.embeddingIds(nvidia, true).get(0).toolCalling()).isFalse();

        JsonNode openRouter = JSON.readTree("{\"data\":[{\"id\":\"liquid/lfm-2.5-embedding-350m:free\","
                + "\"name\":\"LiquidAI: LFM2.5-Embedding-350M (free)\",\"context_length\":512,"
                + "\"architecture\":{\"input_modalities\":[\"text\"]},\"pricing\":{\"prompt\":\"0\"}},"
                + "{\"id\":\"openai/text-embedding-3-small\",\"pricing\":{\"prompt\":\"0.00000002\"}},"
                + "{\"id\":\"vendor/image-embed\",\"architecture\":{\"input_modalities\":[\"image\"]}}]}");
        List<CatalogModel> embeddings = ModelListParsers.openRouterEmbeddings(openRouter);
        assertThat(embeddings).extracting(CatalogModel::id)
                .containsExactly("liquid/lfm-2.5-embedding-350m:free", "openai/text-embedding-3-small");
        assertThat(embeddings.get(0).displayName()).isEqualTo("LiquidAI: LFM2.5-Embedding-350M");
        assertThat(embeddings.get(0).free()).isTrue();
        assertThat(embeddings.get(1).pricePerMTokIn()).isEqualByComparingTo("0.02");
    }

    // @find: test bedrock keeps converse tool models and lists inference profiles for the rest, model list parsers
    @Test
    void bedrockKeepsConverseToolModelsAndListsInferenceProfilesForTheRest() throws IOException {
        List<CatalogModel> listed =
                ModelListParsers.bedrock(fixture("bedrock-foundation"), List.of(fixture("bedrock-profiles")));
        Map<String, CatalogModel> models = byId(listed);

        assertThat(models.keySet()).containsExactlyInAnyOrder(
                // On demand, under their own ids.
                "anthropic.claude-3-haiku-20240307-v1:0",
                "amazon.nova-pro-v1:0",
                "amazon.nova-micro-v1:0",
                "mistral.mistral-large-2402-v1:0",
                "cohere.command-r-plus-v1:0",
                // Reached only through a profile, under the profile's id.
                "eu.anthropic.claude-3-5-sonnet-20241022-v2:0",
                "eu.anthropic.claude-sonnet-4-20250514-v1:0",
                "eu.amazon.nova-pro-v1:0",
                "eu.meta.llama3-2-90b-instruct-v1:0",
                "global.anthropic.claude-haiku-4-5-20251001-v1:0");
        // Left out: legacy Claude 2, image models, Titan Text, Llama 3.0 and 3.2 1B, Mixtral,
        // Command (not R), DeepSeek R1 and the embedding model, none of which take Converse tools.
        assertThat(listed).allMatch(CatalogModel::toolCalling).noneMatch(CatalogModel::free);

        CatalogModel sonnet = models.get("eu.anthropic.claude-sonnet-4-20250514-v1:0");
        assertThat(sonnet.displayName()).isEqualTo("Claude Sonnet 4 (EU cross-region)");
        assertThat(sonnet.vision()).isTrue();
        assertThat(sonnet.contextLength()).isEqualTo(200_000);
        assertThat(sonnet.maxOutputTokens()).isEqualTo(64_000);
        assertThat(sonnet.pricePerMTokIn()).isEqualByComparingTo("3");
        assertThat(sonnet.pricePerMTokOut()).isEqualByComparingTo("15");

        assertThat(models.get("eu.anthropic.claude-3-5-sonnet-20241022-v2:0").displayName())
                .isEqualTo("Claude 3.5 Sonnet v2 (EU cross-region)");
        assertThat(models.get("global.anthropic.claude-haiku-4-5-20251001-v1:0").displayName())
                .isEqualTo("Claude Haiku 4.5 (Global cross-region)");
        assertThat(models.get("eu.meta.llama3-2-90b-instruct-v1:0").vision()).isTrue();
        CatalogModel micro = models.get("amazon.nova-micro-v1:0");
        assertThat(micro.displayName()).isEqualTo("Nova Micro");
        assertThat(micro.vision()).isFalse();
        assertThat(micro.contextLength()).isEqualTo(128_000);
        assertThat(micro.pricePerMTokIn()).isEqualByComparingTo("0.035");
        assertThat(models.get("amazon.nova-pro-v1:0").contextLength()).isEqualTo(300_000);
        // A model with no known price shows none, rather than looking free.
        assertThat(ModelListParsers.bedrockPrice("writer.palmyra-x5-v1:0")).isNull();
    }

    // @find: test bedrock tool families follow the converse feature table, model list parsers
    @Test
    void bedrockToolFamiliesFollowTheConverseFeatureTable() {
        assertThat(ModelListParsers.BEDROCK_TOOLS.matcher("anthropic.claude-opus-4-1-20250805-v1:0").find()).isTrue();
        assertThat(ModelListParsers.BEDROCK_TOOLS.matcher("amazon.nova-premier-v1:0").find()).isTrue();
        assertThat(ModelListParsers.BEDROCK_TOOLS.matcher("meta.llama4-maverick-17b-instruct-v1:0").find()).isTrue();
        assertThat(ModelListParsers.BEDROCK_TOOLS.matcher("ai21.jamba-1-5-large-v1:0").find()).isTrue();
        assertThat(ModelListParsers.BEDROCK_TOOLS.matcher("cohere.command-r-v1:0").find()).isTrue();
        assertThat(ModelListParsers.BEDROCK_TOOLS.matcher("deepseek.v3-v1:0").find()).isTrue();
        assertThat(ModelListParsers.BEDROCK_TOOLS.matcher("anthropic.claude-instant-v1").find()).isFalse();
        assertThat(ModelListParsers.BEDROCK_TOOLS.matcher("meta.llama3-70b-instruct-v1:0").find()).isFalse();
        assertThat(ModelListParsers.BEDROCK_TOOLS.matcher("mistral.mistral-7b-instruct-v0:2").find()).isFalse();
        assertThat(ModelListParsers.BEDROCK_TOOLS.matcher("deepseek.r1-v1:0").find()).isFalse();
        assertThat(ModelListParsers.bedrockScope("apac.amazon.nova-lite-v1:0")).isEqualTo("Asia Pacific cross-region");
    }

    // @find: test bedrock embeddings are titan and cohere text models on demand, model list parsers
    @Test
    void bedrockEmbeddingsAreTitanAndCohereTextModelsOnDemand() throws IOException {
        List<CatalogModel> embeddings = ModelListParsers.bedrockEmbeddings(fixture("bedrock-embeddings"));

        assertThat(embeddings).extracting(CatalogModel::id)
                .containsExactly("amazon.titan-embed-text-v2:0", "cohere.embed-multilingual-v3");
        assertThat(embeddings.get(0).displayName()).isEqualTo("Titan Text Embeddings V2");
        assertThat(embeddings.get(0).pricePerMTokIn()).isEqualByComparingTo("0.02");
        assertThat(embeddings.get(1).contextLength()).isEqualTo(512);
        assertThat(embeddings).noneMatch(CatalogModel::free).noneMatch(CatalogModel::toolCalling);
    }
}
