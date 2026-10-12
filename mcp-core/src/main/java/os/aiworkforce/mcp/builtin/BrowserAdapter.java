// @find: web browser connector, browser agent, open page, read page, follow link, fill field, click button, submit form, browser runner client, screenshot, live view, built in browser, BrowserAdapter
// @what: Built-in web browser connector: sends each browser tool call to the browser runner sidecar, gives the model the page as text and hands the screenshot on separately.
// @flow: Registered by SandboxServerRegistry for the browser server; invoked through ToolGateway.invoke; calls infra/browser-runner over HTTP; AgentRunner stores the screenshot from the result metadata
package os.aiworkforce.mcp.builtin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.spi.McpServerAdapter;

/**
 * The web browser every agent can be given, without connecting anything.
 *
 * <p>Each run gets its own page in the browser runner, a separate container with Chromium in it,
 * named by the run's id. Every call returns what is on the page as text - the title, the address,
 * the visible words, the links and the form fields - and the runner's screenshot travels beside it
 * in the result's metadata under {@link #SCREENSHOT}, never in the text the model reads. The run
 * engine stores that picture for the trace and drops it from the result.
 *
 * <p>Reading is free; typing into a field is a write that sends nothing; clicking a button and
 * submitting a form can send something to the site, so those two are OUTBOUND and wait for a
 * person, whatever the agent's grant says. Because a person may take longer to decide than the
 * runner keeps an idle page open, the page's address and the fields typed since it was opened are
 * remembered here and sent with a click or a submit, so the runner can put the page back first.
 */
public final class BrowserAdapter implements McpServerAdapter {

    /** Logs runner problems only; never page content or typed values. */
    private static final Logger log = LoggerFactory.getLogger(BrowserAdapter.class);

    /** Metadata key of the page's JPEG screenshot, base64-encoded. */
    public static final String SCREENSHOT = "screenshot";
    /** Metadata keys of the page's address and title when the screenshot was taken. */
    public static final String PAGE_URL = "pageUrl";
    public static final String PAGE_TITLE = "pageTitle";

    /** Header carrying the shared secret the runner requires on every request. */
    public static final String SECRET_HEADER = "X-Runner-Secret";

    /** Shown when the runner is not configured or rejects the shared secret. */
    static final String UNAVAILABLE =
            "The web browser is not available on this server yet, so nothing was opened. It is a server setting,"
                    + " not something to switch on in Connectors: an owner or admin needs to check the server's"
                    + " browser runner.";
    /** Shown when a step exceeds {@code CALL_TIMEOUT}. */
    static final String TOO_SLOW =
            "The web browser took too long, so the step was stopped. The page may be very slow; try again or try"
                    + " another page.";
    /** Shown when the runner cannot be reached, after the permitted single retry. */
    static final String NOT_ANSWERING =
            "The web browser did not answer, so nothing was done. Try again in a minute.";

    /** Largest runner answer accepted, sized for a page plus its base64 screenshot. */
    private static final int MAX_ANSWER_BYTES = 8 * 1024 * 1024;
    /*
     * The runner may hold a step in line for up to 15 s while other runs use the browser, then load
     * the page for up to 20 s and settle it for a few more; this leaves room for all of that.
     */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(70);
    /* The runner restarting (a deploy, or Chromium recovering) refuses connections for a moment. */
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(2);

    /**
     * Tool definitions, supplied by {@code SandboxServerRegistry} so the browser tools live with the others.
     */
    private final List<ToolDefinition> tools;
    private final ObjectMapper json;
    private final WebClient http;
    /** True when both the runner address and the secret were supplied. */
    private final boolean configured;
    /** Name lookup used by the address guard; replaced in tests. */
    private final BrowserAddresses.Resolver resolver;
    /* Per run: the open page and what was typed into it, to put the page back after a long approval. */
    private final Map<String, PageState> pages = new ConcurrentHashMap<>();

