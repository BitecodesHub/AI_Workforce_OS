// @find: tool gateway, approvals policy, approval gate, grants, scopes, per run call limit, tool timeout, evaluate tool call, invoke tool, available tools for agent, connected servers, indeterminate, gmail, slack, github, jira, confluence, asana, zendesk, stripe, zoom, hubspot, linear, notion, salesforce, outlook, teams, calendar, drive, sheets, webhook
// @what: The single path every tool call takes: checks grant, scope, arguments, per-run ceiling and approval, then runs the adapter under a timeout.
// @flow: Called by the orchestrator and ConnectorService; calls McpServerAdapter.invoke and ArgumentValidator
package os.aiworkforce.mcp.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import os.aiworkforce.llm.model.ToolNames;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.spi.McpServerAdapter;
import os.aiworkforce.platform.resilience.ResiliencePresets;

/**
 * The single path every tool call takes.
 *
 * <p>There is exactly one of these on purpose. Governance that is applied per adapter is
 * governance that differs per vendor, and the difference is always discovered after something has
 * already been sent. {@link #evaluate} makes its checks in a fixed order, and each exists because
 * skipping it produces a specific, known failure:
 *
 * <ol>
 *   <li><b>Server and tool.</b> The server has a registered adapter and offers the tool.
 *   <li><b>Grant.</b> The agent may reach this server and this tool at all.
 *   <li><b>Scope.</b> The grant carries what the tool needs. A missing scope is reported by name,
 *       so an administrator knows what to add rather than guessing.
 *   <li><b>Arguments.</b> Validated against the tool's own schema, before anything leaves. A
 *       model that hallucinated a field should not discover that at the provider.
 *   <li><b>Per-run ceiling.</b> The grant's {@code maxCallsPerRun}, counted across the whole run,
 *       so one looping agent cannot call a tool without limit.
 *   <li><b>Approval.</b> Outbound and destructive actions always park, whatever the policy says;
 *       the grant, the workspace policy or the adapter (per call) can add a gate.
 * </ol>
 *
 * <p>{@link #invoke} then runs an evaluated call: it validates the arguments again, counts the
 * call against the run, and calls the adapter under the tool's own timeout. A timeout or error is
 * returned as a result, never thrown, and a timeout on a tool that is not idempotent is reported
 * as indeterminate rather than failed.
 *
 * <p>The gateway logs each outcome but does not write audit entries: the callers (the orchestrator
 * and the connector service) record the audit entry from the decision and the result returned
 * here. The {@code ResiliencePresets} passed to the constructor is held but not used by this class.
 *
 * <p>Callers: the orchestrator ({@code evaluate}, then {@code invoke} after any approval) and the
 * connector service. Adapters are discovered as Spring beans at construction.
 */
@Service
public class ToolGateway {

    /** Gateway log. Holds decisions and outcomes only, never credentials or argument values. */
    private static final Logger log = LoggerFactory.getLogger(ToolGateway.class);

    private final Map<String, McpServerAdapter> adapters = new ConcurrentHashMap<>();
    private final ArgumentValidator validator;
    private final ResiliencePresets resilience;

    /* Per-run call counts, so a grant's ceiling is enforced across a whole run rather than
     * per request. Cleared when the run ends. */
    private final Map<String, Map<String, AtomicInteger>> runCallCounts = new ConcurrentHashMap<>();

    /**
     * Registers every adapter bean by server name. Rejects the reserved names {@code person} and
     * {@code memory} with {@code IllegalStateException}, which stops startup: those namespaces belong to the
     * orchestrator and an adapter under them would disguise a person or a memory note as a connector.
     */
    public ToolGateway(List<McpServerAdapter> servers, ArgumentValidator validator, ResiliencePresets resilience) {
        servers.forEach(adapter -> {
            // person.* is answered by a person inside the orchestrator, not by a server. An
            // adapter under that name would make a question to a person look like a tool call.
            if (ToolNames.PERSON_SERVER.equals(adapter.server())) {
                throw new IllegalStateException("The server name 'person' is reserved for questions to a person.");
            }
            // memory.* is the agent's own notes, kept by the memory service and used inside the
            // orchestrator. An adapter under that name would make a connected server look like it.
            if (ToolNames.MEMORY_SERVER.equals(adapter.server())) {
                throw new IllegalStateException("The server name 'memory' is reserved for an agent's own memory.");
            }
            adapters.put(adapter.server(), adapter);
        });
        this.validator = validator;
        this.resilience = resilience;
        log.info("Tool gateway ready with {} server(s): {}", adapters.size(), adapters.keySet());
    }

