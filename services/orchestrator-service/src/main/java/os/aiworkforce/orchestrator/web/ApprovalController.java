package os.aiworkforce.orchestrator.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.service.ApprovalService;
import os.aiworkforce.orchestrator.service.RunExecutor;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * The approvals queue, and the record of what was decided.
 *
 * <p>Approving hands the run back to its agent at once rather than waiting for a scheduler to
 * notice, because the person who just approved something is still watching the screen. The
 * decision is answered as soon as it is recorded: the approved call and the rest of the run carry
 * on in the background, and the run's own page shows them as they happen. Carrying the run on
 * inside this request would hold the approver for as long as the agent took, past the gateway's
 * one-minute limit, and report a failure for an approval that had succeeded.
 */
@RestController
@RequestMapping("/api/approvals")
@Tag(name = "Approvals")
public class ApprovalController {

    /** Approvals one bulk decision may name. */
    static final int MAX_BULK = 200;

    private static final int DEFAULT_PAGE_SIZE = 100;

    private final ApprovalService approvals;
    private final RunExecutor executor;
    private final Runs runs;

    public ApprovalController(ApprovalService approvals, RunExecutor executor, Runs runs) {
        this.approvals = approvals;
        this.executor = executor;
        this.runs = runs;
    }

    /**
     * One approval as a person reads it.
     *
     * @param decidedBy who decided it, or null while it is waiting or when nobody did (an expiry)
     * @param decisionNote the approver's own words, when they gave any
     * @param goalId the goal the work belongs to, or null for a run started directly on an agent
     * @param requestedBy who asked for the work, or null when no person did
     * @param taskInstruction what the task was told to do, cut to 500 characters
     * @param canDecide this caller could decide it now: it is waiting, they hold the permission it
     *     needs, and the workspace's four-eyes rule does not stop them
     */
    public record ApprovalView(
            UUID id,
            UUID runId,
            UUID agentId,
            String tool,
            String actionClass,
            String summary,
            /** The tool call's arguments, exactly as the model produced them, as a JSON document. */
            String payload,
            String status,
            Instant requestedAt,
            Instant expiresAt,
            UUID decidedBy,
            Instant decidedAt,
            String decisionNote,
            UUID goalId,
            String goalTitle,
            UUID requestedBy,
            String taskInstruction,
            boolean canDecide,
            /** Rejected as written, with feedback, while the run carried on. */
            boolean sentBack,
            /** Where the call was going when it was raised: live or sandbox. */
            String mode,
            /** What happened when it was carried out, once it was. */
            String outcome) {}

    public record CountView(int pending, int canDecide) {}

    /** How strictly the person who asked for work is kept from approving it: off, destructive or all. */
    public record SettingsView(String requesterCannotApprove) {}

    public record UpdateSettingsRequest(@NotBlank @Size(max = 20) String requesterCannotApprove) {}

    /**
     * A person's decision: {@code approve}, {@code reject} (which stops the run) or {@code
     * request_changes} (which sends the action back with the note and keeps the run going). A
     * request without {@code mode} is read from {@code approved}, as it always was.
     */
    public record DecisionRequest(
            Boolean approved,
            @Pattern(regexp = "approve|reject|request_changes") String mode,
            @Size(max = 1_000) String note) {

        public DecisionRequest(boolean approved, String note) {
            this(approved, null, note);
        }

        /** The decision, from {@code mode} when it is given and from {@code approved} when it is not. */
        public String effectiveMode() {
            if (mode != null) {
                return mode;
            }
            return Boolean.TRUE.equals(approved) ? "approve" : "reject";
        }
    }

    public record DecisionResult(UUID approvalId, String status, String runStatus) {}

    public record BulkDecisionRequest(
            @NotEmpty @Size(max = MAX_BULK) List<UUID> ids, boolean approved, @Size(max = 1_000) String note) {}

    /**
     * What happened to one approval in a bulk decision.
     *
     * @param result {@code decided}, {@code already_decided}, {@code expired}, {@code forbidden} or
     *     {@code not_found}
     * @param status the approval's status afterwards, when it was found
     * @param message why, for any result but {@code decided}
     */
    public record BulkItemResult(UUID id, String result, String status, String message) {}

    public record BulkDecisionResult(List<BulkItemResult> results) {}

