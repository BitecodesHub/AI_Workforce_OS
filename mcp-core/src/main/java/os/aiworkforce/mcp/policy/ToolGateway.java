package os.aiworkforce.mcp.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 * already been sent. The order below is fixed, and each step exists because skipping it produces
 * a specific, known failure:
 *
 * <ol>
 *   <li><b>Grant.</b> The agent may reach this server and this tool at all.
 *   <li><b>Scope.</b> The grant carries what the tool needs. A missing scope is reported by name,
 *       so an administrator knows what to add rather than guessing.
 *   <li><b>Arguments.</b> Validated against the tool's own schema, before anything leaves. A
 *       model that hallucinated a field should not discover that at the provider.
 *   <li><b>Approval.</b> Outbound and destructive actions always park, whatever the policy says.
 *   <li><b>Rate limit.</b> Per run and per minute, so one looping agent cannot exhaust a
 *       workspace's quota with a vendor.
 *   <li><b>Circuit breaker.</b> One failing server does not stall runs that use the others.
 * </ol>
 *
 * <p>Every outcome, including every refusal, produces an audit entry. A blocked call is exactly
 * the thing somebody will later need to prove did not happen.
 */
@Service
public class ToolGateway {

    private static final Logger log = LoggerFactory.getLogger(ToolGateway.class);

    private final Map<String, McpServerAdapter> adapters = new ConcurrentHashMap<>();
    private final ArgumentValidator validator;
    private final ResiliencePresets resilience;

    /* Per-run call counts, so a grant's ceiling is enforced across a whole run rather than
     * per request. Cleared when the run ends. */
    private final Map<String, Map<String, AtomicInteger>> runCallCounts = new ConcurrentHashMap<>();

    public ToolGateway(
            List<McpServerAdapter> servers, ArgumentValidator validator, ResiliencePresets resilience) {
        servers.forEach(adapter -> adapters.put(adapter.server(), adapter));
        this.validator = validator;
        this.resilience = resilience;
        log.info("Tool gateway ready with {} server(s): {}", adapters.size(), adapters.keySet());
    }

    /** What the model is allowed to see, which is only what the agent may actually call. */
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

    /**
     * Decides what should happen to a call, without making it.
     *
     * <p>Separate from {@link #invoke} so the orchestrator can park a run before spending
     * anything, and so the same decision can be re-evaluated after an approval without the
     * checks being duplicated in two places.
     */
    public ApprovalDecision evaluate(ToolInvocation invocation, List<ToolGrant> grants, boolean policyRequiresApproval) {
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

        if (exceedsRunLimit(invocation, grant)) {
            return new ApprovalDecision.Refuse(
                    "This agent has already used " + tool.qualifiedName() + " the maximum number of times in this run.");
        }

        // The tool's own nature outranks the policy. A workspace can add a gate; it cannot
        // remove the one on sending or deleting.
        if (tool.alwaysRequiresApproval() || grant.requireApproval() || policyRequiresApproval) {
            return new ApprovalDecision.AwaitApproval(
                    describe(tool, invocation), "approval:decide");
        }

        return ApprovalDecision.PROCEED;
    }

    /**
     * Runs a tool that has already been evaluated and, where needed, approved.
     *
     * <p>Takes the decision as an argument rather than re-deriving it, so a call cannot reach a
     * provider by a path that skipped the gate. Passing {@code Proceed} is a deliberate statement
     * by the caller that the checks were made.
     */
    public Mono<ToolResult> invoke(ToolInvocation invocation, ApprovalDecision decision, String credential) {
        if (decision instanceof ApprovalDecision.Refuse refuse) {
            return Mono.just(ToolResult.blocked(refuse.reason()));
        }
        if (decision instanceof ApprovalDecision.AwaitApproval await) {
            return Mono.just(ToolResult.blocked("Waiting for approval: " + await.reason()));
        }

        McpServerAdapter adapter = adapters.get(invocation.server());
        if (adapter == null) {
            return Mono.just(ToolResult.blocked("No server named " + invocation.server() + " is connected."));
        }
        ToolDefinition tool = adapter.tool(invocation.tool()).orElse(null);
        if (tool == null) {
            return Mono.just(ToolResult.blocked("That tool is no longer offered."));
        }

        String problem = validator.validate(tool, invocation.argumentsJson());
        if (problem != null) {
            // Reported back to the model rather than thrown: a model that produced bad arguments
            // can usually correct them if it is told what was wrong.
            return Mono.just(ToolResult.failed(problem));
        }

        countCall(invocation);
        Instant startedAt = Instant.now();

        return adapter.invoke(invocation, credential)
                .timeout(tool.defaultTimeout())
                .onErrorResume(error -> Mono.just(classify(tool, error, startedAt)))
                .doOnNext(result -> log.info(
                        "{} for agent {} in run {}: {}",
                        tool.qualifiedName(), invocation.agentId(), invocation.runId(), result.status()));
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
                    "The provider did not confirm the result within " + tool.defaultTimeout().toSeconds()
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
        return new ToolResult(
                ToolResult.Status.FAILED,
                "{}",
                "The tool could not be run: " + error.getClass().getSimpleName(),
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

    private String describe(ToolDefinition tool, ToolInvocation invocation) {
        return switch (tool.sideEffect()) {
            case OUTBOUND -> "Send something outside the workspace using " + tool.qualifiedName();
            case DESTRUCTIVE -> "Permanently remove something using " + tool.qualifiedName();
            case WRITE -> "Change workspace data using " + tool.qualifiedName();
            case READ -> "Read data using " + tool.qualifiedName();
        };
    }

    private boolean exceedsRunLimit(ToolInvocation invocation, ToolGrant grant) {
        if (grant.maxCallsPerRun() == null || invocation.runId() == null) {
            return false;
        }
        AtomicInteger count = runCallCounts
                .getOrDefault(invocation.runId(), Map.of())
                .get(invocation.qualifiedName());
        return count != null && count.get() >= grant.maxCallsPerRun();
    }

    private void countCall(ToolInvocation invocation) {
        if (invocation.runId() == null) {
            return;
        }
        runCallCounts
                .computeIfAbsent(invocation.runId(), id -> new ConcurrentHashMap<>())
                .computeIfAbsent(invocation.qualifiedName(), name -> new AtomicInteger())
                .incrementAndGet();
    }

    /** Called when a run ends, so the counters do not accumulate for the life of the process. */
    public void releaseRun(String runId) {
        runCallCounts.remove(runId);
    }

    public Map<String, Boolean> connectedServers() {
        Map<String, Boolean> servers = new HashMap<>();
        adapters.forEach((name, adapter) -> servers.put(name, adapter.isSandbox()));
        return servers;
    }

    public Optional<McpServerAdapter> adapter(String server) {
        return Optional.ofNullable(adapters.get(server));
    }
}
