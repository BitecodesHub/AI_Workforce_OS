// @find: approval decision, proceed, await approval, refuse, invalid arguments, approver permission, approvals, human in the loop, gate outcome, approve or reject tool call
// @what: Sealed type for the gateway verdict on a tool call: proceed, wait for a person, refuse, or invalid arguments.
// @flow: Returned by ToolGateway.evaluate; the orchestrator parks the run on AwaitApproval
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

    /**
     * The arguments do not fit the tool, so there is nothing yet for a person to approve. Not a
     * refusal: the model is told what is wrong and can correct it, and the call never reaches the
     * provider or the approval queue.
     *
     * @param problem what is wrong with the arguments, naming the field
     */
    record Invalid(String problem) implements ApprovalDecision {}

    ApprovalDecision PROCEED = new Proceed();
}
