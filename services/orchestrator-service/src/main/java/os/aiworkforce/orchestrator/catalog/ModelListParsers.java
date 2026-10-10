// @find: model list parsers, parse provider models, OpenRouter, NVIDIA, OpenAI, Anthropic, Gemini, Bedrock, embeddings, tool families, display name, ModelListParsers, chat model filter
// @what: Reads each provider's model list response into CatalogModel entries and decides which models support tools.
// @flow: Called by ModelCatalogService.
package os.aiworkforce.orchestrator.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads each provider's model list into {@link CatalogModel}s.
 *
 * <p>Only OpenRouter says, per model, whether it calls tools and what it costs. Everyone else lists
 * identifiers and little more, so tool support is decided here from what is known about each model
 * family, and kept in the patterns below. A family is added to {@link #TOOL_FAMILIES} once its
 * models are known to accept a {@code tools} array on chat completions; anything that is not a
 * chat model at all (embeddings, rerankers, guards, speech, image generators) is caught by
 * {@link #NOT_CHAT} first, whatever its name otherwise suggests.
 *
 * <p>Pure: no network, no clock. Each parser takes the provider's response body as parsed JSON.
 */
public final class ModelListParsers {

    private ModelListParsers() {}

    /** Models that are not chat models, or that cannot take a request with tools. */
    static final Pattern NOT_CHAT = Pattern.compile(
            "embed|rerank|retriever|reward|guard|safety|topic-control|nemoretriever|parse|clip\\b|nvclip|whisper"
                    + "|tts|speech|transcribe|audio|realtime|translate|detector|calibration|diffusion|\\bvila\\b"
                    + "|/vila|neva|fuyu|kosmos|deplot|cosmos|chatqa|starcoder|codegemma|codellama|minitron"
                    + "|nemotron-4-340b|compound|moderation|dall-e|image-generation|imagen|-image\\b|-image-"
                    + "|aqa|-base\\b|v0\\.1$|search|computer-use|deep-research",
            Pattern.CASE_INSENSITIVE);

    /**
     * Families whose instruct models take tools on an OpenAI-compatible chat completion. Used for
     * NVIDIA NIM, Groq and any other OpenAI-compatible endpoint, which list no capabilities.
     */
    static final Pattern TOOL_FAMILIES = Pattern.compile(
            "llama-?3\\.[1-3]|llama3\\.[1-3]|llama-?4|nemotron|mistral-(large|medium|small|nemo)|mixtral-8x22b-instruct"
                    + "|magistral|devstral|ministral|qwen-?2\\.5|qwen-?3|qwq|deepseek-(v3|v4|r1|chat)|kimi-k[2-9]"
                    + "|glm-4\\.[5-9]|glm-[5-9]|gpt-oss|minimax-m[1-9]|gemma-?4|granite-[34]\\.|jamba-1\\.[5-9]"
                    + "|command-r|command-a|seed-oss|hermes-[34]|phi-4|palmyra-x|laguna|mistral-7b-instruct-v0\\.3",
            Pattern.CASE_INSENSITIVE);

    /** Families that read images. */
    static final Pattern VISION_FAMILIES = Pattern.compile(
            "vision|-vl\\b|-vl-|vlm|omni|llama-?4|gemma-?[34]|kimi-k2\\.[5-9]|kimi-k[3-9]|pixtral|gpt-4o|gpt-4\\.1"
                    + "|gpt-5|^o1$|^o1-20|^o3|^o4|gpt-4-turbo|llama-3\\.2-(11|90)b|nemotron-nano-12b-v2-vl",
            Pattern.CASE_INSENSITIVE);

    /** OpenAI chat models that take tools on chat completions. */
    static final Pattern OPENAI_CHAT = Pattern.compile(
            "^(gpt-5|gpt-4\\.1|gpt-4o|chatgpt-4o|gpt-4-turbo|gpt-4(-0613|-0125-preview|-1106-preview)?$"
                    + "|gpt-3\\.5-turbo(-0125|-1106)?$|o1$|o1-20|o3|o4-mini|gpt-oss)",
            Pattern.CASE_INSENSITIVE);

    /** OpenAI models the chat completions API does not serve, or serves without tools. */
    static final Pattern OPENAI_NOT_CHAT = Pattern.compile(
            "-pro\\b|codex|instruct|nano-banana", Pattern.CASE_INSENSITIVE);

    static final Pattern GEMINI_CHAT = Pattern.compile("^gemini-(1\\.5|[2-9])", Pattern.CASE_INSENSITIVE);

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    static final String NVIDIA_FREE_NOTE = "Free developer access, rate-limited";

    // ---- OpenRouter ---------------------------------------------------------------------------

    /**
     * OpenRouter's {@code GET /api/v1/models}: the one listing that says what each model supports
     * ({@code supported_parameters}), what it reads ({@code architecture.input_modalities}) and what
     * it costs ({@code pricing}, US dollars per token, as strings; {@code -1} where the price varies
     * by the model a router picks).
     */
    // @find: parse OpenRouter models
    public static List<CatalogModel> openRouter(JsonNode body) {
        List<CatalogModel> out = new ArrayList<>();
        for (JsonNode node : array(body, "data")) {
            String id = text(node, "id");
            if (id.isEmpty()) {
                continue;
            }
            List<String> parameters = strings(node.path("supported_parameters"));
            JsonNode architecture = node.path("architecture");
            List<String> outputs = strings(architecture.path("output_modalities"));
            List<String> inputs = strings(architecture.path("input_modalities"));
            boolean writesText = outputs.isEmpty() || outputs.contains("text");
            boolean tools = writesText && parameters.contains("tools");
            boolean vision = inputs.contains("image");

            BigDecimal in = perMillion(node.path("pricing").path("prompt"));
            BigDecimal out2 = perMillion(node.path("pricing").path("completion"));
            boolean free = id.endsWith(":free")
                    || (in != null && out2 != null && in.signum() == 0 && out2.signum() == 0);

            int context = node.path("context_length").asInt(0);
            int maxOut = node.path("top_provider").path("max_completion_tokens").asInt(0);
            String name = text(node, "name");
            name = name.isEmpty() ? displayName(id) : name.replaceFirst("\\s*\\(free\\)\\s*$", "");
            out.add(new CatalogModel(
                    id,
                    name,
                    free,
                    tools,
                    vision,
                    context,
                    maxOut,
                    parameters.contains("response_format") || parameters.contains("structured_outputs"),
                    free ? BigDecimal.ZERO : in,
                    free ? BigDecimal.ZERO : out2,
                    null));
        }
        return out;
    }

    // ---- NVIDIA NIM ---------------------------------------------------------------------------

    /**
     * NVIDIA's {@code GET /v1/models}: identifiers only. Tool support is the family list; every
     * hosted model is free for development within NVIDIA's rate limits.
     */
    // @find: parse NVIDIA models
    public static List<CatalogModel> nvidia(JsonNode body) {
        List<CatalogModel> out = new ArrayList<>();
        for (JsonNode node : array(body, "data")) {
            String id = text(node, "id");
            if (id.isEmpty()) {
                continue;
            }
            out.add(new CatalogModel(
                    id,
                    displayName(id),
                    true,
                    familyCallsTools(id),
                    VISION_FAMILIES.matcher(id).find(),
                    contextOf(node),
                    node.path("max_completion_tokens").asInt(0),
                    familyCallsTools(id),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    NVIDIA_FREE_NOTE));
        }
        return out;
    }

    // ---- Groq and any other OpenAI-compatible endpoint -----------------------------------------

    /**
     * Groq's {@code GET /openai/v1/models}, which adds {@code context_window} and {@code active}; also
     * any OpenAI-compatible endpoint, which may add {@code context_length}. Inactive models are left
     * out. Prices are not listed.
     */
    // @find: parse OpenAI-compatible models
    public static List<CatalogModel> openAiCompatible(JsonNode body) {
        List<CatalogModel> out = new ArrayList<>();
        for (JsonNode node : array(body, "data")) {
            String id = text(node, "id");
            if (id.isEmpty() || (node.has("active") && !node.path("active").asBoolean(true))) {
                continue;
            }
            boolean tools = familyCallsTools(id);
            out.add(new CatalogModel(
                    id,
                    displayName(id),
                    false,
                    tools,
                    VISION_FAMILIES.matcher(id).find(),
                    contextOf(node),
                    node.path("max_completion_tokens").asInt(0),
                    tools,
                    null,
                    null,
                    null));
        }
        return out;
    }

    // ---- Ollama (local) -----------------------------------------------------------------------

    /** What a local model's window is taken to be: the server is started with OLLAMA_CONTEXT_LENGTH=8192. */
    public static final int OLLAMA_CONTEXT = 8192;

    /** Ollama's own price: the models run on this server, so a call costs nothing per token. */
    public static final String OLLAMA_NOTE = "Runs on this server's CPU: free, private and slower than a cloud model.";

    /**
     * Ollama's {@code GET /api/tags}: the models pulled onto the server. The listing names no
     * capabilities, so tool calling is read from the family as for any OpenAI-compatible list;
     * embedding models ({@code nomic-embed-text}, {@code mxbai-embed}) are left out.
     */
    public static List<CatalogModel> ollamaTags(JsonNode body) {
        List<CatalogModel> out = new ArrayList<>();
        for (JsonNode node : array(body, "models")) {
            String id = text(node, "name");
            if (id.isEmpty()) {
                id = text(node, "model");
            }
            if (id.isEmpty() || id.toLowerCase(java.util.Locale.ROOT).contains("embed")) {
                continue;
            }
            boolean tools = familyCallsTools(id);
            String size = node.path("details").path("parameter_size").asText("");
            out.add(new CatalogModel(
                    id,
                    displayName(id) + (size.isEmpty() ? "" : " " + size) + " (local)",
                    true,
                    tools,
                    VISION_FAMILIES.matcher(id).find(),
                    OLLAMA_CONTEXT,
                    2048,
                    tools,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    OLLAMA_NOTE));
        }
        return out;
    }

    // ---- OpenAI -------------------------------------------------------------------------------

    /**
     * OpenAI's {@code GET /v1/models}. The listing includes the account's own fine-tuned models
     * ({@code ft:...}, owned by the organisation): those are never returned, because what is
     * returned is stored in a catalogue every workspace reads.
     */
    // @find: parse OpenAI models
    public static List<CatalogModel> openAi(JsonNode body) {
        List<CatalogModel> out = new ArrayList<>();
        for (JsonNode node : array(body, "data")) {
            String id = text(node, "id");
            if (id.isEmpty() || id.startsWith("ft:") || id.contains(":ft-")) {
                continue;
            }
            boolean tools = OPENAI_CHAT.matcher(id).find()
                    && !NOT_CHAT.matcher(id).find()
                    && !OPENAI_NOT_CHAT.matcher(id).find();
            out.add(new CatalogModel(
                    id,
                    displayName(id),
                    false,
                    tools,
                    tools && VISION_FAMILIES.matcher(id).find(),
                    openAiContext(id),
                    openAiMaxOutput(id),
                    tools,
                    null,
                    null,
                    null));
        }
        return out;
    }

    static int openAiContext(String id) {
        if (id.startsWith("gpt-4.1")) return 1_047_576;
        if (id.startsWith("gpt-5")) return 400_000;
        if (id.matches("^o[134].*")) return 200_000;
        if (id.startsWith("gpt-4o") || id.startsWith("chatgpt-4o") || id.startsWith("gpt-4-turbo")
                || id.contains("preview")) return 128_000;
        if (id.startsWith("gpt-3.5")) return 16_385;
        if (id.startsWith("gpt-4")) return 8_192;
        return 128_000;
    }

    static int openAiMaxOutput(String id) {
        if (id.startsWith("gpt-4.1")) return 32_768;
        if (id.startsWith("gpt-5")) return 128_000;
        if (id.matches("^o[134].*")) return 100_000;
        if (id.startsWith("gpt-4o") || id.startsWith("chatgpt-4o")) return 16_384;
        return 4_096;
    }

    // ---- Anthropic ----------------------------------------------------------------------------

    /**
     * Anthropic's {@code GET /v1/models}: every Claude 3 and later model takes tools and reads
     * images. The display name is Anthropic's own.
     */
    // @find: parse Anthropic models
    public static List<CatalogModel> anthropic(JsonNode body) {
        List<CatalogModel> out = new ArrayList<>();
        for (JsonNode node : array(body, "data")) {
            String id = text(node, "id");
            if (!id.startsWith("claude-")) {
                continue;
            }
            boolean legacy = id.startsWith("claude-2") || id.startsWith("claude-instant");
            String name = text(node, "display_name");
            int context = node.path("max_input_tokens").asInt(200_000);
            int maxOut = node.path("max_tokens").asInt(anthropicMaxOutput(id));
            out.add(new CatalogModel(
                    id,
                    name.isEmpty() ? displayName(id) : name,
                    false,
                    !legacy,
                    !legacy,
                    context,
                    maxOut,
                    !legacy,
                    null,
                    null,
                    null));
        }
        return out;
    }

    static int anthropicMaxOutput(String id) {
        if (id.startsWith("claude-3-5")) return 8_192;
        if (id.startsWith("claude-3-7")) return 64_000;
        if (id.startsWith("claude-3")) return 4_096;
        return 32_000;
    }

    // ---- Google Gemini ------------------------------------------------------------------------

    /**
     * Gemini's {@code GET /v1beta/models}: names come as {@code models/gemini-2.5-flash}, with the
     * methods each supports. Gemini 1.5 and later take function declarations and read images; the
     * Gemma models served there do not take tools.
     */
    // @find: parse Gemini models
    public static List<CatalogModel> gemini(JsonNode body) {
        List<CatalogModel> out = new ArrayList<>();
        for (JsonNode node : array(body, "models")) {
            String id = text(node, "name").replaceFirst("^models/", "");
            if (id.isEmpty()) {
                continue;
            }
            boolean generates = strings(node.path("supportedGenerationMethods")).contains("generatecontent");
            boolean tools = generates && GEMINI_CHAT.matcher(id).find() && !NOT_CHAT.matcher(id).find();
            String name = text(node, "displayName");
            out.add(new CatalogModel(
                    id,
                    name.isEmpty() ? displayName(id) : name,
                    false,
                    tools,
                    tools,
                    node.path("inputTokenLimit").asInt(0),
                    node.path("outputTokenLimit").asInt(0),
                    tools,
                    null,
                    null,
                    null));
        }
        return out;
    }

    // ---- Amazon Bedrock -----------------------------------------------------------------------

    /**
     * Bedrock models that take tools through the Converse API, by model id (without an inference
     * profile's region prefix). From the Converse "supported models and model features" table:
     * Claude 3 and later, Amazon Nova, Llama 3.1 and later (3.2 only at 11B and 90B), Llama 4,
     * Mistral Large and Pixtral Large, Cohere Command R and R+, AI21 Jamba 1.5, DeepSeek V3, Writer
     * Palmyra X4/X5, Qwen3 and OpenAI's open-weight models. Claude 2, Titan Text, Llama 2 and 3.0,
     * Mistral 7B and Mixtral, Command (not R), Jurassic and DeepSeek R1 do not.
     */
    static final Pattern BEDROCK_TOOLS = Pattern.compile(
            "^(anthropic\\.claude-(3|sonnet-4|opus-4|haiku-4|4)"
                    + "|amazon\\.nova-(2-)?(premier|pro|lite|micro)"
                    + "|meta\\.llama3-1-|meta\\.llama3-2-(11|90)b|meta\\.llama3-3-|meta\\.llama4-"
                    + "|mistral\\.mistral-large|mistral\\.pixtral-large"
                    + "|cohere\\.command-r"
                    + "|ai21\\.jamba-1-5"
                    + "|deepseek\\.v3"
                    + "|writer\\.palmyra-x[45]"
                    + "|qwen\\.qwen3"
                    + "|openai\\.gpt-oss)",
            Pattern.CASE_INSENSITIVE);

    /** An inference profile's region prefix: us., eu., apac., global. and the rest. */
    static final Pattern BEDROCK_PROFILE_PREFIX =
            Pattern.compile("^(us|eu|apac|global|us-gov|jp|au|ca|ap)\\.", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> BEDROCK_SCOPES = Map.of(
            "us", "US",
            "eu", "EU",
            "apac", "Asia Pacific",
            "ap", "Asia Pacific",
            "global", "Global",
            "us-gov", "US GovCloud",
            "jp", "Japan",
            "au", "Australia",
            "ca", "Canada");

    /**
     * On-demand prices for Bedrock models, in US dollars per million tokens (input, output), as
     * AWS lists them for us-east-1. Bedrock's model list gives no prices, so these are the known
     * ones; a model not here is shown without a price rather than as free. Prefix match, longest
     * first, on the id without its profile prefix.
     */
    static final Map<String, BigDecimal[]> BEDROCK_PRICES = bedrockPrices();

    private static Map<String, BigDecimal[]> bedrockPrices() {
        Map<String, BigDecimal[]> prices = new java.util.LinkedHashMap<>();
        BiPrice put = (id, in, out) -> prices.put(id, new BigDecimal[] {new BigDecimal(in), new BigDecimal(out)});
        put.add("anthropic.claude-3-haiku", "0.25", "1.25");
        put.add("anthropic.claude-3-5-haiku", "0.80", "4.00");
        put.add("anthropic.claude-haiku-4-5", "1.00", "5.00");
        put.add("anthropic.claude-3-sonnet", "3.00", "15.00");
        put.add("anthropic.claude-3-5-sonnet", "3.00", "15.00");
        put.add("anthropic.claude-3-7-sonnet", "3.00", "15.00");
        put.add("anthropic.claude-sonnet-4", "3.00", "15.00");
        put.add("anthropic.claude-3-opus", "15.00", "75.00");
        put.add("anthropic.claude-opus-4-5", "5.00", "25.00");
        put.add("anthropic.claude-opus-4", "15.00", "75.00");
        put.add("amazon.nova-micro", "0.035", "0.14");
        put.add("amazon.nova-lite", "0.06", "0.24");
        put.add("amazon.nova-pro", "0.80", "3.20");
        put.add("amazon.nova-premier", "2.50", "12.50");
        put.add("meta.llama3-1-8b", "0.22", "0.22");
        put.add("meta.llama3-1-70b", "0.72", "0.72");
        put.add("meta.llama3-1-405b", "2.40", "2.40");
        put.add("meta.llama3-2-11b", "0.16", "0.16");
        put.add("meta.llama3-2-90b", "0.72", "0.72");
        put.add("meta.llama3-3-70b", "0.72", "0.72");
        put.add("meta.llama4-maverick", "0.24", "0.97");
        put.add("meta.llama4-scout", "0.17", "0.66");
        put.add("mistral.mistral-large-2402", "4.00", "12.00");
        put.add("mistral.mistral-large-2407", "2.00", "6.00");
        put.add("mistral.pixtral-large", "2.00", "6.00");
        put.add("cohere.command-r-plus", "3.00", "15.00");
        put.add("cohere.command-r", "0.50", "1.50");
        put.add("ai21.jamba-1-5-large", "2.00", "8.00");
        put.add("ai21.jamba-1-5-mini", "0.20", "0.40");
        put.add("deepseek.v3", "0.58", "1.68");
        return java.util.Collections.unmodifiableMap(prices);
    }

    @FunctionalInterface
    private interface BiPrice {
        void add(String id, String in, String out);
    }

    /** Bedrock's embedding prices per million input tokens, by model id prefix. */
    static final Map<String, BigDecimal> BEDROCK_EMBEDDING_PRICES = Map.of(
            "amazon.titan-embed-text-v2", new BigDecimal("0.02"),
            "amazon.titan-embed-text-v1", new BigDecimal("0.10"),
            "amazon.titan-embed-g1-text", new BigDecimal("0.10"),
            "cohere.embed-english-v3", new BigDecimal("0.10"),
            "cohere.embed-multilingual-v3", new BigDecimal("0.10"),
            "cohere.embed-v4", new BigDecimal("0.12"));

    /**
     * Bedrock's {@code ListFoundationModels} and {@code ListInferenceProfiles} for one region, as
     * one list of the models an agent can use there.
     *
     * <p>A model offered on demand is listed under its own id. One reached through an inference
     * profile (newer Claude, Nova and Llama models in most regions offer no other way) is listed
     * under the profile's id, {@code us.anthropic.claude-sonnet-4-20250514-v1:0}, named for the
     * model and the profile's reach: "Claude Sonnet 4 (US cross-region)". Only models with text
     * out that take tools in Converse are kept; models AWS has marked legacy are left out.
     *
     * @param foundation the {@code GET /foundation-models} body
     * @param profiles each page of {@code GET /inference-profiles}; empty when it could not be read
     */
    // @find: parse Bedrock models and inference profiles
    public static List<CatalogModel> bedrock(JsonNode foundation, List<JsonNode> profiles) {
        Map<String, JsonNode> byId = new java.util.HashMap<>();
        List<CatalogModel> out = new ArrayList<>();
        for (JsonNode node : array(foundation, "modelSummaries")) {
            String id = text(node, "modelId");
            if (id.isEmpty()) {
                continue;
            }
            byId.put(id, node);
            if (!bedrockUsable(node) || !strings(node.path("outputModalities")).contains("text")) {
                continue;
            }
            List<String> inference = strings(node.path("inferenceTypesSupported"));
            if (!inference.contains("on_demand")) {
                continue;
            }
            out.add(bedrockModel(id, id, bedrockName(node, id), node));
        }
        for (JsonNode page : profiles) {
            for (JsonNode profile : array(page, "inferenceProfileSummaries")) {
                String id = text(profile, "inferenceProfileId");
                if (id.isEmpty() || !"active".equalsIgnoreCase(text(profile, "status"))) {
                    continue;
                }
                String base = BEDROCK_PROFILE_PREFIX.matcher(id).replaceFirst("");
                JsonNode underlying = byId.get(base);
                if (underlying == null) {
                    underlying = byId.get(modelIdFromArn(profile));
                }
                if (underlying != null && (!bedrockUsable(underlying)
                        || !strings(underlying.path("outputModalities")).contains("text"))) {
                    continue;
                }
                String name = underlying != null
                        ? bedrockName(underlying, base)
                        : stripProfileName(text(profile, "inferenceProfileName"), base);
                out.add(bedrockModel(id, base, name + " (" + bedrockScope(id) + ")", underlying));
            }
        }
        return out.stream().filter(CatalogModel::toolCalling).toList();
    }

    private static CatalogModel bedrockModel(String id, String base, String name, JsonNode summary) {
        boolean tools = BEDROCK_TOOLS.matcher(base).find();
        boolean vision = summary != null
                ? strings(summary.path("inputModalities")).contains("image")
                : base.startsWith("anthropic.claude-") || base.matches("(?i)amazon\\.nova-(2-)?(pro|lite|premier).*");
        BigDecimal[] price = bedrockPrice(base);
        return new CatalogModel(
                id,
                name,
                false,
                tools,
                vision,
                bedrockContext(base),
                bedrockMaxOutput(base),
                tools,
                price == null ? null : price[0],
                price == null ? null : price[1],
                null);
    }

    private static boolean bedrockUsable(JsonNode summary) {
        String status = summary.path("modelLifecycle").path("status").asText("ACTIVE");
        return !"legacy".equalsIgnoreCase(status);
    }

    /** "Claude 3.5 Sonnet v2", from AWS's own name and the id's version where AWS repeats a name. */
    private static String bedrockName(JsonNode summary, String id) {
        String name = text(summary, "modelName");
        if (name.isEmpty()) {
            return displayName(id.replaceFirst("^[a-z0-9-]+\\.", "").replaceFirst(":\\d+$", ""));
        }
        String provider = text(summary, "providerName");
        if (provider.equalsIgnoreCase("meta") && !name.toLowerCase(Locale.ROOT).startsWith("llama")) {
            name = "Llama " + name;
        }
        java.util.regex.Matcher version = Pattern.compile("-v(\\d+):").matcher(id);
        if (version.find() && !version.group(1).equals("1") && !name.matches("(?i).*\\bv\\d+\\b.*")) {
            name = name + " v" + version.group(1);
        }
        return name;
    }

    private static String stripProfileName(String profileName, String base) {
        if (profileName.isEmpty()) {
            return displayName(base.replaceFirst("^[a-z0-9-]+\\.", "").replaceFirst(":\\d+$", ""));
        }
        return profileName.replaceFirst("^(US|EU|APAC|Global|GLOBAL|US-GOV|JP|AU|CA)\\s+", "")
                .replaceFirst("^(Anthropic|Amazon|Meta|Mistral|Cohere|AI21|DeepSeek|Writer)\\s+", "");
    }

    private static String modelIdFromArn(JsonNode profile) {
        for (JsonNode model : array(profile, "models")) {
            String arn = text(model, "modelArn");
            int slash = arn.lastIndexOf('/');
            if (slash >= 0) {
                return arn.substring(slash + 1);
            }
        }
        return "";
    }

    static String bedrockScope(String profileId) {
        java.util.regex.Matcher prefix = BEDROCK_PROFILE_PREFIX.matcher(profileId);
        String scope = prefix.find() ? BEDROCK_SCOPES.getOrDefault(prefix.group(1).toLowerCase(Locale.ROOT), "") : "";
        return scope.isEmpty() ? "inference profile" : scope + " cross-region";
    }

    static BigDecimal[] bedrockPrice(String base) {
        String id = base.toLowerCase(Locale.ROOT);
        String best = null;
        for (String prefix : BEDROCK_PRICES.keySet()) {
            if (id.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best == null ? null : BEDROCK_PRICES.get(best);
    }

    static int bedrockContext(String base) {
        String id = base.toLowerCase(Locale.ROOT);
        if (id.startsWith("anthropic.")) return 200_000;
        if (id.startsWith("amazon.nova-premier")) return 1_000_000;
        if (id.startsWith("amazon.nova-micro")) return 128_000;
        if (id.startsWith("amazon.nova")) return 300_000;
        if (id.startsWith("meta.llama4-scout")) return 3_500_000;
        if (id.startsWith("meta.llama4")) return 1_000_000;
        if (id.startsWith("mistral.mistral-large-2402")) return 32_000;
        if (id.startsWith("ai21.jamba")) return 256_000;
        if (id.startsWith("deepseek.v3")) return 163_840;
        return 128_000;
    }

    static int bedrockMaxOutput(String base) {
        String id = base.toLowerCase(Locale.ROOT);
        if (id.startsWith("anthropic.claude-3-5")) return 8_192;
        if (id.startsWith("anthropic.claude-3-7")) return 64_000;
        if (id.startsWith("anthropic.claude-3")) return 4_096;
        if (id.startsWith("anthropic.claude-opus-4-5")) return 64_000;
        if (id.startsWith("anthropic.claude-opus-4")) return 32_000;
        if (id.startsWith("anthropic.")) return 64_000;
        if (id.startsWith("amazon.nova")) return 5_000;
        if (id.startsWith("meta.") || id.startsWith("mistral.") || id.startsWith("deepseek.")) return 8_192;
        return 4_096;
    }

    /**
     * The text embedding models in Bedrock's {@code ListFoundationModels}: Titan Text Embeddings
     * and Cohere Embed, on demand. Image and multimodal embedders are left out.
     */
    // @find: parse Bedrock embedding models
    public static List<CatalogModel> bedrockEmbeddings(JsonNode foundation) {
        List<CatalogModel> out = new ArrayList<>();
        for (JsonNode node : array(foundation, "modelSummaries")) {
            String id = text(node, "modelId");
            String lower = id.toLowerCase(Locale.ROOT);
            boolean known = lower.startsWith("amazon.titan-embed-text") || lower.startsWith("amazon.titan-embed-g1-text")
                    || lower.startsWith("cohere.embed");
            if (!known || lower.contains("image") || !bedrockUsable(node)
                    || !strings(node.path("outputModalities")).contains("embedding")
                    || !strings(node.path("inferenceTypesSupported")).contains("on_demand")
                    || id.matches(".*:\\d+k$")) {
                continue;
            }
            BigDecimal price = null;
            for (Map.Entry<String, BigDecimal> entry : BEDROCK_EMBEDDING_PRICES.entrySet()) {
                if (lower.startsWith(entry.getKey())) {
                    price = entry.getValue();
                }
            }
            String name = text(node, "modelName");
            out.add(new CatalogModel(
                    id,
                    name.isEmpty() ? displayName(id) : name,
                    false,
                    false,
                    false,
                    lower.startsWith("cohere.embed-english-v3") || lower.startsWith("cohere.embed-multilingual-v3")
                            ? 512
                            : 8_192,
                    1,
                    false,
                    price,
                    BigDecimal.ZERO,
                    null));
        }
        return out;
    }

    // ---- Embedding models ---------------------------------------------------------------------

    /** Models that turn text into vectors, by name; every provider names its embedding models so. */
    static final Pattern EMBEDDING = Pattern.compile("embed", Pattern.CASE_INSENSITIVE);

    /**
     * OpenRouter's {@code GET /api/v1/embeddings/models}: the same shape as its chat listing, and
     * every entry an embedding model. Prices are per token, as there.
     */
    // @find: parse OpenRouter embedding models
    public static List<CatalogModel> openRouterEmbeddings(JsonNode body) {
        List<CatalogModel> out = new ArrayList<>();
        for (JsonNode node : array(body, "data")) {
            String id = text(node, "id");
            if (id.isEmpty()) {
                continue;
            }
            List<String> inputs = strings(node.path("architecture").path("input_modalities"));
            if (!inputs.isEmpty() && !inputs.contains("text")) {
                continue;
            }
            BigDecimal in = perMillion(node.path("pricing").path("prompt"));
            boolean free = id.endsWith(":free") || (in != null && in.signum() == 0);
            String name = text(node, "name");
            name = name.isEmpty() ? displayName(id) : name.replaceFirst("\\s*\\(free\\)\\s*$", "");
            out.add(new CatalogModel(
                    id,
                    name,
                    free,
                    false,
                    false,
                    node.path("context_length").asInt(0),
                    1,
                    false,
                    free ? BigDecimal.ZERO : in,
                    BigDecimal.ZERO,
                    null));
        }
        return out;
    }

    /**
     * The embedding models in an OpenAI-shaped {@code GET /models} listing (OpenAI, NVIDIA NIM, any
     * compatible server), picked out by name. NVIDIA's are free for development, as its chat models.
     */
    // @find: parse embedding model ids
    public static List<CatalogModel> embeddingIds(JsonNode body, boolean nvidia) {
        List<CatalogModel> out = new ArrayList<>();
        for (JsonNode node : array(body, "data")) {
            String id = text(node, "id");
            if (id.isEmpty() || !EMBEDDING.matcher(id).find()) {
                continue;
            }
            out.add(new CatalogModel(
                    id,
                    displayName(id),
                    nvidia,
                    false,
                    false,
                    contextOf(node),
                    1,
                    false,
                    nvidia ? BigDecimal.ZERO : null,
                    BigDecimal.ZERO,
                    nvidia ? NVIDIA_FREE_NOTE : null));
        }
        return out;
    }

    /** Gemini's listing, keeping the models that support {@code embedContent}. */
    // @find: parse Gemini embedding models
    public static List<CatalogModel> geminiEmbeddings(JsonNode body) {
        List<CatalogModel> out = new ArrayList<>();
        for (JsonNode node : array(body, "models")) {
            String id = text(node, "name").replaceFirst("^models/", "");
            if (id.isEmpty() || !strings(node.path("supportedGenerationMethods")).contains("embedcontent")) {
                continue;
            }
            String name = text(node, "displayName");
            out.add(new CatalogModel(
                    id,
                    name.isEmpty() ? displayName(id) : name,
                    false,
                    false,
                    false,
                    node.path("inputTokenLimit").asInt(0),
                    1,
                    false,
                    null,
                    BigDecimal.ZERO,
                    null));
        }
        return out;
    }

    // ---- Shared -------------------------------------------------------------------------------

    /** True when the id belongs to a family known to take tools and is not a non-chat model. */
    // @find: does model family call tools
    public static boolean familyCallsTools(String id) {
        return TOOL_FAMILIES.matcher(id).find() && !NOT_CHAT.matcher(id).find();
    }

    private static final Map<String, String> WORDS = Map.ofEntries(
            Map.entry("gpt", "GPT"),
            Map.entry("oss", "OSS"),
            Map.entry("glm", "GLM"),
            Map.entry("it", "IT"),
            Map.entry("vl", "VL"),
            Map.entry("vlm", "VLM"),
            Map.entry("qa", "QA"),
            Map.entry("ai", "AI"),
            Map.entry("moe", "MoE"),
            Map.entry("nim", "NIM"),
            Map.entry("chatgpt", "ChatGPT"),
            Map.entry("deepseek", "DeepSeek"),
            Map.entry("qwq", "QwQ"),
            Map.entry("minimax", "MiniMax"));

    /**
     * A readable name made from an identifier, for listings that give none:
     * {@code meta/llama-3.3-70b-instruct} reads "Llama 3.3 70B Instruct".
     */
    // @find: display name for model id
    public static String displayName(String id) {
        String base = id.replaceFirst("^models/", "");
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceFirst(":free$", "");
        StringBuilder name = new StringBuilder();
        for (String part : base.split("[-_\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!name.isEmpty()) {
                name.append(' ');
            }
            String lower = part.toLowerCase(Locale.ROOT);
            if (WORDS.containsKey(lower)) {
                name.append(WORDS.get(lower));
            } else if (lower.matches("\\d+(\\.\\d+)?[bmkt]") || lower.matches("a\\d+(\\.\\d+)?b") || lower.matches("[vr]\\d+(\\.\\d+)?")
                    || lower.matches("\\d+x\\d+[bm]")) {
                name.append(part.toUpperCase(Locale.ROOT));
            } else if (lower.matches("o\\d.*") || lower.matches("\\d.*")) {
                name.append(part);
            } else {
                name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return name.isEmpty() ? id : name.toString();
    }

    private static int contextOf(JsonNode node) {
        int context = node.path("context_window").asInt(0);
        return context > 0 ? context : node.path("context_length").asInt(0);
    }

    /** A per-token price string as dollars per million tokens; null when absent, unparseable or negative. */
    static BigDecimal perMillion(JsonNode price) {
        if (price == null || price.isMissingNode() || price.isNull()) {
            return null;
        }
        try {
            BigDecimal perToken = new BigDecimal(price.asText().trim());
            if (perToken.signum() < 0) {
                return null;
            }
            BigDecimal value = perToken.multiply(MILLION).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
            return value.signum() == 0 ? BigDecimal.ZERO : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Iterable<JsonNode> array(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.path(field);
        return node != null && node.isArray() ? node : List.of();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : "";
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> out.add(item.asText().toLowerCase(Locale.ROOT)));
        }
        return out;
    }
}
