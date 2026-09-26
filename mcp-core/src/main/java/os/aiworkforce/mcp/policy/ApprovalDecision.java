package os.aiworkforce.mcp.policy;

/**
 * Whether a tool call may proceed now, must wait for a person, or is refused outright.
 *
 * <p>Three outcomes rather than two. "Wait" is not a soft refusal: the run parks, a person
 * decides, and the call continues afterwards with the same arguments. Collapsing it into
 * "refused" would make every gated action look like a failure in the trace.
 */
public sealed interface ApprovalDecision {

    record Proceed() implements ApprovalDecision {}

    /**
     * @param reason what the approver is being asked to approve
     * @param approverPermission the permission an approver must hold
     */
    record AwaitApproval(String reason, String approverPermission) implements ApprovalDecision {}

    /** @param reason why it will not be permitted, whoever asks */
    record Refuse(String reason) implements ApprovalDecision {}

    ApprovalDecision PROCEED = new Proceed();
}