    @GetMapping
    @RequiresPermission(Permission.Codes.APPROVAL_READ)
    @Operation(
            summary = "Approvals: those waiting for a decision by default, or the decided history",
            description =
                    "status is pending (soonest to expire first), decided (newest decision first) or all."
                            + " runId narrows to one run's approvals.")
    public List<ApprovalView> list(
            @RequestParam(defaultValue = "pending") String status,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) UUID runId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        List<Approval> found = approvals.list(orgId(), status, agentId, runId, page, size);
        return views(found);
    }

    @GetMapping("/count")
    @RequiresPermission(Permission.Codes.APPROVAL_READ)
    @Operation(summary = "How many approvals are waiting, and how many of them the caller could decide")
    public CountView count(@RequestParam(required = false) UUID agentId) {
        ApprovalService.Counts counts = approvals.count(orgId(), RequestContext.requireActor(), agentId);
        return new CountView(counts.pending(), counts.canDecide());
    }

    @GetMapping("/settings")
    @RequiresPermission(Permission.Codes.APPROVAL_READ)
    @Operation(summary = "Whether the person who asked for work may approve what its agent then does")
    public SettingsView settings() {
        return new SettingsView(approvals.requesterRuleName(orgId()));
    }

    @PutMapping("/settings")
    @RequiresPermission(Permission.Codes.WORKSPACE_UPDATE)
    @Operation(summary = "Ask for a second pair of eyes: off, destructive actions only, or every action")
    public SettingsView updateSettings(@Valid @RequestBody UpdateSettingsRequest request) {
        return new SettingsView(
                approvals.setRequesterRule(orgId(), request.requesterCannotApprove(), RequestContext.requireActor()));
    }

    @GetMapping("/{approvalId}")
    @RequiresPermission(Permission.Codes.APPROVAL_READ)
    @Operation(summary = "One approval, waiting or decided")
    public ApprovalView get(@PathVariable UUID approvalId) {
        return views(List.of(approvals.get(orgId(), approvalId))).get(0);
    }

    @PostMapping("/{approvalId}/decision")
    @RequiresPermission(Permission.Codes.APPROVAL_DECIDE)
    @Operation(summary = "Approve or reject an action")
    public DecisionResult decide(@PathVariable UUID approvalId, @Valid @RequestBody DecisionRequest request) {
        UUID orgId = orgId();
        // Commits on return, so the resume below reads the decision from the database.
        String mode = request.effectiveMode();
        if ("request_changes".equals(mode)) {
            ApprovalService.SendBack sent = approvals.sendBack(orgId, approvalId, request.note());
            Approval returned = sent.approval();
            if (!sent.ended()) {
                // The agent carries on with the feedback as soon as the claim is made, which is
                // here, not on the next sweep.
                executor.submitResume(orgId, returned.getRunId(), RequestContext.requireActor());
            }
            String after = runs.findById(returned.getRunId()).map(Run::getStatus).orElse("unknown");
            return new DecisionResult(
                    returned.getId(),
                    returned.getStatus(),
                    !sent.ended() && "waiting_approval".equals(after) ? "running" : after);
        }
        boolean approved = "approve".equals(mode);
        Approval approval = approvals.decide(orgId, approvalId, approved, request.note());
        String runStatus =
                runs.findById(approval.getRunId()).map(Run::getStatus).orElse("unknown");

        if (!approved) {
            return new DecisionResult(approval.getId(), approval.getStatus(), runStatus);
        }

        // Handed on here rather than left for a sweep: the approver is still looking at the
        // screen, and a queue that clears only on the next tick feels broken. A run started
        // directly, with no goal behind it, carries on as the approver who let it.
        executor.submitResume(orgId, approval.getRunId(), RequestContext.requireActor());
        // Still parked on the approval just granted means the resume has not claimed it yet; it
        // is about to, so the run is reported as carrying on.
        return new DecisionResult(
                approval.getId(), approval.getStatus(), "waiting_approval".equals(runStatus) ? "running" : runStatus);
    }

    /**
     * Decides several approvals the same way, each in a transaction of its own and with the same
     * checks as a single decision, so one that cannot be decided - already decided by someone
     * else, expired, or not this caller's to decide - never stops the rest. Each approved run is
     * handed to the executor to carry on in the background; none is carried on inside this request.
     */
    @PostMapping("/decisions")
    @RequiresPermission(Permission.Codes.APPROVAL_DECIDE)
    @Operation(summary = "Approve or reject several actions at once, with a result for each")
    public BulkDecisionResult decideMany(@Valid @RequestBody BulkDecisionRequest request) {
        UUID orgId = orgId();
        Actor actor = RequestContext.requireActor();
        List<BulkItemResult> results = new ArrayList<>();
        // A repeated id is decided once, not answered "already decided" the second time.
        for (UUID id : new LinkedHashSet<>(request.ids())) {
            results.add(decideOne(orgId, actor, id, request.approved(), request.note()));
        }
        return new BulkDecisionResult(results);
    }

    private BulkItemResult decideOne(UUID orgId, Actor actor, UUID id, boolean approved, String note) {
        try {
            Approval approval = approvals.decide(orgId, id, approved, note);
            if (approved) {
                executor.submitResume(orgId, approval.getRunId(), actor);
            }
            return new BulkItemResult(id, "decided", approval.getStatus(), null);
        } catch (ApiException refused) {
            return new BulkItemResult(id, resultFor(refused.code()), statusOf(refused), refused.getMessage());
        }
    }

    private static String resultFor(ErrorCode code) {
        return switch (code) {
            case APPROVAL_ALREADY_DECIDED -> "already_decided";
            case APPROVAL_EXPIRED -> "expired";
            case NOT_FOUND -> "not_found";
            // A missing permission and the four-eyes rule read the same to the caller: not theirs to decide.
            default -> "forbidden";
        };
    }

    private static String statusOf(ApiException refused) {
        if (refused.code() == ErrorCode.APPROVAL_EXPIRED) {
            return "expired";
        }
        return refused.details().get("status") instanceof String text ? text : null;
    }

    private List<ApprovalView> views(List<Approval> found) {
        Actor actor = RequestContext.requireActor();
        Map<UUID, ApprovalService.Context> context = approvals.contextFor(found);
        return found.stream()
                .map(approval -> toView(
                        approval,
                        context.getOrDefault(approval.getId(), ApprovalService.Context.NONE),
                        approvals.canDecide(actor, approval)))
                .toList();
    }

    private static ApprovalView toView(Approval approval, ApprovalService.Context context, boolean canDecide) {
        return new ApprovalView(
                approval.getId(),
                approval.getRunId(),
                approval.getAgentId(),
                approval.getTool(),
                approval.getActionClass(),
                approval.getSummary(),
                approval.getPayload(),
                approval.getStatus(),
                approval.getRequestedAt(),
                approval.getExpiresAt(),
                approval.getDecidedBy(),
                approval.getDecidedAt(),
                approval.getDecisionNote(),
                context.goalId(),
                context.goalTitle(),
                context.requestedBy(),
                context.taskInstruction(),
                canDecide,
                approval.isSentBack(),
                approval.getMode(),
                approval.getOutcome());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
