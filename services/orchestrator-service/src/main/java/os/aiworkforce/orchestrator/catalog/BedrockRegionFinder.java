// @find: Bedrock region finder, find my region, AWS region, Bedrock credential check, which region works, ListFoundationModels, Converse test call, BedrockRegionFinder, Find my region button
// @what: Checks which AWS regions accept a pasted Bedrock credential and in which a model really answers.
// @flow: Called by the model catalogue endpoints behind the Find my region button in provider settings.
package os.aiworkforce.orchestrator.catalog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import os.aiworkforce.llm.bedrock.AwsSigV4;
import os.aiworkforce.llm.bedrock.BedrockCredentials;

/**
 * "Find my region" for Amazon Bedrock: which AWS regions accept a pasted credential, and in which
 * of them a model answers.
 *
 * <p>Every Bedrock region is asked at once for its model list (ListFoundationModels), each with a
 * short timeout. The few regions that accept the credential are then sent one tiny Converse call
 * (one output token) on a small model the region lists, so the answer says not only "the key
 * works here" but "a model answers here". The best region is the first that answered, preferring
 * the one the person already chose.
 *
 * <p>The credential lives only for the call. Nothing here logs it, a header, or anything AWS said
 * back, and the result carries no part of it: only region ids, counts and plain sentences.
 */
@Service
public class BedrockRegionFinder {

    private static final Logger log = LoggerFactory.getLogger(BedrockRegionFinder.class);

    /** How long one region's model list may take. */
    static final Duration LIST_TIMEOUT = Duration.ofSeconds(6);

    /** How long the one-token call may take. */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    /** At most this many accepting regions get the one-token call, so a check costs almost nothing. */
    static final int CALL_LIMIT = 3;

    /** Small, cheap models to spend the one token on, in order; otherwise the first on-demand text model listed. */
    static final List<String> PROBE_MODELS = List.of(
            "amazon.nova-micro-v1:0",
            "amazon.nova-lite-v1:0",
            "amazon.titan-text-lite-v1",
            "meta.llama3-2-1b-instruct-v1:0",
            "meta.llama3-8b-instruct-v1:0",
            "mistral.mistral-7b-instruct-v0:2",
            "anthropic.claude-3-haiku-20240307-v1:0");

    /**
     * Every region the console offers for Bedrock, the opt-in ones included: a region that is not
     * turned on for the account answers as refused, which is worth knowing too.
     */
    static final List<String> REGIONS = List.of(
            "us-east-1", "us-east-2", "us-west-1", "us-west-2",
            "ca-central-1", "ca-west-1", "mx-central-1", "sa-east-1",
            "eu-central-1", "eu-central-2", "eu-west-1", "eu-west-2", "eu-west-3", "eu-north-1", "eu-south-1",
            "eu-south-2",
            "il-central-1", "me-central-1", "me-south-1",
            "ap-south-1", "ap-south-2", "ap-southeast-1", "ap-southeast-2", "ap-southeast-3", "ap-southeast-4",
            "ap-southeast-5", "ap-southeast-7", "ap-northeast-1", "ap-northeast-2", "ap-northeast-3", "ap-east-2",
            "us-gov-west-1", "us-gov-east-1");

    /** Where the regions are tried first when nothing else separates them: the largest model lists. */
    static final List<String> PREFERRED = List.of("us-east-1", "us-west-2", "eu-central-1", "ap-northeast-1");

    /** What one region came to. The wire names are the ones the console switches on. */
    public enum Status {
        /** The credential works and a model answered. */
        ready,
        /** The credential works, but the small model tried could not answer: model access is not on. */
        no_model_access,
        /** The credential works here; no model was tried (only the first few accepting regions are). */
        accepted,
        /** The credential was refused here, or the region is not turned on for the account. */
        refused,
        /** No answer in time, or AWS had a fault. */
        unreachable
    }