    /**
     * What is remembered about one run's page: its address, the fields the agent typed since it was
     * opened and, after a person had control of it, what the approver must be told about that.
     */
    private static final class PageState {
        private volatile String url;
        /* The agent's typing since the page was opened (or since a person last had control); a restore types these again. */
        private final List<ObjectNode> fills = new ArrayList<>();
        /* What the agent typed before a person took control: told to an approver, never typed again by a restore. */
        private final List<ObjectNode> earlierFills = new ArrayList<>();
        /* The labels of the fields a person typed into while they had control; never what they typed. */
        private final java.util.Set<String> personFields = new java.util.LinkedHashSet<>();
        /* Whether a person acted on this page while they had control, since the agent last moved it on. */
        private boolean personActed;

        /** Records that a person acted on the page: the agent's typing so far becomes "earlier", the field label is kept. Call under the lock. */
        private void notePerson(java.util.Collection<String> fields, String newUrl) {
            for (ObjectNode fill : fills) {
                earlierFills.removeIf(earlier -> earlier.path("field").asText().equals(fill.path("field").asText()));
                earlierFills.add(fill);
            }
            fills.clear();
            personActed = true;
            for (String field : fields) {
                if (field != null && !field.isBlank() && personFields.size() < 10) {
                    personFields.add(clip(field));
                }
            }
            if (newUrl != null && !newUrl.isBlank()) {
                url = newUrl;
            }
        }

        /** Forgets everything typed and any person's control: the agent moved the page on. Call under the lock. */
        private void movedOn() {
            fills.clear();
            earlierFills.clear();
            personFields.clear();
            personActed = false;
        }
    }

    /** Production constructor: uses the system name resolver for the address guard. */
    public BrowserAdapter(
            List<ToolDefinition> tools, ObjectMapper json, WebClient.Builder http, String runnerUrl, String secret) {
        this(tools, json, http, runnerUrl, secret, BrowserAddresses.SYSTEM);
    }