    // @find: tools an agent may see, grants filter, which tools can the agent use
    /**
     * What the model is allowed to see, which is only what the agent may actually call. Keeps a
     * tool only when its grant is enabled, names the tool (or covers every tool) and carries all the
     * scopes the tool needs. Servers with no adapter are skipped. Pure; contacts no provider.
     */
    public List<ToolDefinition> availableTools(List<ToolGrant> grants) {
        return grants.stream()
                .filter(ToolGrant::enabled)
                .flatMap(grant -> Optional.ofNullable(adapters.get(grant.server()))
                        .map(adapter -> adapter.tools().stream()
                                .filter(tool -> grant.covers(tool.name()))
                                .filter(tool -> grant.hasScopes(tool.requiredScopes())))
                        .orElseGet(java.util.stream.Stream::empty))
                .toList();
    }

    // @find: decide approval, does this call need approval, grant scope check, always approve send and delete, refuse or proceed
    /**
     * Decides what should happen to a call, without making it.
     *
     * <p>Separate from {@link #invoke} so the orchestrator can park a run before spending
     * anything, and so the same decision can be re-evaluated after an approval without the
     * checks being duplicated in two places.
     *
     * <p>Order of checks: server known, tool known, grant covers the tool, scopes present, argument
     * validation, per-run ceiling, then approval. A call needs approval when the tool is outbound or
     * destructive, the grant or the workspace policy asks for it, or the adapter names a per-call
     * reason. Outputs: {@code Refuse}, {@code Invalid}, {@code AwaitApproval} (approver permission
     * {@code approval:decide}) or {@code Proceed}. Has no side effects and sends nothing.
     */
    public ApprovalDecision evaluate(
            ToolInvocation invocation, List<ToolGrant> grants, boolean policyRequiresApproval) {
        McpServerAdapter adapter = adapters.get(invocation.server());
        if (adapter == null) {
            return new ApprovalDecision.Refuse("No server named " + invocation.server() + " is connected.");
        }

        Optional<ToolDefinition> maybeTool = adapter.tool(invocation.tool());
        if (maybeTool.isEmpty()) {
            return new ApprovalDecision.Refuse(
                    "The connected server does not offer a tool named " + invocation.tool() + ".");
        }
        ToolDefinition tool = maybeTool.get();

        Optional<ToolGrant> maybeGrant = grants.stream()
                .filter(grant -> grant.server().equals(invocation.server()) && grant.covers(invocation.tool()))
                .findFirst();
        if (maybeGrant.isEmpty()) {
            return new ApprovalDecision.Refuse("This agent has not been granted " + tool.qualifiedName() + ".");
        }
        ToolGrant grant = maybeGrant.get();

        List<String> missing = grant.missingScopes(tool.requiredScopes());
        if (!missing.isEmpty()) {
            // Named, so the remedy is obvious. "Permission denied" alone sends somebody hunting.
            return new ApprovalDecision.Refuse(
                    "The integration is missing the scope(s) " + String.join(", ", missing) + ".");
        }

        // Checked before the gate, so a person is never asked to approve a call that would only
        // fail afterwards for a missing or mistyped field.
        String problem = validator.validate(tool, validator.normalise(tool, invocation.argumentsJson()));
        if (problem != null) {
            return new ApprovalDecision.Invalid(problem);
        }

        if (exceedsRunLimit(invocation, grant)) {
            return new ApprovalDecision.Refuse("This agent has already used " + tool.qualifiedName()
                    + " the maximum number of times in this run.");
        }

        // The tool's own nature outranks the policy. A workspace can add a gate; it cannot
        // remove the one on sending or deleting.
        if (tool.alwaysRequiresApproval() || grant.requireApproval() || policyRequiresApproval) {
            return new ApprovalDecision.AwaitApproval(describe(adapter, tool, invocation), "approval:decide");
        }
        // Some calls of an otherwise free tool need a person: the adapter says which, per call.
        Optional<String> perCall =
                adapter.approvalReason(invocation.withArguments(validator.normalise(tool, invocation.argumentsJson())));
        if (perCall.isPresent()) {
            return new ApprovalDecision.AwaitApproval(perCall.get(), "approval:decide");
        }

        return ApprovalDecision.PROCEED;
    }

