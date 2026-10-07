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

    // ---- OpenAI -------------------------------------------------------------------------------

    /**
     * OpenAI's {@code GET /v1/models}. The listing includes the account's own fine-tuned models
     * ({@code ft:...}, owned by the organisation): those are never returned, because what is
     * returned is stored in a catalogue every workspace reads.
     */
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

    // ---- Shared -------------------------------------------------------------------------------

    /** True when the id belongs to a family known to take tools and is not a non-chat model. */
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
