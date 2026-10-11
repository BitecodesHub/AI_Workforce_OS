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

    private static final Logger log = LoggerFactory.getLogger(BrowserAdapter.class);

    /** Metadata key of the page's JPEG screenshot, base64-encoded. */
    public static final String SCREENSHOT = "screenshot";
    /** Metadata keys of the page's address and title when the screenshot was taken. */
    public static final String PAGE_URL = "pageUrl";
    public static final String PAGE_TITLE = "pageTitle";

    public static final String SECRET_HEADER = "X-Runner-Secret";

    static final String UNAVAILABLE =
            "The web browser is not available on this server yet, so nothing was opened. It is a server setting,"
                    + " not something to switch on in Connectors: an owner or admin needs to check the server's"
                    + " browser runner.";
    static final String TOO_SLOW =
            "The web browser took too long, so the step was stopped. The page may be very slow; try again or try"
                    + " another page.";
    static final String NOT_ANSWERING =
            "The web browser did not answer, so nothing was done. Try again in a minute.";

    private static final int MAX_ANSWER_BYTES = 8 * 1024 * 1024;
    /*
     * The runner may hold a step in line for up to 15 s while other runs use the browser, then load
     * the page for up to 20 s and settle it for a few more; this leaves room for all of that.
     */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(70);
    /* The runner restarting (a deploy, or Chromium recovering) refuses connections for a moment. */
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(2);

    private final List<ToolDefinition> tools;
    private final ObjectMapper json;
    private final WebClient http;
    private final boolean configured;
    private final BrowserAddresses.Resolver resolver;
    /* Per run: the open page and what was typed into it, to put the page back after a long approval. */
    private final Map<String, PageState> pages = new ConcurrentHashMap<>();

    private static final class PageState {
        private volatile String url;
        private final List<ObjectNode> fills = new ArrayList<>();
    }

    public BrowserAdapter(
            List<ToolDefinition> tools, ObjectMapper json, WebClient.Builder http, String runnerUrl, String secret) {
        this(tools, json, http, runnerUrl, secret, BrowserAddresses.SYSTEM);
    }

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

    @Override
    public String server() {
        return "browser";
    }

    @Override
    public List<ToolDefinition> tools() {
        return tools;
    }

    @Override
    public boolean isBuiltIn() {
        return true;
    }

    /** Whether a runner address and secret are configured; false means every call says the browser is off. */
    public boolean configured() {
        return configured;
    }

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

    // @find: run browser tool, open page through runner, browser call
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
                            return call(invocation, session, "open", open, page, startedAt);
                        });
            }
            case "read_page" -> action = "read";
            case "follow_link" -> {
                action = "follow";
                body.put("linkText", arguments.path("linkText").asText(""));
            }
            case "search_site" -> {
                action = "search";
                body.put("query", arguments.path("query").asText(""));
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

    /* Steps that change nothing outside the page may be retried; clicks and submits never are. */
    static boolean repeatable(String action) {
        return switch (action) {
            case "open", "read", "follow", "search", "fill" -> true;
            default -> false;
        };
    }

    /* The runner answers a refusal with {"error": "<plain sentence>"}; that sentence is the result. */
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

    private void remember(PageState page, String action, ObjectNode body, JsonNode answer) {
        synchronized (page) {
            if ("fill".equals(action)) {
                page.fills.removeIf(fill -> fill.path("field").asText().equals(body.path("field").asText()));
                page.fills.add(body.deepCopy());
            } else if (!"read".equals(action)) {
                page.fills.clear();
            }
            String url = answer.path("url").asText(null);
            if (url != null && !url.isBlank()) {
                page.url = url;
            }
        }
    }

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
    /** What the model reads: the page as text. The screenshot goes into the metadata only. */
    ToolResult result(String action, JsonNode answer, Duration took) {
        String url = answer.path("url").asText("");
        String title = answer.path("title").asText("");
        ObjectNode content = json.createObjectNode();
        content.put("url", url);
        content.put("title", title);
        content.put("text", answer.path("text").asText(""));
        content.set("links", answer.path("links").isArray() ? answer.get("links") : json.createArrayNode());
        content.set("fields", answer.path("fields").isArray() ? answer.get("fields") : json.createArrayNode());
        if (answer.path("buttons").isArray()) {
            content.set("buttons", answer.get("buttons"));
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
        if (page != null) {
            synchronized (page) {
                url = page.url;
                fills = List.copyOf(page.fills);
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
        if (fills.isEmpty()) {
            text.append(". Nothing was typed into the page.");
        } else {
            text.append(", sending what was typed: ");
            List<String> typed = new ArrayList<>();
            for (ObjectNode fill : fills) {
                typed.add(clip(fill.path("field").asText("a field")) + " = \"" + clip(fill.path("value").asText("")) + "\"");
            }
            text.append(String.join("; ", typed)).append('.');
        }
        return Optional.of(text.toString());
    }

    private static String clip(String value) {
        String flat = value.replaceAll("\\s+", " ").strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 199) + "…";
    }

    // @find: close browser session when run ends
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

    private static String sessionOf(ToolInvocation invocation) {
        String id = invocation.runId() == null || invocation.runId().isBlank()
                ? "org-" + invocation.orgId()
                : invocation.runId();
        return id.replaceAll("[^A-Za-z0-9-]", "");
    }
}