    // @find: run tool call, execute approved call, call connector, timeout indeterminate
    /**
     * Runs a tool that has already been evaluated and, where needed, approved.
     *
     * <p>Takes the decision as an argument rather than re-deriving it, so a call cannot reach a
     * provider by a path that skipped the gate. Passing {@code Proceed} is a deliberate statement
     * by the caller that the checks were made.
     *
     * <p>Flow: a refusing, waiting or invalid decision returns a blocked or failed result at once;
     * otherwise the arguments are normalised and validated again, the per-run counter is
     * incremented, and the adapter is called with the credential under the tool's timeout. A timeout
     * or any error becomes a result through {@code classify}, never an exception. Side effect: this
     * is the one place a provider is contacted for a tool call, and it logs the outcome.
     */
    public Mono<ToolResult> invoke(ToolInvocation invocation, ApprovalDecision decision, String credential) {
        if (decision instanceof ApprovalDecision.Refuse refuse) {
            return Mono.just(ToolResult.blocked(refuse.reason()));
        }
        if (decision instanceof ApprovalDecision.AwaitApproval await) {
            return Mono.just(ToolResult.blocked("Waiting for approval: " + await.reason()));
        }
        if (decision instanceof ApprovalDecision.Invalid invalid) {
            return Mono.just(ToolResult.failed(invalid.problem()));
        }

        McpServerAdapter adapter = adapters.get(invocation.server());
        if (adapter == null) {
            return Mono.just(ToolResult.blocked("No server named " + invocation.server() + " is connected."));
        }
        ToolDefinition tool = adapter.tool(invocation.tool()).orElse(null);
        if (tool == null) {
            return Mono.just(ToolResult.blocked("That tool is no longer offered."));
        }

        // The adapter receives the arguments as the schema describes them, near misses put right.
        ToolInvocation normalised = invocation.withArguments(validator.normalise(tool, invocation.argumentsJson()));
        String problem = validator.validate(tool, normalised.argumentsJson());
        if (problem != null) {
            // Reported back to the model rather than thrown: a model that produced bad arguments
            // can usually correct them if it is told what was wrong.
            return Mono.just(ToolResult.failed(problem));
        }

        countCall(invocation);
        Instant startedAt = Instant.now();

        return adapter.invoke(normalised, credential)
                .timeout(tool.defaultTimeout())
                .onErrorResume(error -> Mono.just(classify(tool, error, startedAt)))
                .doOnNext(result -> log.info(
                        "{} for agent {} in run {}: {}",
                        tool.qualifiedName(),
                        invocation.agentId(),
                        invocation.runId(),
                        result.status()));
    }

    /**
     * Turns a failure into a result, distinguishing "it did not happen" from "we do not know".
     *
     * <p>A timeout on a tool that is not idempotent is the whole reason
     * {@link ToolResult.Status#INDETERMINATE} exists. The email may already be sent; calling it a
     * failure invites a retry that sends a second one.
     */
    private ToolResult classify(ToolDefinition tool, Throwable error, Instant startedAt) {
        Duration took = Duration.between(startedAt, Instant.now());
        boolean timedOut = isTimeout(error);

        if (timedOut && !tool.idempotent()) {
            return ToolResult.indeterminate(
                    "The provider did not confirm the result within "
                            + tool.defaultTimeout().toSeconds()
                            + " seconds. The action was not repeated because it cannot be undone.",
                    took);
        }
        if (timedOut) {
            return new ToolResult(
                    ToolResult.Status.FAILED,
                    "{}",
                    "The provider did not respond in time.",
                    took,
                    Map.of("retryable", "true"));
        }
        // The exception's own name and text are for the log, not for the model or the person reading
        // the trace: "WebClientRequestException" tells neither of them anything they can act on.
        log.warn("{} failed unexpectedly: {}", tool.qualifiedName(), error.toString());
        return new ToolResult(
                ToolResult.Status.FAILED,
                "{}",
                tool.server() + " did not answer as expected. "
                        + (tool.idempotent()
                                ? "Try again shortly."
                                : "Check " + tool.server() + " before trying again, in case it went through."),
                took,
                Map.of());
    }

