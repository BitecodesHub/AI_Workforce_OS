// @find: approvals, human gate, approve, reject, decide approval, pending approvals, approval queue, four-eyes, requester rule, send back, request changes, approval expiry, deadline, who can approve, approval counts, held actions, tool call approval
// @what: Raises approvals for risky agent tool calls and records approve, reject or send-back decisions, enforcing the four-eyes rule and paging the queue.
// @flow: Called by AgentRunner.drive when a call needs a person and by ApprovalController for decisions; resumes the run through RunExecutor after commit
package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.llm.model.ToolCall;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.policy.ApprovalDecision;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Approvals;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.runtimeconfig.ConfigKey;
import os.aiworkforce.platform.runtimeconfig.RuntimeConfigService;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * The human gate.
 *
 * <p>Everything here exists to make one guarantee hold: an action that leaves the workspace or
 * destroys something happened because a named person, holding the right permission, decided it
 * should - and the record of that decision cannot be altered afterwards.
 *
 * <p>Four cases are handled explicitly because each has produced a real incident in systems that
 * did not handle it: an approval decided twice by two people clicking at once; an approver whose
 * permission was removed between the request and the decision; an approval nobody answers; and a
 * run cancelled while an approval for it is still open.
 *
 * <p>A workspace may also ask for four eyes: the setting {@code approvals.requesterCannotApprove}
 * stops the person who asked for the work from approving what its agent then wants to do, for
 * every action or only for the ones that remove something. It is off by default, because a small
 * team deciding its own requests in chat is an ordinary, designed flow.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    /** What a run is told when its approval is rejected without a reason. */
    static final String REJECTED = "An approver rejected the action this run needed.";

    static final String EXPIRED = "The approval this run needed expired before anybody decided.";
    static final String REQUESTER_MUST_NOT_DECIDE = "Someone other than the requester must decide this";

    /** The longest note kept with a decision, and so the longest shown or audited. */
    static final int NOTE_MAX = 1_000;
    /** The longest failure reason a rejection writes onto its run and task. */
    static final int REASON_MAX = 300;
    /** How much of the task's instruction an approval card carries. */
    static final int INSTRUCTION_MAX = 500;
    /** Items a page of approvals may hold, whatever the caller asks for. */
    public static final int MAX_PAGE_SIZE = 200;

    private static final List<String> PENDING = List.of("pending");
    /** What leaves the queue: a decision, or the end of the deadline, or the run being stopped. */
    private static final List<String> DECIDED = List.of("approved", "rejected", "expired", "cancelled");

    private static final List<String> EVERY_STATUS = List.of("pending", "approved", "rejected", "expired", "cancelled");

    /**
     * Whether the person who asked for the work may approve what its agent wants to do: {@code off},
     * only for actions that remove something ({@code destructive}), or never ({@code all}). Runs
     * with no human behind them, such as a schedule nobody owns, are unaffected.
     */
    static final ConfigKey REQUESTER_CANNOT_APPROVE = ConfigKey.choice(
            "approvals.requesterCannotApprove",
            ConfigKey.Scope.WORKSPACE,
            "off",
            List.of("off", "destructive", "all"),
            "Stops the person who asked for the work from approving what its agent then wants to do: off, only"
                    + " for actions that remove something (destructive), or for every action (all).");

    /** How strictly the four-eyes rule applies in a workspace. */
    public enum RequesterRule {
        OFF,
        DESTRUCTIVE,
        ALL;

        static RequesterRule parse(String value) {
            if (value == null) {
                return OFF;
            }
            return switch (value.trim().toLowerCase()) {
                case "all" -> ALL;
                case "destructive" -> DESTRUCTIVE;
                default -> OFF;
            };
        }

        /** Whether this rule stops the requester of an action of {@code actionClass} deciding it. */
        boolean covers(String actionClass) {
            return this == ALL || (this == DESTRUCTIVE && "DESTRUCTIVE".equalsIgnoreCase(actionClass));
        }
    }

    /**
     * What a person needs to place an approval beyond the row itself.
     *
     * @param goalId the goal the work belongs to, or null for a run started directly on an agent
     * @param requestedBy who asked for the work, or null when no person did
     * @param taskInstruction what the task was asked to do, cut to {@value #INSTRUCTION_MAX} characters
     */
    public record Context(UUID goalId, String goalTitle, UUID requestedBy, String taskInstruction) {
        /** For an approval with no goal or task behind it. */
        public static final Context NONE = new Context(null, null, null, null);
    }

    /** How many approvals are waiting, and how many of them the caller could decide. */
    public record Counts(int pending, int canDecide) {}

    private final Approvals approvals;
    private final Runs runs;
    /** Where an approval's task and goal are read. Null in unit tests that do not look at them. */
    private final Tasks tasks;

    private final Goals goals;
    private final ObjectMapper objectMapper;
    private final AuditClient audit;
    private final TaskProgress progress;
    /**
     * For what every ending of a run owes after it commits. Lazy, because the runner raises its
     * approvals here; null in unit tests that do not look at it.
     */
    private final AgentRunner runner;
    /** Tells listeners an approval was raised or expired, once that commits. Null in unit tests. */
    private LifecycleAnnouncer announcer;
    /** Where the four-eyes setting is read. Null in unit tests, which use the default (off). */
    private RuntimeConfigService runtimeConfig;

    /**
     * Without the task and goal, so an approval is raised with no requester. Kept for code that
     * builds the service by hand and never looks at who asked for the work.
     */
    public ApprovalService(
            Approvals approvals,
            Runs runs,
            ObjectMapper objectMapper,
            AuditClient audit,
            TaskProgress progress,
            @Lazy AgentRunner runner) {
        this(approvals, runs, null, null, objectMapper, audit, progress, runner);
    }

    @Autowired
    public ApprovalService(
            Approvals approvals,
            Runs runs,
            Tasks tasks,
            Goals goals,
            ObjectMapper objectMapper,
            AuditClient audit,
            TaskProgress progress,
            @Lazy AgentRunner runner) {
        this.approvals = approvals;
        this.runs = runs;
        this.tasks = tasks;
        this.goals = goals;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.progress = progress;
        this.runner = runner;
    }

    @Autowired(required = false)
    void setAnnouncer(LifecycleAnnouncer announcer) {
        this.announcer = announcer;
    }

    @Autowired(required = false)
    void setRuntimeConfig(RuntimeConfigService runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
        if (runtimeConfig != null) {
            runtimeConfig.register(List.of(REQUESTER_CANNOT_APPROVE));
        }
    }

    // @find: raise approval, create approval, agent wants to call a tool, ask a person to approve, approval card
    /**
     * Raises an approval for a tool call the agent wants to make.
     *
     * <p>Records who asked for the work - the run's task's goal's requester, or the person who
     * started a run directly - so the four-eyes rule has someone to compare the approver with. A
     * run no person is behind, such as a schedule nobody owns, has none.
     *
     * @param actionClass what the call does, from the tool's own definition - {@code OUTBOUND},
     *     {@code DESTRUCTIVE}, {@code WRITE} - so an approver sees a deletion marked as one; {@code
     *     OUTBOUND} when it is not known
     */
    @Transactional
    public Approval raise(
            Run run,
            Agent agent,
            ToolInvocation invocation,
            ApprovalDecision.AwaitApproval await,
            ToolCall call,
            String actionClass) {
        Approval approval = new Approval();
        approval.setId(UuidV7.generate());
        approval.setOrgId(run.getOrgId());
        approval.setRunId(run.getId());
        approval.setTaskId(run.getTaskId());
        approval.setAgentId(agent.getId());
        approval.setTool(invocation.qualifiedName());
        approval.setToolCallId(call.id());
        approval.setActionClass(actionClass == null || actionClass.isBlank() ? "OUTBOUND" : actionClass);
        approval.setSummary(await.reason());
        approval.setPayload(toJsonPayload(invocation.argumentsJson()));
        approval.setRequiredPermission(await.approverPermission());
        approval.setRequestedBy(requesterOf(run));
        approval.setExpiresAt(Instant.now().plus(DEFAULT_WINDOW));
        approvals.save(approval);

        log.info(
                "Approval {} raised for {} by agent {} in run {}",
                approval.getId(),
                invocation.qualifiedName(),
                agent.getName(),
                run.getId());
        if (announcer != null) {
            LifecycleAnnouncer.afterCommit(() -> announcer.approvalRaised(approval));
        }
        return approval;
    }

    // @find: record approval outcome, what happened after approved call ran
    /** Puts what happened when an approved call was carried out on the approval, so it can be shown there. */
    public void recordOutcome(java.util.UUID approvalId, String outcome) {
        approvals.recordOutcome(approvalId, outcome);
    }

    /**
     * Who asked for the work a run is doing: its task's goal's requester, or - for a run started
     * directly on an agent - the person who started it. Null when no person did.
     */
    private UUID requesterOf(Run run) {
        if (run.getTaskId() == null) {
            return parseUuid(run.getCreatedBy());
        }
        if (tasks == null || goals == null) {
            return null;
        }
        Task task = tasks.findById(run.getTaskId()).orElse(null);
        if (task == null || task.getGoalId() == null) {
            return null;
        }
        return goals.findById(task.getGoalId()).map(Goal::getRequestedBy).orElse(null);
    }

    // @find: list pending approvals, approvals waiting, approval inbox
    @Transactional(readOnly = true)
    public List<Approval> pending(UUID orgId) {
        return approvals.findPending(orgId);
    }

    // @find: list approvals, approval history, filter by status agent run, approvals page
    /**
     * One page of a workspace's approvals, in the order a person reading them wants: the queue
     * soonest-expiring first, the history newest decision first.
     *
     * @param status {@code pending}, {@code decided} (decided, expired or withdrawn), {@code all},
     *     or one of {@code approved}, {@code rejected}, {@code expired}, {@code cancelled}; blank
     *     means pending
     * @param agentId only this agent's approvals, when given
     * @param runId only this run's approvals, when given; it wins over {@code agentId}
     */
    @Transactional(readOnly = true)
    public List<Approval> list(UUID orgId, String status, UUID agentId, UUID runId, int page, int size) {
        String wanted = status == null || status.isBlank() ? "pending" : status.trim().toLowerCase();
        List<String> statuses = statusesFor(wanted);
        Sort sort =
                switch (wanted) {
                    case "pending" -> Sort.by(
                            Sort.Order.asc("expiresAt"), Sort.Order.asc("requestedAt"), Sort.Order.asc("id"));
                    case "all" -> Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id"));
                    default -> Sort.by(Sort.Order.desc("decidedAt"), Sort.Order.desc("id"));
                };
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE), sort);
        if (runId != null) {
            return approvals.findByOrgIdAndStatusInAndRunId(orgId, statuses, runId, pageable);
        }
        if (agentId != null) {
            return approvals.findByOrgIdAndStatusInAndAgentId(orgId, statuses, agentId, pageable);
        }
        return approvals.findByOrgIdAndStatusIn(orgId, statuses, pageable);
    }

    private static List<String> statusesFor(String status) {
        return switch (status) {
            case "pending" -> PENDING;
            case "decided" -> DECIDED;
            case "all" -> EVERY_STATUS;
            default -> {
                if (DECIDED.contains(status)) {
                    yield List.of(status);
                }
                throw ApiException.validation("status", "must be pending, decided or all");
            }
        };
    }

    // @find: approval counts, pending badge, how many can I decide
    /**
     * How many approvals are waiting, and how many of them {@code actor} could decide - counted in
     * the database, so a badge costs no payloads.
     */
    @Transactional(readOnly = true)
    public Counts count(UUID orgId, Actor actor, UUID agentId) {
        List<Object[]> rows = agentId == null
                ? approvals.countPendingByDecider(orgId)
                : approvals.countPendingByDeciderForAgent(orgId, agentId);
        RequesterRule rule = requesterRule(orgId);
        int pending = 0;
        int decidable = 0;
        for (Object[] row : rows) {
            int n = ((Number) row[3]).intValue();
            pending += n;
            if (actor.hasPermission((String) row[0]) && !stoppedByRule(rule, (UUID) row[1], (String) row[2], actor)) {
                decidable += n;
            }
        }
        return new Counts(pending, decidable);
    }

    // @find: get one approval by id
    /** One approval, for a caller that knows only its id. */
    @Transactional(readOnly = true)
    public Approval get(UUID orgId, UUID approvalId) {
        return approvals
                .findByIdAndOrgId(approvalId, orgId)
                .orElseThrow(() -> ApiException.notFound("approval", approvalId));
    }

    /** One approval, for a caller that already knows its workspace - the resumed run, for one. */
    @Transactional(readOnly = true)
    public Optional<Approval> find(UUID orgId, UUID approvalId) {
        return approvals.findByIdAndOrgId(approvalId, orgId);
    }

    // @find: approval context, goal title, who asked, task instruction on approval card
    /**
     * What each approval is for - its goal, who asked, and what the task was told to do - with a
     * handful of lookups however many approvals there are, never one per approval.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Context> contextFor(Collection<Approval> list) {
        Map<UUID, Context> result = new LinkedHashMap<>();
        if (list.isEmpty()) {
            return result;
        }
        Map<UUID, Task> taskById = new HashMap<>();
        Map<UUID, Goal> goalById = new HashMap<>();
        Map<UUID, Run> runById = new HashMap<>();
        if (tasks != null && goals != null) {
            Set<UUID> taskIds = new LinkedHashSet<>();
            for (Approval approval : list) {
                if (approval.getTaskId() != null) {
                    taskIds.add(approval.getTaskId());
                }
            }
            if (!taskIds.isEmpty()) {
                for (Task task : tasks.findAllById(taskIds)) {
                    taskById.put(task.getId(), task);
                }
            }
            Set<UUID> goalIds = new LinkedHashSet<>();
            for (Task task : taskById.values()) {
                if (task.getGoalId() != null) {
                    goalIds.add(task.getGoalId());
                }
            }
            if (!goalIds.isEmpty()) {
                for (Goal goal : goals.findAllById(goalIds)) {
                    goalById.put(goal.getId(), goal);
                }
            }
        }
        Set<UUID> directRunIds = new LinkedHashSet<>();
        for (Approval approval : list) {
            if (approval.getTaskId() == null && approval.getRequestedBy() == null) {
                directRunIds.add(approval.getRunId());
            }
        }
        if (!directRunIds.isEmpty()) {
            for (Run run : runs.findAllById(directRunIds)) {
                runById.put(run.getId(), run);
            }
        }

        for (Approval approval : list) {
            Task task = approval.getTaskId() == null ? null : taskById.get(approval.getTaskId());
            Goal goal = task == null || task.getGoalId() == null ? null : goalById.get(task.getGoalId());
            if (task != null && !approval.getOrgId().equals(task.getOrgId())) {
                task = null;
                goal = null;
            }
            if (goal != null && !approval.getOrgId().equals(goal.getOrgId())) {
                goal = null;
            }
            UUID requestedBy = approval.getRequestedBy();
            if (requestedBy == null && goal != null) {
                requestedBy = goal.getRequestedBy();
            }
            if (requestedBy == null && approval.getTaskId() == null) {
                Run run = runById.get(approval.getRunId());
                requestedBy = run == null ? null : parseUuid(run.getCreatedBy());
            }
            result.put(
                    approval.getId(),
                    new Context(
                            goal == null ? null : goal.getId(),
                            goal == null ? null : goal.getTitle(),
                            requestedBy,
                            task == null ? null : truncate(task.getInstruction(), INSTRUCTION_MAX)));
        }
        return result;
    }

    // @find: can this person decide, approval permission check, four-eyes check
    /**
     * Whether {@code actor} may decide {@code approval} now: it is still waiting, they hold the
     * permission it needs, and the workspace's four-eyes rule does not stop them as its requester.
     */
    public boolean canDecide(Actor actor, Approval approval) {
        if (actor == null || !approval.isPending() || !actor.hasPermission(approval.getRequiredPermission())) {
            return false;
        }
        return !stoppedByRule(
                requesterRule(approval.getOrgId()), approval.getRequestedBy(), approval.getActionClass(), actor);
    }

    private static boolean stoppedByRule(RequesterRule rule, UUID requestedBy, String actionClass, Actor actor) {
        return rule != RequesterRule.OFF
                && requestedBy != null
                && requestedBy.toString().equals(actor.id())
                && rule.covers(actionClass);
    }

    /**
     * The workspace's four-eyes rule. A setting that cannot be read is treated as the strictest,
     * not the loosest: a safety control must not quietly switch itself off, and the only people it
     * then stops are requesters, who are told plainly who must decide instead.
     */
    private RequesterRule requesterRule(UUID orgId) {
        if (runtimeConfig == null) {
            return RequesterRule.OFF;
        }
        try {
            return RequesterRule.parse(runtimeConfig.getString(REQUESTER_CANNOT_APPROVE, orgId.toString()));
        } catch (RuntimeException unreadable) {
            log.warn(
                    "Could not read {}; treating it as all: {}",
                    REQUESTER_CANNOT_APPROVE.name(),
                    unreadable.getMessage());
            return RequesterRule.ALL;
        }
    }

    /** The workspace's four-eyes rule as a setting reads: {@code off}, {@code destructive} or {@code all}. */
    public String requesterRuleName(UUID orgId) {
        return requesterRule(orgId).name().toLowerCase();
    }

    // @find: set four-eyes rule, requester cannot approve own action, off destructive all setting
    /**
     * Sets the workspace's four-eyes rule. It applies from the next decision: an approval already
     * waiting is judged by the rule in force when somebody decides it.
     *
     * @param value {@code off}, {@code destructive} or {@code all}
     */
    public String setRequesterRule(UUID orgId, String value, Actor actor) {
        String chosen = value == null ? "" : value.strip().toLowerCase();
        if (!REQUESTER_CANNOT_APPROVE.allowedValues().contains(chosen)) {
            throw ApiException.validation("requesterCannotApprove", "must be off, destructive or all");
        }
        if (runtimeConfig == null) {
            throw new ApiException(ErrorCode.DEPENDENCY_DEGRADED, "Settings are not available right now.");
        }
        String previous = requesterRuleName(orgId);
        runtimeConfig.set(REQUESTER_CANNOT_APPROVE.name(), orgId.toString(), chosen, actor.id());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requesterCannotApprove", chosen);
        detail.put("previous", previous);
        audit.record(orgId, actor, "approval.settings_update", "workspace", orgId.toString(), "succeeded", detail);
        return chosen;
    }

    // @find: approve or reject approval, decide approval, record decision, approval note, reject reason
    /**
     * Records a decision.
     *
     * <p>The permission is re-checked here, not only when the queue was rendered. A person can be
     * demoted between opening the page and clicking the button, and the check that matters is the
     * one made at the moment the action is authorised.
     *
     * <p>A refusal does not roll back what was written before it. The only write before a refusal
     * is marking an overdue approval expired, and that must stick: it is the truth whether or not
     * this caller's decision could be accepted.
     *
     * <p>The decision is audited once it commits, with the approver's note, who the agent was and
     * how long the request waited, so a decision that rolled back is never on record as made.
     *
     * @param note why, in the approver's words; trimmed, kept to {@value #NOTE_MAX} characters, and
     *     on a rejection carried into the run's failure reason
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Approval decide(UUID orgId, UUID approvalId, boolean approved, String note) {
        Actor actor = RequestContext.requireActor();
        // Locked until this transaction ends: two approvers clicking at once are taken in turn.
        Approval approval = approvals
                .lockByIdAndOrgId(approvalId, orgId)
                .orElseThrow(() -> ApiException.notFound("approval", approvalId));

        if (!actor.hasPermission(approval.getRequiredPermission())) {
            throw ApiException.permissionDenied(approval.getRequiredPermission());
        }
        if (!approval.isPending()) {
            // Somebody decided it a moment ago. The same answer from a second person is not a
            // failure - the work is exactly where they wanted it - but the opposite answer is.
            String wanted = approved ? "approved" : "rejected";
            if (wanted.equals(approval.getStatus()) && !approval.isSentBack()) {
                return approval;
            }
            throw new ApiException(ErrorCode.APPROVAL_ALREADY_DECIDED).with("status", approval.getStatus());
        }
        if (approval.hasExpired()) {
            expire(approval);
            throw new ApiException(ErrorCode.APPROVAL_EXPIRED);
        }
        if (stoppedByRule(requesterRule(orgId), approval.getRequestedBy(), approval.getActionClass(), actor)) {
            throw new ApiException(ErrorCode.POLICY_VIOLATION, REQUESTER_MUST_NOT_DECIDE);
        }

        String cleanNote = cleanNote(note);
        approval.decide(approved ? "approved" : "rejected", UUID.fromString(actor.id()), cleanNote);
        approvals.save(approval);

        if (!approved) {
            // A rejection ends the run rather than letting the agent look for another way to do
            // the same thing.
            runs.findById(approval.getRunId())
                    .filter(Run::isActive)
                    .ifPresent(run -> endRun(run, rejectionReason(cleanNote)));
        }

        log.info("Approval {} {} by {}", approval.getId(), approved ? "approved" : "rejected", actor.id());

        // The run is named so an investigator can go from the decision straight to its trace.
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("approved", approved);
        detail.put("runId", approval.getRunId().toString());
        if (approval.getTool() != null) {
            detail.put("tool", approval.getTool());
        }
        detail.put("agentId", approval.getAgentId().toString());
        if (approval.getTaskId() != null) {
            detail.put("taskId", approval.getTaskId().toString());
        }
        detail.put("waitedSeconds", waitedSeconds(approval));
        if (cleanNote != null) {
            detail.put("note", cleanNote);
        }
        String resourceId = approval.getId().toString();
        LifecycleAnnouncer.afterCommit(
                () -> audit.record(orgId, actor, "approval.decide", "approval", resourceId, "succeeded", detail));

        return approval;
    }

    /** The most rounds of requested changes one run is given before it stops. */
    public static final int MAX_CHANGE_ROUNDS = 2;

    /** What the run says when it has been sent back too often. */
    public static final String STOPPED_AFTER_ROUNDS = "Stopped after two rounds of requested changes.";

    /**
     * What sending an approval back came to.
     *
     * @param ended whether the run was stopped because it has already been sent back as often as it
     *     may be, in which case nothing should resume it
     */
    public record SendBack(Approval approval, boolean ended) {}

    /**
     * Rejects the action as written but keeps the run going, so the agent can revise it with the
     * approver's feedback.
     *
     * <p>The same checks as {@link #decide} apply - permission, still pending, not expired, the
     * four-eyes rule - and a note is required, because feedback with no content leaves the agent
     * nothing to revise. A run that has been sent back about this same tool before, or twice in
     * all, ends instead with {@link #STOPPED_AFTER_ROUNDS}: an agent that cannot get it right in
     * two attempts needs a person, not a third.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public SendBack sendBack(UUID orgId, UUID approvalId, String note) {
        Actor actor = RequestContext.requireActor();
        String clean = cleanNote(note);
        if (clean == null) {
            throw ApiException.validation("note", "Say what should change, so the agent has something to revise.");
        }
        Approval approval = approvals
                .lockByIdAndOrgId(approvalId, orgId)
                .orElseThrow(() -> ApiException.notFound("approval", approvalId));
        if (!actor.hasPermission(approval.getRequiredPermission())) {
            throw ApiException.permissionDenied(approval.getRequiredPermission());
        }
        if (!approval.isPending()) {
            throw new ApiException(ErrorCode.APPROVAL_ALREADY_DECIDED).with("status", approval.getStatus());
        }
        if (approval.hasExpired()) {
            expire(approval);
            throw new ApiException(ErrorCode.APPROVAL_EXPIRED);
        }
        if (stoppedByRule(requesterRule(orgId), approval.getRequestedBy(), approval.getActionClass(), actor)) {
            throw new ApiException(ErrorCode.POLICY_VIOLATION, REQUESTER_MUST_NOT_DECIDE);
        }

        List<Approval> earlier = approvals.findByRunIdAndSentBackTrueOrderByRequestedAtAsc(approval.getRunId());
        boolean sameToolBefore =
                earlier.stream().anyMatch(prior -> java.util.Objects.equals(prior.getTool(), approval.getTool()));
        boolean ends = sameToolBefore || earlier.size() >= MAX_CHANGE_ROUNDS;

        approval.decide("rejected", UUID.fromString(actor.id()), clean);
        approval.setSentBack(true);
        approvals.save(approval);
        if (ends) {
            runs.findById(approval.getRunId())
                    .filter(Run::isActive)
                    .ifPresent(run -> endRun(run, STOPPED_AFTER_ROUNDS));
        }

        log.info("Approval {} sent back by {} (run ends: {})", approval.getId(), actor.id(), ends);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runId", approval.getRunId().toString());
        if (approval.getTool() != null) {
            detail.put("tool", approval.getTool());
        }
        detail.put("agentId", approval.getAgentId().toString());
        detail.put("note", clean);
        detail.put("round", earlier.size() + 1);
        detail.put("runEnded", ends);
        detail.put("waitedSeconds", waitedSeconds(approval));
        String resourceId = approval.getId().toString();
        LifecycleAnnouncer.afterCommit(
                () -> audit.record(orgId, actor, "approval.request_changes", "approval", resourceId, "succeeded", detail));
        return new SendBack(approval, ends);
    }


    /** The note trimmed and cut to length, or null when there is nothing in it. */
    static String cleanNote(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > NOTE_MAX ? trimmed.substring(0, NOTE_MAX) : trimmed;
    }

    /**
     * What a rejected run is told: the reason on one line when there is one, cut to {@value
     * #REASON_MAX} characters.
     */
    static String rejectionReason(String note) {
        if (note == null) {
            return REJECTED;
        }
        String reason = REJECTED.substring(0, REJECTED.length() - 1) + ": " + note.replaceAll("\\s+", " ");
        return truncate(reason, REASON_MAX);
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "…";
    }

    private static long waitedSeconds(Approval approval) {
        Instant end = approval.getDecidedAt() == null ? Instant.now() : approval.getDecidedAt();
        return Math.max(0, Duration.between(approval.getRequestedAt(), end).toSeconds());
    }

    /**
     * Closes approvals nobody answered.
     *
     * <p>The default is to reject. An action that nobody approved must not happen because
     * everybody was busy, and a pending approval that silently becomes permission after a day is
     * not a gate at all.
     */
    @Transactional
    public int expireOverdue(int limit) {
        List<Approval> overdue = approvals.findExpired(Instant.now(), PageRequest.of(0, limit));
        for (Approval approval : overdue) {
            expire(approval);
        }
        if (!overdue.isEmpty()) {
            log.info("Expired {} approval(s) that passed their deadline", overdue.size());
        }
        return overdue.size();
    }

    /**
     * Closes one approval as expired, stops its run, and - once that commits - records the expiry
     * as the platform's own act and tells the listeners, so work that died waiting is neither
     * silent in the audit log nor unseen by the people who wanted to hear.
     */
    private void expire(Approval approval) {
        approval.setStatus("expired");
        approval.setDecidedAt(Instant.now());
        approvals.save(approval);
        runs.findById(approval.getRunId()).filter(Run::isActive).ifPresent(run -> endRun(run, EXPIRED));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runId", approval.getRunId().toString());
        if (approval.getTool() != null) {
            detail.put("tool", approval.getTool());
        }
        detail.put("agentId", approval.getAgentId().toString());
        detail.put("waitedSeconds", waitedSeconds(approval));
        detail.put("outcome", "denied");
        UUID orgId = approval.getOrgId();
        String resourceId = approval.getId().toString();
        LifecycleAnnouncer.afterCommit(
                () -> audit.record(orgId, Actor.SYSTEM, "approval.expire", "approval", resourceId, "denied", detail));
        if (announcer != null) {
            LifecycleAnnouncer.afterCommit(() -> announcer.approvalExpired(approval));
        }
    }

    /**
     * Ends a run its approval will not let continue, and its task with it. The tool budget and the
     * run's audit entry follow once this commits, the same as for every other way a run ends.
     */
    private void endRun(Run run, String reason) {
        run.finish("cancelled", reason);
        runs.save(run);
        progress.onRunFinished(run, "cancelled", null, reason);
        if (runner != null) {
            LifecycleAnnouncer.afterCommit(() -> runner.finishTerminal(run, "cancelled", reason));
        }
    }

    /** Cancels open approvals for a run that has been stopped, so the queue stays truthful. */
    @Transactional
    public void cancelForRun(UUID runId) {
        withdrawForRun(runId);
    }

    /**
     * Withdraws a stopped run's pending approvals, and says how many there were.
     *
     * <p>A conditional bulk update rather than loading and saving each row: an approver deciding
     * at the same moment then simply wins or loses, and can never make the stop itself fail with
     * a version conflict.
     */
    @Transactional
    public int withdrawForRun(UUID runId) {
        return approvals.withdrawPending(runId, Instant.now());
    }

    /**
     * Runs an approver approved that are still parked, for the resume sweep: the approver's own
     * resume did not happen, most often because the service restarted in between. Only a run's
     * newest approval counts, so a run parked again on a newer one is never resumed past it.
     */
    @Transactional(readOnly = true)
    public List<RunRef> approvedAwaitingResume(Instant cutoff, int limit) {
        return approvals.findApprovedAwaitingResume(cutoff, PageRequest.of(0, limit)).stream()
                .map(approval -> new RunRef(approval.getOrgId(), approval.getRunId()))
                .distinct()
                .toList();
    }

    /**
     * The payload column is JSONB, but the model's arguments are kept as raw text upstream
     * because models produce malformed JSON often enough that it is an ordinary case (see
     * {@link os.aiworkforce.llm.model.ToolCall}). Valid text is stored as-is; anything else is
     * wrapped so the column always holds a document rather than failing the approval.
     */
    private String toJsonPayload(String argumentsJson) {
        String value = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        try {
            objectMapper.readTree(value);
            return value;
        } catch (Exception malformed) {
            return objectMapper.createObjectNode().put("raw", value).toString();
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAnIdentity) {
            return null;
        }
    }
}