    /**
     * Builds the adapter. When the runner address and secret are both present, requests go to the
     * runner with the secret header and a larger response limit; otherwise every call answers that
     * the browser is unavailable and no network call is made.
     */
    public BrowserAdapter(
            List<ToolDefinition> tools,
            ObjectMapper json,
            WebClient.Builder http,
            String runnerUrl,
            String secret,
            BrowserAddresses.Resolver resolver) {
        this.tools = List.copyOf(tools);
        this.json = json;
        this.resolver = resolver;
        this.configured = runnerUrl != null && !runnerUrl.isBlank() && secret != null && !secret.isBlank();
        WebClient.Builder builder =
                http.clone().codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_ANSWER_BYTES));
        if (configured) {
            builder = builder.baseUrl(runnerUrl.strip()).defaultHeader(SECRET_HEADER, secret.strip());
        }
        this.http = builder.build();
    }

    /** The server name, {@code browser}. */
    @Override
    public String server() {
        return "browser";
    }

    /** The browser tools, as defined in the sandbox registry. */
    @Override
    public List<ToolDefinition> tools() {
        return tools;
    }

    /** Built in: nothing to connect, no credential looked up, and calls always run for real. */
    @Override
    public boolean isBuiltIn() {
        return true;
    }

    /** Whether a runner address and secret are configured; false means every call says the browser is off. */
    public boolean configured() {
        return configured;
    }

    /**
     * Asks the runner's health endpoint, with a five second limit. False when the browser is not
     * configured, answers with an error status or cannot be reached. Read-only; the credential is unused.
     */
    @Override
    public Mono<Boolean> healthCheck(String credential) {
        if (!configured) {
            return Mono.just(false);
        }
        return http.get().uri("/health").retrieve().toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .timeout(Duration.ofSeconds(5))
                .onErrorReturn(false);
    }

    // @find: browser live view, live frame of a run, what the browser is doing now, GET /sessions/{id}/live
    /**
     * The live view of one run's browser: what it is doing now ({@code busy}, {@code action}), the
     * page it has open ({@code url}, {@code title}) and a fresh JPEG frame ({@code frame}, base64).
     * Called by the orchestrator's live endpoint about once a second while someone watches a run.
     * Empty when the browser is not set up, the run has no page open, or the runner does not answer
     * within two seconds; never errors, so a watcher simply keeps the last picture it had.
     *
     * @param runId the run whose browser to show; the runner keys its pages by run id
     */
    public Mono<JsonNode> live(String runId) {
        if (!configured || runId == null || runId.isBlank()) {
            return Mono.empty();
        }
        String session = runId.replaceAll("[^A-Za-z0-9-]", "");
        return http.get()
                .uri("/sessions/{id}/live", session)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(2))
                .onErrorResume(error -> Mono.empty());
    }

    // @find: run browser tool, open page through runner, browser call
    /**
     * What the runner answered to a person's action on a run's page while they held control of it.
     *
     * @param ok whether the action was done
     * @param problem a plain sentence saying why not, when {@code ok} is false
     * @param url the page's address afterwards
     * @param title the page's title afterwards
     * @param frame a fresh JPEG of the page, base64; empty when none could be taken
     * @param field for typing, the label of the field typed into (never what was typed); else empty
     */
    public record TakeoverAnswer(boolean ok, String problem, String url, String title, String frame, String field) {

        /** An action that was not done, with the sentence that says why. */
        public static TakeoverAnswer refused(String problem) {
            return new TakeoverAnswer(false, problem, "", "", "", "");
        }
    }

    /** The person actions the runner accepts on a page someone has taken over. */
    public static final List<String> TAKEOVER_ACTIONS = List.of("click", "type", "key", "scroll", "navigate");

    /* Long enough for an address to load (20 s) and settle; a person's click or key press is quicker. */
    private static final Duration TAKEOVER_TIMEOUT = Duration.ofSeconds(35);

    // @find: take over browser, person controls the page, click type key scroll navigate, POST /sessions/{id}/takeover/{action}
    /**
     * Does one action of a person who has taken over a run's web browser: a click at frame
     * coordinates ({@code x}, {@code y}, {@code width}, {@code height}), typing {@code text} into the
     * focused field, pressing {@code key}, scrolling by {@code dy} or opening {@code url}.
     *
     * <p>Flow: refuse when the browser is not set up or the action is unknown; for {@code navigate},
     * apply the same address guard as {@code open_page} off the event loop first; then one POST to
     * {@code /sessions/{id}/takeover/{action}}, never retried (a click must not be made twice), limited
     * to 35 seconds. On success, when this server remembers the page, it notes that a person acted
     * (the agent's earlier typing is kept only to tell an approver, so a later restore never types
     * over what the person did) and moves to the new address. The server driving the run learns
     * the same through {@link #personActed} when control is handed back. Typed text is never
     * logged or kept here. Failure behaviour: every failure is a refused answer with a plain sentence,
     * never an error.
     *
     * @param runId the run whose page to act on; the runner keys its pages by run id
     * @param action one of {@link #TAKEOVER_ACTIONS}
     * @param body the action's checked values, as the runner expects them
     */
    public Mono<TakeoverAnswer> takeover(String runId, String action, ObjectNode body) {
        if (!configured) {
            return Mono.just(TakeoverAnswer.refused(UNAVAILABLE));
        }
        if (runId == null || runId.isBlank() || !TAKEOVER_ACTIONS.contains(action)) {
            return Mono.just(TakeoverAnswer.refused("The web browser cannot do that."));
        }
        String session = runId.replaceAll("[^A-Za-z0-9-]", "");
        if ("navigate".equals(action)) {
            String url = body.path("url").asText("").strip();
            return Mono.fromCallable(() -> {
                        String problem = BrowserAddresses.problem(url, resolver);
                        return problem == null ? "" : problem;
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(problem -> problem.isEmpty()
                            ? personAction(session, action, body)
                            : Mono.just(TakeoverAnswer.refused(problem)));
        }
        return personAction(session, action, body);
    }

    /**
     * The POST behind {@link #takeover}: sends the action, maps the runner's answer, and updates the
     * remembered page on success. Refusals, timeouts and an unreachable runner become refused answers.
     */
    private Mono<TakeoverAnswer> personAction(String session, String action, ObjectNode body) {
        return http.post()
                .uri("/sessions/{id}/takeover/{action}", session, action)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(TAKEOVER_TIMEOUT)
                .map(answer -> {
                    String url = answer.path("url").asText("");
                    String field = answer.path("field").asText("");
                    // Only a page this server already remembers: another server may be driving the
                    // run, and it is told through personActed when control comes back.
                    PageState page = pages.get(session);
                    if (page != null) {
                        synchronized (page) {
                            page.notePerson(field.isBlank() ? List.of() : List.of(field), url);
                        }
                    }
                    return new TakeoverAnswer(
                            true,
                            null,
                            url,
                            answer.path("title").asText(""),
                            answer.path("frame").asText(""),
                            answer.path("field").asText(""));
                })
                .onErrorResume(WebClientResponseException.class, refused ->
                        Mono.just(TakeoverAnswer.refused(refusal(refused).summary())))
                .onErrorResume(TimeoutException.class, slow -> Mono.just(TakeoverAnswer.refused(TOO_SLOW)))
                .onErrorResume(WebClientRequestException.class, down -> {
                    log.warn("The browser runner could not be reached for a person's {}: {}", action, down.getMessage());
                    return Mono.just(TakeoverAnswer.refused(NOT_ANSWERING));
                });
    }

    // @find: person acted on the run's page, takeover handed back, approval after takeover, forget agent typing
    /**
     * Tells this server that a person acted on a run's page while they had control: the labels of
     * the fields they typed into (never what they typed) and the page's address now. Called on the
     * server driving the run when control is handed back, since the person's actions may have been
     * served by another one.
     *
     * <p>Effects, under the page's lock: the agent's typing so far is kept only to tell an approver
     * and is never typed again by a restore; the person's field labels are kept; the address moves
     * on when one is given. An approval for the agent's next click or submit on this page then says
     * that a person had control and typed into those fields. Contacts nothing; never throws.
     *
     * @param runId the run whose page it is
     * @param fields the labels of the fields the person typed into; may be empty
     * @param url the page's address now, or null or blank to keep the remembered one
     */
    public void personActed(String runId, java.util.Collection<String> fields, String url) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        String session = runId.replaceAll("[^A-Za-z0-9-]", "");
        PageState page = pages.computeIfAbsent(session, id -> new PageState());
        synchronized (page) {
            page.notePerson(fields == null ? List.of() : fields, url);
        }
    }

    // @find: hold browser page while a person has control, keep page open during takeover, POST /sessions/{id}/takeover/hold
    /**
     * Asks the runner to keep a run's page open while a person holds it ({@code on} true), however
     * long they look without acting, or to let the usual idle rule apply again ({@code on} false).
     * The runner bounds a hold to 30 minutes itself. False when the browser is not set up or the
     * runner did not answer within five seconds; never errors.
     */
    public Mono<Boolean> hold(String runId, boolean on) {
        if (!configured || runId == null || runId.isBlank()) {
            return Mono.just(false);
        }
        String session = runId.replaceAll("[^A-Za-z0-9-]", "");
        return http.post()
                .uri("/sessions/{id}/takeover/hold", session)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json.createObjectNode().put("on", on))
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .timeout(Duration.ofSeconds(5))
                .onErrorReturn(false);
    }

    /**
     * Runs one browser tool against the run's page in the runner. The credential is unused.
     *
     * <p>Flow: fail plainly when not configured; parse the arguments; map the tool to a runner action
     * ({@code open_page} to open, {@code read_page} to read, {@code follow_link}, {@code search_site},
     * {@code fill_field}, {@code click_button}, {@code submit_form}); for {@code open_page} check the
     * address with {@link BrowserAddresses} off the event loop first; then {@link #call}. Side-effect
     * classes: open, read, follow and search read; fill writes only to the open page and sends
     * nothing; click and submit are outbound and are gated by the gateway, which asks a person first.
     * Failure behaviour: every failure is returned as a failed result with a plain sentence, never
     * thrown.
     */
    @Override
    public Mono<ToolResult> invoke(ToolInvocation invocation, String credential) {
        if (!configured) {
            return Mono.just(ToolResult.failed(UNAVAILABLE));
        }
        Instant startedAt = Instant.now();
        JsonNode arguments;
        try {
            arguments = json.readTree(invocation.argumentsJson());
        } catch (Exception e) {
            return Mono.just(ToolResult.failed("The arguments were not valid JSON."));
        }
        String session = sessionOf(invocation);
        PageState page = pages.computeIfAbsent(session, id -> new PageState());
        ObjectNode body = json.createObjectNode();
        String action;
        switch (invocation.tool()) {
            case "open_page" -> {
                String url = arguments.path("url").asText("").strip();
                return Mono.fromCallable(() -> {
                            String problem = BrowserAddresses.problem(url, resolver);
                            return problem == null ? "" : problem;
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(problem -> {
                            if (!problem.isEmpty()) {
                                return Mono.just(ToolResult.failed(problem));
                            }
                            ObjectNode open = json.createObjectNode().put("url", url);
                            String search = arguments.path("search").asText("").strip();
                            if (!search.isEmpty()) {
                                open.put("search", search);
                            }
                            return call(invocation, session, "open", open, page, startedAt);
                        });
            }
            case "read_page" -> {
                action = "read";
                if (arguments.path("from").canConvertToInt() && arguments.path("from").asInt() > 0) {
                    body.put("from", arguments.path("from").asInt());
                }
            }
            case "follow_link" -> {
                action = "follow";
                body.put("linkText", arguments.path("linkText").asText(""));
            }
            case "search_site" -> {
                action = "search";
                body.put("query", arguments.path("query").asText(""));
                String site = arguments.path("url").asText("").strip();
                if (!site.isEmpty()) {
                    body.put("url", site);
                    // The same address checks as open_page, off the request thread (they look the
                    // name up), before the runner is asked to open the site and search it.
                    return Mono.fromCallable(() -> {
                                String problem = BrowserAddresses.problem(site, resolver);
                                return problem == null ? "" : problem;
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(problem -> problem.isEmpty()
                                    ? call(invocation, session, "search", body, page, startedAt)
                                    : Mono.just(ToolResult.failed(problem)));
                }
                if (arguments.hasNonNull("field")) {
                    body.put("field", arguments.path("field").asText(""));
                }
            }
            case "fill_field" -> {
                action = "fill";
                body.put("field", arguments.path("field").asText(""));
                body.put("value", arguments.path("value").asText(""));
            }
            case "click_button" -> {
                action = "click";
                body.put("text", arguments.path("text").asText(""));
                body.set("restore", restoreOf(page));
            }
            case "submit_form" -> {
                action = "submit";
                if (arguments.hasNonNull("field")) {
                    body.put("field", arguments.path("field").asText(""));
                }
                body.set("restore", restoreOf(page));
            }
            default -> {
                return Mono.just(ToolResult.failed("The web browser has no tool named " + invocation.tool() + "."));
            }
        }
        return call(invocation, session, action, body, page, startedAt);
    }

    /**
     * Sends one action to the runner at {@code /sessions/{id}/{action}} and turns the answer into a
     * result. HTTP calls: one POST, with one retry after two seconds when the runner could not be
     * reached, only for actions that {@link #repeatable} allows. Limited to 70 seconds. A refusal
     * from the runner, a timeout and an unreachable runner each become a failed result. On success
     * the remembered page state is updated first.
     */
    private Mono<ToolResult> call(
            ToolInvocation invocation, String session, String action, ObjectNode body, PageState page, Instant startedAt) {
        return http.post()
                .uri("/sessions/{id}/{action}", session, action)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                // One more try when the runner could not be reached, for reading and typing only: a
                // click or a submit is never sent twice, in case the first one did reach the page.
                .retryWhen(Retry.fixedDelay(1, RECONNECT_DELAY)
                        .filter(error -> error instanceof WebClientRequestException && repeatable(action))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .timeout(CALL_TIMEOUT)
                .map(answer -> {
                    remember(page, action, body, answer);
                    return result(action, answer, Duration.between(startedAt, Instant.now()));
                })
                .onErrorResume(WebClientResponseException.class, refused -> Mono.just(refusal(refused)))
                .onErrorResume(TimeoutException.class, slow -> Mono.just(ToolResult.failed(TOO_SLOW)))
                .onErrorResume(WebClientRequestException.class, down -> {
                    log.warn("The browser runner could not be reached for {}: {}", invocation.qualifiedName(),
                            down.getMessage());
                    return Mono.just(ToolResult.failed(NOT_ANSWERING));
                });
    }

    /**
     * Steps that change nothing outside the page may be retried; clicks and submits never are.
     */
    static boolean repeatable(String action) {
        return switch (action) {
            case "open", "read", "follow", "search", "fill" -> true;
            default -> false;
        };
    }

    /**
     * The runner answers a refusal with {"error": "<plain sentence>"}; that sentence is the result.
     * A 401 or 403 means the shared secret is wrong and is reported as the browser being unavailable.
     */
    private ToolResult refusal(WebClientResponseException refused) {
        String message = null;
        try {
            message = json.readTree(refused.getResponseBodyAsString()).path("error").asText(null);
        } catch (Exception ignored) {
            // Not JSON: the generic sentence below.
        }
        if (refused.getStatusCode().value() == 401 || refused.getStatusCode().value() == 403) {
            log.warn("The browser runner refused the shared secret; check AIWOS_BROWSER_RUNNER_SECRET");
            return ToolResult.failed(UNAVAILABLE);
        }
        if (message == null || message.isBlank()) {
            message = "The web browser could not do that (status " + refused.getStatusCode().value() + ").";
        }
        return ToolResult.failed(message);
    }

    /**
     * Updates what is remembered about the page after a successful step: a fill is recorded (replacing
     * an earlier fill of the same field); anything other than a read clears the fills and what a
     * person did while they had control, since the page has moved on; the page address is refreshed
     * from the answer. Synchronised per page.
     */
    private void remember(PageState page, String action, ObjectNode body, JsonNode answer) {
        synchronized (page) {
            if ("fill".equals(action)) {
                page.fills.removeIf(fill -> fill.path("field").asText().equals(body.path("field").asText()));
                page.fills.add(body.deepCopy());
            } else if (!"read".equals(action)) {
                page.movedOn();
            }
            String url = answer.path("url").asText(null);
            if (url != null && !url.isBlank()) {
                page.url = url;
            }
        }
    }

    /**
     * The address and typed fields to send with a click or submit, so the runner can recreate the
     * page if it closed while a person was deciding. Copies under the page's lock.
     */
    private ObjectNode restoreOf(PageState page) {
        ObjectNode restore = json.createObjectNode();
        synchronized (page) {
            if (page.url != null) {
                restore.put("url", page.url);
            }
            ArrayNode fills = restore.putArray("fills");
            page.fills.forEach(fill -> fills.add(fill.deepCopy()));
        }
        return restore;
    }

    // @find: browser result for model, page as text, screenshot in metadata not in text
    /**
     * What the model reads: the page as text. The screenshot goes into the metadata only. The content
     * holds the address, title, visible text, links, fields and (when present) buttons and a note; the
     * summary is one sentence for the trace, chosen by the action.
     */
    ToolResult result(String action, JsonNode answer, Duration took) {
        String url = answer.path("url").asText("");
        String title = answer.path("title").asText("");
        ObjectNode content = json.createObjectNode();
        content.put("url", url);
        content.put("title", title);
        content.put("text", answer.path("text").asText(""));
        // Where the rest of a long page starts; read_page with from reads it.
        if (answer.path("more").canConvertToInt()) {
            content.put("more", answer.path("more").asInt());
        }
        // Empty lists are left out: the model reads every character of every page on every later turn.
        for (String list : List.of("links", "fields", "buttons")) {
            if (answer.path(list).isArray() && !answer.get(list).isEmpty()) {
                content.set(list, answer.get(list));
            }
        }
        if (answer.hasNonNull("note")) {
            content.put("note", answer.path("note").asText());
        }
        String page = title.isBlank() ? url : title + " (" + url + ")";
        String summary = switch (action) {
            case "open" -> "Opened " + page + ".";
            case "read" -> "Read " + page + ".";
            case "follow" -> "Followed a link to " + page + ".";
            case "search" -> "Searched the site; the page is now " + page + ".";
            case "fill" -> "Typed into a field on " + page + ". Nothing was sent.";
            case "click" -> "Clicked a button; the page is now " + page + ".";
            case "submit" -> "Submitted the form; the page is now " + page + ".";
            default -> "Used the web browser on " + page + ".";
        };
        Map<String, String> metadata = new java.util.LinkedHashMap<>();
        String screenshot = answer.path("screenshot").asText("");
        if (!screenshot.isBlank()) {
            metadata.put(SCREENSHOT, screenshot);
        }
        metadata.put(PAGE_URL, url);
        metadata.put(PAGE_TITLE, title);
        String text;
        try {
            text = json.writeValueAsString(content);
        } catch (Exception e) {
            text = "{}";
        }
        return new ToolResult(ToolResult.Status.SUCCEEDED, text, summary, took, metadata);
    }

    // @find: browser approval detail, what a click or submit will send, page and typed values for approver
    /**
     * For a click or a submit, the page it acts on and what was typed into that page, so the person
     * approving it sees what would reach the site: a submit's own arguments are usually empty.
     * When a person had control of the page since the agent opened it, says so, names the fields
     * they typed into (never their values, which are not known here) and what the agent had typed
     * before; it never claims that nothing was typed then. Empty for every other tool. Read from the
     * remembered state only; contacts nothing. Used by the gateway when it builds the approval request.
     */
    @Override
    public Optional<String> approvalDetail(ToolInvocation invocation) {
        boolean click = "click_button".equals(invocation.tool());
        if (!click && !"submit_form".equals(invocation.tool())) {
            return Optional.empty();
        }
        PageState page = pages.get(sessionOf(invocation));
        String url = null;
        List<ObjectNode> fills = List.of();
        List<ObjectNode> earlier = List.of();
        List<String> personFields = List.of();
        boolean personActed = false;
        if (page != null) {
            synchronized (page) {
                url = page.url;
                fills = List.copyOf(page.fills);
                earlier = List.copyOf(page.earlierFills);
                personFields = List.copyOf(page.personFields);
                personActed = page.personActed;
            }
        }
        StringBuilder text = new StringBuilder();
        if (click) {
            String button = "";
            try {
                button = json.readTree(invocation.argumentsJson()).path("text").asText("").strip();
            } catch (Exception ignored) {
                // Unreadable arguments: the sentence names no button.
            }
            text.append(button.isEmpty() ? "Clicks a button" : "Clicks the button \"" + clip(button) + "\"");
        } else {
            text.append("Submits the form");
        }
        text.append(url == null || url.isBlank() ? " on the open page" : " on " + clip(url));
        if (fills.isEmpty() && !personActed) {
            text.append(". Nothing was typed into the page.");
        } else if (fills.isEmpty()) {
            text.append('.');
        } else {
            text.append(", sending what was typed: ").append(typedList(fills)).append('.');
        }
        if (personActed) {
            text.append(" A person had control of this page before this step");
            if (!personFields.isEmpty()) {
                List<String> named = personFields.stream().map(field -> "\"" + field + "\"").toList();
                text.append(" and typed into ").append(String.join(", ", named))
                        .append(" (what they typed is not shown here)");
            }
            text.append(", so the page may hold more than the agent typed.");
            if (!earlier.isEmpty()) {
                text.append(" Before they took control, the agent had typed: ").append(typedList(earlier)).append('.');
            }
        }
        return Optional.of(text.toString());
    }

    /** "Field = "value"; Other = "value"" for typed fields, each part shortened. */
    private static String typedList(List<ObjectNode> fills) {
        List<String> typed = new ArrayList<>();
        for (ObjectNode fill : fills) {
            typed.add(clip(fill.path("field").asText("a field")) + " = \"" + clip(fill.path("value").asText("")) + "\"");
        }
        return String.join("; ", typed);
    }

    /** Collapses whitespace and shortens a value to 200 characters so an approval sentence stays readable. */
    private static String clip(String value) {
        String flat = value.replaceAll("\\s+", " ").strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 199) + "…";
    }

    // @find: close browser session when run ends
    /**
     * Forgets the run's page and, when one was open, asks the runner to close its session. Fire and
     * forget: a failure is logged at debug level only. Called by the gateway when a run ends.
     */
    @Override
    public void releaseRun(String runId) {
        if (runId == null || pages.remove(runId) == null || !configured) {
            return;
        }
        http.delete()
                .uri("/sessions/{id}", runId)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(5))
                .subscribe(done -> {}, error -> log.debug("Browser session {} not closed: {}", runId, error.toString()));
    }

    /** The runner session id: the run id, or the workspace id when there is none, stripped to safe characters. */
    private static String sessionOf(ToolInvocation invocation) {
        String id = invocation.runId() == null || invocation.runId().isBlank()
                ? "org-" + invocation.orgId()
                : invocation.runId();
        return id.replaceAll("[^A-Za-z0-9-]", "");
    }
}