    /**
     * Whether a failure is a timeout, anywhere in its cause chain.
     *
     * <p>The chain has to be walked rather than the top-level type checked. A timeout reaches
     * here wrapped by the reactive pipeline, by an HTTP client, or by an adapter, and checking
     * only the outermost type misses every one of those.
     *
     * <p>Getting this wrong is not a cosmetic bug. A wrapped timeout on a non-idempotent tool
     * would be reported as an ordinary failure, which invites a retry - and the action may
     * already have happened, so the retry sends a second email.
     */
    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    /**
     * Builds the one-sentence reason shown to the approver, from the tool's side-effect class, plus
     * any extra detail the adapter supplies (for example the page a browser submit acts on). A failing
     * adapter detail is ignored so it cannot prevent the approval request.
     */
    private String describe(McpServerAdapter adapter, ToolDefinition tool, ToolInvocation invocation) {
        String what = switch (tool.sideEffect()) {
            case OUTBOUND -> "Send something outside the workspace using " + tool.qualifiedName();
            case DESTRUCTIVE -> "Permanently remove something using " + tool.qualifiedName();
            case WRITE -> "Change workspace data using " + tool.qualifiedName();
            case READ -> "Read data using " + tool.qualifiedName();
        };
        // What the arguments alone do not show the approver, such as the page a browser submit acts on.
        Optional<String> detail;
        try {
            detail = adapter.approvalDetail(invocation).map(String::strip).filter(text -> !text.isEmpty());
        } catch (RuntimeException e) {
            detail = Optional.empty();
        }
        return detail.map(text -> what + ". " + text).orElse(what);
    }

    /**
     * Whether this run has already used the tool as often as the grant allows. False when the grant
     * sets no ceiling or the invocation carries no run id.
     */
    private boolean exceedsRunLimit(ToolInvocation invocation, ToolGrant grant) {
        if (grant.maxCallsPerRun() == null || invocation.runId() == null) {
            return false;
        }
        AtomicInteger count =
                runCallCounts.getOrDefault(invocation.runId(), Map.of()).get(invocation.qualifiedName());
        return count != null && count.get() >= grant.maxCallsPerRun();
    }

    /** Counts one call against the run, keyed by the qualified tool name. */
    private void countCall(ToolInvocation invocation) {
        if (invocation.runId() == null) {
            return;
        }
        runCallCounts
                .computeIfAbsent(invocation.runId(), id -> new ConcurrentHashMap<>())
                .computeIfAbsent(invocation.qualifiedName(), name -> new AtomicInteger())
                .incrementAndGet();
    }

    // @find: release run, clear per run call counts
    /** Called when a run ends, so the counters do not accumulate for the life of the process. */
    public void releaseRun(String runId) {
        runCallCounts.remove(runId);
        adapters.values().forEach(adapter -> {
            try {
                adapter.releaseRun(runId);
            } catch (RuntimeException e) {
                log.debug("{} could not release run {}: {}", adapter.server(), runId, e.toString());
            }
        });
    }

    // @find: which connectors are connected, server status map
    /**
     * Every registered server name mapped to whether its adapter is a sandbox (practice data) one.
     * Used by the connector listing to show which servers exist.
     */
    public Map<String, Boolean> connectedServers() {
        Map<String, Boolean> servers = new HashMap<>();
        adapters.forEach((name, adapter) -> servers.put(name, adapter.isSandbox()));
        return servers;
    }

    // @find: get adapter for connector, find server adapter
    /** The adapter registered for a server, or empty. */
    public Optional<McpServerAdapter> adapter(String server) {
        return Optional.ofNullable(adapters.get(server));
    }
}