    /**
     * @param modelCount text models the region lists for this account; null when the list could not be read
     * @param message plain words, safe to show; never what AWS said
     */
    public record RegionResult(String region, Status status, Integer modelCount, String message) {}

    /**
     * @param best the region to choose, or null when none accepted the credential
     */
    public record Finding(String best, List<RegionResult> regions, String message) {}

    /** The two calls the finder makes. Behind an interface so it is tested without a network. */
    public interface Transport {
        ModelListHttp.Response send(String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout)
                throws IOException;

        static Transport jdk() {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(4))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            return (method, uri, headers, body, timeout) -> {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("Accept", "application/json")
                        .method(method, body == null || body.length == 0
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
                headers.forEach((name, value) -> {
                    // The JDK client sets Host itself and refuses to be told it.
                    if (!name.equalsIgnoreCase("host")) {
                        request.header(name, value);
                    }
                });
                try {
                    HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                    return new ModelListHttp.Response(response.statusCode(), response.body());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
            };
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Transport transport;
    private final Clock clock;
    private final List<String> regions;

    @Autowired
    public BedrockRegionFinder() {
        this(Transport.jdk(), Clock.systemUTC(), REGIONS);
    }

    BedrockRegionFinder(Transport transport, Clock clock, List<String> regions) {
        this.transport = transport;
        this.clock = clock;
        this.regions = List.copyOf(regions);
    }

    /**
     * Asks every region, then sends the one-token call to the best few that accepted.
     *
     * @param credentials parsed and checked already; its own region is the one preferred
     */
    // @find: find Bedrock region, find my region, test credential across regions
    public Finding find(BedrockCredentials credentials) {
        String chosen = credentials.region();
        // GovCloud is a separate AWS partition: a commercial credential never works there, and a
        // GovCloud one is asked only when the person chose a GovCloud region.
        boolean gov = chosen != null && chosen.startsWith("us-gov-");
        List<String> asked = regions.stream().filter(region -> region.startsWith("us-gov-") == gov).toList();

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<CompletableFuture<Listed>> lists = asked.stream()
                    .map(region -> CompletableFuture.supplyAsync(() -> list(credentials, region), pool))
                    .toList();
            List<Listed> listed = new ArrayList<>();
            for (int i = 0; i < lists.size(); i++) {
                listed.add(await(lists.get(i), LIST_TIMEOUT.plusSeconds(2), new Listed(asked.get(i), Status.unreachable, null, List.of())));
            }

            List<Listed> accepting = listed.stream()
                    .filter(entry -> entry.status() == Status.accepted)
                    .sorted(order(chosen))
                    .toList();
            List<Listed> toCall = accepting.stream().limit(CALL_LIMIT).toList();
            List<CompletableFuture<Listed>> calls = toCall.stream()
                    .map(entry -> CompletableFuture.supplyAsync(() -> call(credentials, entry), pool))
                    .toList();
            Map<String, Listed> called = new java.util.HashMap<>();
            for (int i = 0; i < calls.size(); i++) {
                Listed fallback = toCall.get(i);
                Listed result = await(calls.get(i), CALL_TIMEOUT.plusSeconds(2), fallback);
                called.put(result.region(), result);
            }

            List<RegionResult> results = new ArrayList<>();
            for (Listed entry : listed) {
                Listed outcome = called.getOrDefault(entry.region(), entry);
                results.add(new RegionResult(
                        outcome.region(), outcome.status(), outcome.modelCount(), sentence(outcome)));
            }
            results.sort(resultOrder(chosen));

            String best = results.stream()
                    .filter(result -> result.status() == Status.ready)
                    .map(RegionResult::region)
                    .findFirst()
                    .orElseGet(() -> results.stream()
                            .filter(result -> result.status() == Status.accepted || result.status() == Status.no_model_access)
                            .map(RegionResult::region)
                            .findFirst()
                            .orElse(null));
            long ready = results.stream().filter(result -> result.status() == Status.ready).count();
            long accepted = results.stream().filter(result -> result.status() != Status.refused && result.status() != Status.unreachable).count();
            String message = best == null
                    ? results.stream().allMatch(result -> result.status() == Status.unreachable)
                            ? "No Bedrock region answered in time. Check your connection, then try again."
                            : "No region accepted these credentials. Check that you copied the whole key, with nothing around it."
                    : ready > 0
                            ? "These credentials work in " + accepted + (accepted == 1 ? " region" : " regions")
                                    + ". " + best + " is chosen: a model answered there."
                            : "These credentials work in " + accepted + (accepted == 1 ? " region" : " regions")
                                    + ", but no model answered yet. Turn on model access in the Amazon Bedrock"
                                    + " console for " + best + ", then check again.";
            log.info("Bedrock region search: {} of {} regions accepted, best {}", accepted, asked.size(), best);
            return new Finding(best, results, message);
        } finally {
            pool.shutdownNow();
        }
    }

    /** One region's list: whether the credential is accepted there, and what it may run. */
    record Listed(String region, Status status, Integer modelCount, List<String> onDemandModels) {}

    Listed list(BedrockCredentials credentials, String region) {
        URI uri = URI.create(BedrockCredentials.controlEndpoint(region) + "/foundation-models?byOutputModality=TEXT");
        BedrockCredentials inRegion = inRegion(credentials, region);
        ModelListHttp.Response response;
        try {
            response = transport.send(
                    "GET", uri, inRegion.authHeaders("GET", uri, new byte[0], null, clock.instant()), new byte[0], LIST_TIMEOUT);
        } catch (IOException | RuntimeException e) {
            return new Listed(region, Status.unreachable, null, List.of());
        }
        int status = response.status();
        if (status >= 200 && status < 300) {
            List<String> onDemand = new ArrayList<>();
            int count = 0;
            try {
                JsonNode body = JSON.readTree(response.body() == null ? "" : response.body());
                for (JsonNode model : body.path("modelSummaries")) {
                    count++;
                    boolean active = !"LEGACY".equalsIgnoreCase(model.path("modelLifecycle").path("status").asText(""));
                    boolean onDemandType = false;
                    for (JsonNode type : model.path("inferenceTypesSupported")) {
                        onDemandType |= "ON_DEMAND".equalsIgnoreCase(type.asText());
                    }
                    if (active && onDemandType) {
                        onDemand.add(model.path("modelId").asText(""));
                    }
                }
            } catch (IOException e) {
                return new Listed(region, Status.accepted, null, List.of());
            }
            return new Listed(region, Status.accepted, count, List.copyOf(onDemand));
        }
        String type = errorType(response);
        // Allowed in, but not to list models: the credential works, so the one-token call decides.
        if (status == 403 && type.equals("AccessDeniedException") && !refusesCaller(response.body())) {
            return new Listed(region, Status.accepted, null, List.of());
        }
        if (status == 400 || status == 401 || status == 403) {
            return new Listed(region, Status.refused, null, List.of());
        }
        return new Listed(region, Status.unreachable, null, List.of());
    }

    /** The one-token call, on the first small model the region offers on demand. */
    Listed call(BedrockCredentials credentials, Listed listed) {
        String model = probeModel(listed.onDemandModels());
        URI uri = URI.create(BedrockCredentials.runtimeEndpoint(listed.region()) + "/model/" + AwsSigV4.encode(model) + "/converse");
        byte[] body = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"Reply with ok.\"}]}],\"inferenceConfig\":{\"maxTokens\":1}}"
                .getBytes(StandardCharsets.UTF_8);
        BedrockCredentials inRegion = inRegion(credentials, listed.region());
        ModelListHttp.Response response;
        try {
            response = transport.send(
                    "POST",
                    uri,
                    inRegion.authHeaders("POST", uri, body, "application/json", clock.instant()),
                    body,
                    CALL_TIMEOUT);
        } catch (IOException | RuntimeException e) {
            return listed;
        }
        int status = response.status();
        if (status >= 200 && status < 300) {
            return new Listed(listed.region(), Status.ready, listed.modelCount(), listed.onDemandModels());
        }
        if ((status == 401 || status == 403) && refusesCaller(response.body())) {
            return new Listed(listed.region(), Status.refused, listed.modelCount(), listed.onDemandModels());
        }
        if (status == 400 || status == 403 || status == 404) {
            // Model access not turned on, a model that needs an inference profile here, or IAM not
            // allowing the call: the credential works, a model did not answer.
            return new Listed(listed.region(), Status.no_model_access, listed.modelCount(), listed.onDemandModels());
        }
        return listed;
    }

    static String probeModel(List<String> onDemand) {
        for (String preferred : PROBE_MODELS) {
            if (onDemand.contains(preferred)) {
                return preferred;
            }
        }
        return onDemand.stream()
                .filter(id -> id.startsWith("amazon.nova") || id.startsWith("meta.") || id.startsWith("mistral."))
                .findFirst()
                .orElse(onDemand.isEmpty() ? PROBE_MODELS.getFirst() : onDemand.getFirst());
    }

    /** Words in a 403 that say the credential itself was refused, rather than one action. */
    static boolean refusesCaller(String body) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return lower.contains("security token") || lower.contains("signature") || lower.contains("api key")
                || lower.contains("bearer") || lower.contains("authentication failed") || lower.contains("expired")
                || lower.contains("invalid");
    }

