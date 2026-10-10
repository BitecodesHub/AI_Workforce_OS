// @find: model catalog, list models for provider, refresh models, embedding models, provider model list, tool-capable models, ModelCatalogService, choose model for agent, model picker, cache
// @what: Lists every model a provider offers that an agent can use, read from the provider with the workspace key, cached and saved to the catalogue.
// @flow: Called by the model catalogue controllers; uses ModelListHttp, ModelListParsers and ModelCatalogStore.
package os.aiworkforce.orchestrator.catalog;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import os.aiworkforce.llm.bedrock.BedrockCredentials;
import os.aiworkforce.llm.model.ProviderDescriptor;
import os.aiworkforce.llm.spi.CredentialResolver;
import os.aiworkforce.orchestrator.domain.LlmModelEntity;
import os.aiworkforce.orchestrator.domain.LlmProviderEntity;
import os.aiworkforce.orchestrator.repository.Models;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry.WorkspaceProvider;
import os.aiworkforce.platform.error.ApiException;

/**
 * Every model a provider offers that an agent can use, read from the provider's own model list.
 *
 * <p>The list is fetched with the workspace's stored key (OpenRouter and NVIDIA answer without
 * one), kept for {@link #TTL} per workspace and provider, and its tool-capable models are written
 * to the shared catalogue so a policy that names one validates and routes. When the provider
 * cannot be asked, or does not answer, the saved models are shown instead and the view says so in
 * plain words.
 *
 * <p>The key is used for the one request and never logged; neither is anything the provider says.
 */
@Service
public class ModelCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogService.class);

    /** How long a fetched list is used before the provider is asked again. */
    static final Duration TTL = Duration.ofHours(6);

    /** A refresh this soon after the last fetch is served from the cache, so the button cannot hammer a provider. */
    static final Duration REFRESH_FLOOR = Duration.ofSeconds(30);

    /** Gemini and Anthropic page their lists; more pages than this is not a list anyone scrolls. */
    private static final int MAX_PAGES = 5;

    public enum Source {
        /** Fetched from the provider for this request. */
        live,
        /** Fetched from the provider earlier, within {@link #TTL}. */
        cached,
        /** The provider was not asked or did not answer: the models this installation has saved. */
        saved
    }

    /**
     * One model for the picker.
     *
     * @param pricePerMTokIn US dollars per million input tokens; null when the provider does not list it
     * @param pricingNote how a free provider limits use, for example; null when there is nothing to say
     */
    public record ModelOption(
            String id,
            String displayName,
            boolean free,
            boolean toolCalling,
            boolean vision,
            int contextLength,
            BigDecimal pricePerMTokIn,
            BigDecimal pricePerMTokOut,
            String pricingNote) {}

    /**
     * A provider's models as the picker shows them.
     *
     * @param message a plain sentence when the list is not fresh from the provider; null otherwise
     * @param fetchedAt when the provider was last asked successfully; null for saved models
     */
    public record CatalogueView(
            String providerId,
            String providerName,
            Source source,
            Instant fetchedAt,
            String message,
            int total,
            int freeCount,
            List<ModelOption> models) {}

    private record Entry(Instant fetchedAt, List<CatalogModel> models) {}

    private final JpaProviderRegistry registry;
    private final Models models;
    private final CredentialResolver credentials;
    private final ModelCatalogStore store;
    private final ObjectMapper json;
    private final ModelListHttp http;
    private final Clock clock;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public ModelCatalogService(
            JpaProviderRegistry registry,
            Models models,
            CredentialResolver credentials,
            ModelCatalogStore store,
            ObjectMapper json) {
        this(registry, models, credentials, store, json, ModelListHttp.jdk(), Clock.systemUTC());
    }

    ModelCatalogService(
            JpaProviderRegistry registry,
            Models models,
            CredentialResolver credentials,
            ModelCatalogStore store,
            ObjectMapper json,
            ModelListHttp http,
            Clock clock) {
        this.registry = registry;
        this.models = models;
        this.credentials = credentials;
        this.store = store;
        this.json = json;
        this.http = http;
        this.clock = clock;
    }

    /** Bedrock's adapter, for the server's own AWS role when a workspace stores no Bedrock key; may be absent. */
    private os.aiworkforce.llm.provider.BedrockProvider bedrock;

    @Autowired(required = false)
    void setBedrock(os.aiworkforce.llm.provider.BedrockProvider bedrock) {
        this.bedrock = bedrock;
    }

    private boolean bedrockHasRole(ProviderDescriptor.Kind kind) {
        return kind == ProviderDescriptor.Kind.BEDROCK && bedrock != null && bedrock.hasAmbientCredential(null);
    }

    /**
     * @param refresh ask the provider now, unless it was asked in the last {@link #REFRESH_FLOOR}
     * @param all include models that cannot call tools, for diagnosis
     */
    // @find: list provider models, refresh model list, model picker for agents
    public CatalogueView list(UUID orgId, String providerId, boolean refresh, boolean all) {
        WorkspaceProvider provider = registry.workspaceProvider(orgId, providerId)
                .orElseThrow(() -> ApiException.notFound("provider", providerId));
        LlmProviderEntity entity = provider.entity();
        String name = entity.getDisplayName();
        ProviderDescriptor.Kind kind = kindOf(entity);

        if (kind == ProviderDescriptor.Kind.SANDBOX) {
            return saved(entity, all, null);
        }
        String key = orgId + "/" + providerId;
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            Instant now = clock.instant();
            Entry entry = cache.get(key);
            if (entry != null) {
                Duration age = Duration.between(entry.fetchedAt(), now);
                boolean fresh = age.compareTo(TTL) < 0;
                if (fresh && (!refresh || age.compareTo(REFRESH_FLOOR) < 0)) {
                    return view(entity, Source.cached, entry.fetchedAt(), null, entry.models(), all);
                }
            }

            String credential = null;
            if (entity.getCredentialRef() != null) {
                CredentialResolver.Lookup lookup = credentials.lookup(orgId.toString(), entity.getCredentialRef());
                if (lookup instanceof CredentialResolver.Found found) {
                    credential = found.value();
                } else if (lookup instanceof CredentialResolver.NotFound && bedrockHasRole(kind)) {
                    // Listed with the server's own AWS role, as runs are called.
                    credential = null;
                } else if (needsKey(entity)) {
                    String message = lookup instanceof CredentialResolver.Unavailable
                            ? "Couldn't refresh the list from " + name + "; showing saved models."
                            : "Store a key for " + name + " to load its full model list. Showing saved models.";
                    return saved(entity, all, message);
                }
            }

            List<CatalogModel> listed;
            try {
                listed = fetch(entity, kind, credential);
            } catch (CatalogueUnavailable e) {
                // The status alone: a provider's error body can quote the key.
                log.warn("Model list from provider {} for workspace {} failed: {}", providerId, orgId, e.getMessage());
                return saved(entity, all, "Couldn't refresh the list from " + name + "; showing saved models.");
            }

            listed = withSeededDetails(entity.getId(), listed);
            try {
                store.saveDiscovered(entity.getId(), listed, now);
            } catch (RuntimeException e) {
                // The picker still works from the fetched list; only choosing a brand-new model would fail validation.
                log.warn("Saving the model list from provider {} failed: {}", providerId, e.getClass().getSimpleName());
            }
            cache.put(key, new Entry(now, listed));
            return view(entity, Source.live, now, null, listed, all);
        }
    }

    // ---- Embedding models -------------------------------------------------------------------

    /**
     * One embedding model for the knowledge base's picker.
     *
     * @param pricePerMTokIn US dollars per million input tokens; null when the provider does not list it
     */
    public record EmbeddingOption(
            String providerId,
            String providerName,
            String id,
            String displayName,
            boolean free,
            int contextLength,
            BigDecimal pricePerMTokIn,
            String pricingNote) {}

    /**
     * Every embedding model this workspace can use now, across its providers.
     *
     * @param models free first, then by provider and name
     * @param notes a plain sentence for each provider that could not be asked, or has no key stored
     */
    public record EmbeddingCatalogue(List<EmbeddingOption> models, List<String> notes) {}

    /**
     * The embedding models of every provider this workspace has switched on and stored a working
     * key for, read from each provider's own model list and cached like the chat lists. Anthropic
     * and Groq offer none; Bedrock's Titan and Cohere models are read from its region. The models found are saved, so the
     * embedding endpoint can price and use the one chosen.
     */
    // @find: list embedding models, knowledge embedding model picker
    public EmbeddingCatalogue embeddingModels(UUID orgId, boolean refresh) {
        List<EmbeddingOption> options = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (WorkspaceProvider provider : registry.workspaceProviders(orgId)) {
            LlmProviderEntity entity = provider.entity();
            ProviderDescriptor.Kind kind = kindOf(entity);
            if (!provider.enabled() || !provider.platformEnabled()) {
                continue;
            }
            if (kind == ProviderDescriptor.Kind.SANDBOX || kind == ProviderDescriptor.Kind.ANTHROPIC) {
                continue;
            }
            if (entity.getCredentialRef() == null) {
                continue;
            }
            CredentialResolver.Lookup lookup = credentials.lookup(orgId.toString(), entity.getCredentialRef());
            String storedKey;
            if (lookup instanceof CredentialResolver.Found found) {
                storedKey = found.value();
            } else if (lookup instanceof CredentialResolver.NotFound && bedrockHasRole(kind)) {
                storedKey = null;
            } else {
                // No key: the provider could list models but would refuse to embed with them.
                continue;
            }
            List<CatalogModel> listed;
            String key = "embed/" + orgId + "/" + entity.getId();
            synchronized (locks.computeIfAbsent(key, k -> new Object())) {
                Instant now = clock.instant();
                Entry entry = cache.get(key);
                boolean fresh = entry != null && Duration.between(entry.fetchedAt(), now).compareTo(TTL) < 0;
                boolean tooSoon = entry != null
                        && Duration.between(entry.fetchedAt(), now).compareTo(REFRESH_FLOOR) < 0;
                if (fresh && (!refresh || tooSoon)) {
                    listed = entry.models();
                } else {
                    try {
                        listed = fetchEmbedding(entity, kind, storedKey);
                    } catch (CatalogueUnavailable e) {
                        log.warn("Embedding model list from provider {} failed: {}", entity.getId(), e.getMessage());
                        notes.add("Couldn't read the embedding models of " + entity.getDisplayName() + " just now.");
                        if (kind != ProviderDescriptor.Kind.BEDROCK) {
                            continue;
                        }
                        // Bedrock's list needs bedrock:ListFoundationModels, which a narrow policy or
                        // an API key may not allow; the saved Titan and Cohere rows are offered instead.
                        listed = null;
                    }
                    if (listed == null) {
                        // Not cached, so the next look asks AWS again.
                        listed = savedEmbeddings(entity.getId());
                    } else {
                        try {
                            store.saveEmbedding(entity.getId(), listed, now);
                        } catch (RuntimeException e) {
                            log.warn("Saving the embedding models of {} failed: {}", entity.getId(), e.getClass().getSimpleName());
                        }
                        cache.put(key, new Entry(now, listed));
                    }
                }
            }
            Map<String, CatalogModel> unique = new LinkedHashMap<>();
            listed.forEach(model -> unique.putIfAbsent(model.id(), model));
            unique.values().forEach(model -> options.add(new EmbeddingOption(
                    entity.getId(),
                    entity.getDisplayName(),
                    model.id(),
                    model.displayName(),
                    model.free(),
                    Math.max(0, model.contextLength()),
                    model.free() ? BigDecimal.ZERO : model.pricePerMTokIn(),
                    model.pricingNote())));
        }
        options.sort(Comparator.comparing((EmbeddingOption option) -> !option.free())
                .thenComparing(EmbeddingOption::providerName)
                .thenComparing(option -> option.displayName().toLowerCase(Locale.ROOT)));
        return new EmbeddingCatalogue(options, notes);
    }

    private List<CatalogModel> fetchEmbedding(LlmProviderEntity entity, ProviderDescriptor.Kind kind, String credential)
            throws CatalogueUnavailable {
        if (kind == ProviderDescriptor.Kind.BEDROCK) {
            BedrockCredentials keys = bedrockCredentials(credential);
            String url = BedrockCredentials.controlEndpoint(keys.regionOrDefault())
                    + "/foundation-models?byOutputModality=EMBEDDING";
            return ModelListParsers.bedrockEmbeddings(bedrockGet(url, keys));
        }
        String base = stripSlash(entity.getBaseUrl());
        if (base.isEmpty()) {
            throw new CatalogueUnavailable("no base URL");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (entity.getDefaultHeaders() != null) {
            headers.putAll(entity.getDefaultHeaders());
        }
        if (kind == ProviderDescriptor.Kind.GEMINI) {
            headers.put("x-goog-api-key", credential);
            List<CatalogModel> out = new ArrayList<>();
            String token = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                String url = base + "/v1beta/models?pageSize=1000" + (token == null ? "" : "&pageToken=" + encode(token));
                JsonNode body = get(url, headers);
                out.addAll(ModelListParsers.geminiEmbeddings(body));
                token = body.path("nextPageToken").asText("");
                if (token.isEmpty()) {
                    break;
                }
            }
            return out;
        }
        headers.put("Authorization", "Bearer " + credential);
        return switch (flavour(entity)) {
            case "openrouter" -> ModelListParsers.openRouterEmbeddings(get(base + "/embeddings/models", headers));
            case "nvidia" -> ModelListParsers.embeddingIds(get(base + "/models", headers), true);
            default -> ModelListParsers.embeddingIds(get(base + "/models", headers), false);
        };
    }

    /** Forgets every cached list, for a test. */    /** Forgets every cached list, for a test. */
    void clear() {
        cache.clear();
    }

    // ---- Fetching -----------------------------------------------------------------------------

    /** The provider could not be asked or did not answer usefully. The message is safe to log. */
    static final class CatalogueUnavailable extends Exception {
        CatalogueUnavailable(String message) {
            super(message);
        }
    }

    private List<CatalogModel> fetch(LlmProviderEntity entity, ProviderDescriptor.Kind kind, String credential)
            throws CatalogueUnavailable {
        if (kind == ProviderDescriptor.Kind.BEDROCK) {
            return bedrockModels(credential);
        }
        String base = stripSlash(entity.getBaseUrl());
        if (base.isEmpty()) {
            throw new CatalogueUnavailable("no base URL");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (entity.getDefaultHeaders() != null) {
            headers.putAll(entity.getDefaultHeaders());
        }
        return switch (kind) {
            case ANTHROPIC -> {
                if (credential != null) {
                    headers.put("x-api-key", credential);
                }
                headers.put("anthropic-version", "2023-06-01");
                yield anthropicPages(base, headers);
            }
            case GEMINI -> {
                if (credential != null) {
                    headers.put("x-goog-api-key", credential);
                }
                yield geminiPages(base, headers);
            }
            default -> {
                if (credential != null) {
                    headers.put("Authorization", "Bearer " + credential);
                }
                if ("ollama".equals(flavour(entity))) {
                    // Ollama's own list says more than its OpenAI-compatible one (sizes, families).
                    yield ModelListParsers.ollamaTags(get(ollamaRoot(base) + "/api/tags", headers));
                }
                JsonNode body = get(base + "/models", headers);
                yield switch (flavour(entity)) {
                    case "openrouter" -> ModelListParsers.openRouter(body);
                    case "nvidia" -> ModelListParsers.nvidia(body);
                    case "openai" -> ModelListParsers.openAi(body);
                    default -> ModelListParsers.openAiCompatible(body);
                };
            }
        };
    }

    /**
     * Bedrock's foundation models and inference profiles for the credential's region, signed with
     * the stored credentials. The profiles are what most newer models are reached through; when
     * they cannot be read (a policy without bedrock:ListInferenceProfiles) the on-demand models
     * are still listed.
     */
    private List<CatalogModel> bedrockModels(String credential) throws CatalogueUnavailable {
        BedrockCredentials keys = bedrockCredentials(credential);
        String endpoint = BedrockCredentials.controlEndpoint(keys.regionOrDefault());
        JsonNode foundation = bedrockGet(endpoint + "/foundation-models?byOutputModality=TEXT", keys);
        List<JsonNode> profiles = new ArrayList<>();
        String token = null;
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                String url = endpoint + "/inference-profiles?maxResults=1000&typeEquals=SYSTEM_DEFINED"
                        + (token == null ? "" : "&nextToken=" + encode(token));
                JsonNode body = bedrockGet(url, keys);
                profiles.add(body);
                token = body.path("nextToken").asText("");
                if (token.isEmpty()) {
                    break;
                }
            }
        } catch (CatalogueUnavailable e) {
            log.warn("Bedrock inference profiles could not be listed: {}", e.getMessage());
        }
        return ModelListParsers.bedrock(foundation, profiles);
    }

    private BedrockCredentials bedrockCredentials(String credential) throws CatalogueUnavailable {
        if (credential == null || credential.isBlank()) {
            if (bedrock != null) {
                java.util.Optional<BedrockCredentials> role = bedrock.ambientCredentials();
                if (role.isPresent()) {
                    return role.get();
                }
            }
            throw new CatalogueUnavailable("no credentials");
        }
        try {
            return BedrockCredentials.parse(credential);
        } catch (BedrockCredentials.Invalid e) {
            throw new CatalogueUnavailable("unreadable credentials");
        }
    }

    private JsonNode bedrockGet(String url, BedrockCredentials keys) throws CatalogueUnavailable {
        URI uri = URI.create(url);
        BedrockCredentials inRegion = keys.region() != null
                ? keys
                : new BedrockCredentials(
                        keys.accessKeyId(), keys.secretAccessKey(), keys.sessionToken(), keys.apiKey(),
                        keys.regionOrDefault());
        return get(url, inRegion.authHeaders("GET", uri, new byte[0], null, clock.instant()));
    }

    /** The embedding rows saved for a provider, for when its own list cannot be read. */
    private List<CatalogModel> savedEmbeddings(String providerId) {
        return orEmpty(models.findByProviderIdAndEnabledTrue(providerId)).stream()
                .filter(row -> row.getMaxOutputTokens() == 1 && !row.isSupportsTools())
                .map(ModelCatalogService::fromRow)
                .toList();
    }

    private List<CatalogModel> anthropicPages(String base, Map<String, String> headers) throws CatalogueUnavailable {
        List<CatalogModel> out = new ArrayList<>();
        String after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String url = base + "/v1/models?limit=1000" + (after == null ? "" : "&after_id=" + encode(after));
            JsonNode body = get(url, headers);
            out.addAll(ModelListParsers.anthropic(body));
            String last = body.path("last_id").asText("");
            if (!body.path("has_more").asBoolean(false) || last.isEmpty()) {
                break;
            }
            after = last;
        }
        return out;
    }

    private List<CatalogModel> geminiPages(String base, Map<String, String> headers) throws CatalogueUnavailable {
        List<CatalogModel> out = new ArrayList<>();
        String token = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String url = base + "/v1beta/models?pageSize=1000" + (token == null ? "" : "&pageToken=" + encode(token));
            JsonNode body = get(url, headers);
            out.addAll(ModelListParsers.gemini(body));
            token = body.path("nextPageToken").asText("");
            if (token.isEmpty()) {
                break;
            }
        }
        return out;
    }

    private JsonNode get(String url, Map<String, String> headers) throws CatalogueUnavailable {
        ModelListHttp.Response response;
        try {
            response = http.get(URI.create(url), headers);
        } catch (IOException | IllegalArgumentException e) {
            throw new CatalogueUnavailable("not reachable (" + e.getClass().getSimpleName() + ")");
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new CatalogueUnavailable("status " + response.status());
        }
        try {
            JsonNode body = json.readTree(response.body());
            if (body == null || !body.isObject()) {
                throw new CatalogueUnavailable("not a JSON object");
            }
            return body;
        } catch (IOException e) {
            throw new CatalogueUnavailable("unreadable body");
        }
    }

    /** Which listing shape an OpenAI-compatible provider answers with. */
    static String flavour(LlmProviderEntity entity) {
        String id = entity.getId().toLowerCase(Locale.ROOT);
        String base = entity.getBaseUrl() == null ? "" : entity.getBaseUrl().toLowerCase(Locale.ROOT);
        if (id.equals("openrouter") || base.contains("openrouter.ai")) return "openrouter";
        if (id.equals("nvidia") || base.contains("nvidia.com")) return "nvidia";
        if (id.equals("openai") || base.contains("api.openai.com")) return "openai";
        if (id.equals("ollama") || base.contains(":11434")) return "ollama";
        return "generic";
    }

    /** Ollama's root, from its OpenAI-compatible base URL: {@code http://ollama:11434/v1} gives {@code http://ollama:11434}. */
    static String ollamaRoot(String base) {
        String root = stripSlash(base);
        return root.endsWith("/v1") ? root.substring(0, root.length() - 3) : root;
    }

    /** OpenRouter and NVIDIA list their models to anybody; everybody else wants the key. */
    private static boolean needsKey(LlmProviderEntity entity) {
        ProviderDescriptor.Kind kind = kindOf(entity);
        if (kind != ProviderDescriptor.Kind.OPENAI_COMPATIBLE) {
            return true;
        }
        String flavour = flavour(entity);
        return !flavour.equals("openrouter") && !flavour.equals("nvidia") && !flavour.equals("ollama");
    }

    // ---- Shaping ------------------------------------------------------------------------------

    /**
     * A listing that gives no price or a bare name borrows the seeded row's, which were checked by
     * hand: "Llama 3.3 70B" rather than one made from the id, and Groq's prices, which Groq's list
     * leaves out.
     */
    private List<CatalogModel> withSeededDetails(String providerId, List<CatalogModel> listed) {
        List<LlmModelEntity> seeded = models.findByProviderIdAndEnabledTrue(providerId);
        if (seeded == null || seeded.isEmpty()) {
            return listed;
        }
        Map<String, LlmModelEntity> byId = seeded.stream()
                .collect(Collectors.toMap(LlmModelEntity::getModelId, Function.identity(), (a, b) -> a));
        return listed.stream()
                .map(model -> {
                    LlmModelEntity seed = byId.get(model.id());
                    if (seed == null) {
                        return model;
                    }
                    boolean priced = model.pricePerMTokIn() != null;
                    return new CatalogModel(
                            model.id(),
                            seed.getDisplayName(),
                            model.free(),
                            model.toolCalling() || seed.isSupportsTools(),
                            model.vision() || seed.isSupportsVision(),
                            model.contextLength() > 0 ? model.contextLength() : seed.getContextWindow(),
                            model.maxOutputTokens() > 0 ? model.maxOutputTokens() : seed.getMaxOutputTokens(),
                            model.jsonMode() || seed.isSupportsJsonMode(),
                            priced ? model.pricePerMTokIn() : seed.getInputCostPerMillion(),
                            priced ? model.pricePerMTokOut() : seed.getOutputCostPerMillion(),
                            model.pricingNote());
                })
                .toList();
    }

    private CatalogueView view(
            LlmProviderEntity entity,
            Source source,
            Instant fetchedAt,
            String message,
            List<CatalogModel> listed,
            boolean all) {
        // One row per id: a listing that repeats a model shows it once.
        Map<String, CatalogModel> unique = new LinkedHashMap<>();
        listed.forEach(model -> unique.putIfAbsent(model.id(), model));
        List<ModelOption> options = unique.values().stream()
                .filter(model -> all || model.toolCalling())
                .map(model -> new ModelOption(
                        model.id(),
                        model.displayName(),
                        model.free(),
                        model.toolCalling(),
                        model.vision(),
                        Math.max(0, model.contextLength()),
                        model.free() ? BigDecimal.ZERO : model.pricePerMTokIn(),
                        model.free() ? BigDecimal.ZERO : model.pricePerMTokOut(),
                        model.pricingNote()))
                .sorted(ORDER)
                .toList();
        int free = (int) options.stream().filter(ModelOption::free).count();
        return new CatalogueView(
                entity.getId(), entity.getDisplayName(), source, fetchedAt, message, options.size(), free, options);
    }

    /** Free first, then by name, then by id. */
    static final Comparator<ModelOption> ORDER = Comparator.comparing((ModelOption option) -> !option.free())
            .thenComparing(option -> option.displayName().toLowerCase(Locale.ROOT))
            .thenComparing(ModelOption::id);

    /** The models saved for the provider: the seeded rows and any a listing wrote earlier. */
    private CatalogueView saved(LlmProviderEntity entity, boolean all, String message) {
        List<LlmModelEntity> seeded = orEmpty(models.findByProviderIdAndEnabledTrue(entity.getId()));
        List<LlmModelEntity> discovered =
                orEmpty(models.findByProviderIdAndEnabledTrueAndSource(entity.getId(), "discovered"));
        Map<String, CatalogModel> rows = new HashMap<>();
        Stream.concat(seeded.stream(), discovered.stream())
                .filter(row -> row.getMaxOutputTokens() > 1)
                .forEach(row -> rows.putIfAbsent(row.getModelId(), fromRow(row)));
        return view(entity, Source.saved, null, message, new ArrayList<>(rows.values()), all);
    }

    private static CatalogModel fromRow(LlmModelEntity row) {
        BigDecimal in = row.getInputCostPerMillion();
        BigDecimal out = row.getOutputCostPerMillion();
        boolean zero = in != null && out != null && in.signum() == 0 && out.signum() == 0;
        // A discovered row stores an unlisted price as zero, so zero is free only when it says so.
        boolean free = row.isFree() || (!row.isDiscovered() && zero);
        boolean priceKnown = free || !zero;
        return new CatalogModel(
                row.getModelId(),
                row.getDisplayName(),
                free,
                row.isSupportsTools(),
                row.isSupportsVision(),
                row.getContextWindow(),
                row.getMaxOutputTokens(),
                row.isSupportsJsonMode(),
                priceKnown ? in : null,
                priceKnown ? out : null,
                "nvidia".equals(row.getProviderId()) && free ? ModelListParsers.NVIDIA_FREE_NOTE : null);
    }

    private static ProviderDescriptor.Kind kindOf(LlmProviderEntity entity) {
        try {
            return ProviderDescriptor.Kind.valueOf(entity.getKind());
        } catch (IllegalArgumentException | NullPointerException e) {
            return ProviderDescriptor.Kind.OPENAI_COMPATIBLE;
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String stripSlash(String url) {
        String value = url == null ? "" : url.strip();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