    private static String errorType(ModelListHttp.Response response) {
        try {
            JsonNode node = JSON.readTree(response.body() == null ? "" : response.body());
            String raw = node == null ? "" : node.path("__type").asText(node.path("code").asText(""));
            int hash = raw.lastIndexOf('#');
            raw = hash >= 0 ? raw.substring(hash + 1) : raw;
            int colon = raw.indexOf(':');
            return colon >= 0 ? raw.substring(0, colon) : raw;
        } catch (IOException e) {
            return "";
        }
    }

    static String sentence(Listed entry) {
        return switch (entry.status()) {
            case ready -> "Works, and a model answered.";
            case no_model_access -> "Accepts the credentials, but no model answered. Turn on model access here.";
            case accepted -> entry.modelCount() == null
                    ? "Accepts the credentials."
                    : "Accepts the credentials. " + entry.modelCount() + " text models listed.";
            case refused -> "Did not accept these credentials, or is not turned on for the account.";
            case unreachable -> "No answer in time.";
        };
    }

    /** The chosen region first, then the preferred ones, then the most models, then the usual order. */
    private Comparator<Listed> order(String chosen) {
        return Comparator.<Listed>comparingInt(entry -> entry.region().equals(chosen) ? 0 : 1)
                .thenComparingInt(entry -> preference(entry.region()))
                .thenComparing(entry -> entry.modelCount() == null ? 0 : -entry.modelCount())
                .thenComparingInt(entry -> regions.indexOf(entry.region()));
    }

    private Comparator<RegionResult> resultOrder(String chosen) {
        return Comparator.<RegionResult>comparingInt(result -> rank(result.status()))
                .thenComparingInt(result -> result.region().equals(chosen) ? 0 : 1)
                .thenComparingInt(result -> preference(result.region()))
                .thenComparingInt(result -> regions.indexOf(result.region()));
    }

    private static int rank(Status status) {
        return switch (status) {
            case ready -> 0;
            case accepted -> 1;
            case no_model_access -> 2;
            case unreachable -> 3;
            case refused -> 4;
        };
    }

    private static int preference(String region) {
        int index = PREFERRED.indexOf(region);
        return index < 0 ? PREFERRED.size() : index;
    }

    private static BedrockCredentials inRegion(BedrockCredentials keys, String region) {
        return new BedrockCredentials(keys.accessKeyId(), keys.secretAccessKey(), keys.sessionToken(), keys.apiKey(), region);
    }

    private static <T> T await(CompletableFuture<T> future, Duration limit, T fallback) {
        try {
            return future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return fallback;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
