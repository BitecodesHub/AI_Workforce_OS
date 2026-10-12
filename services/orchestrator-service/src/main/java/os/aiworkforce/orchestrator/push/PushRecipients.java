// @find: push recipients, who gets a push, who can decide an approval, four-eyes push, approval:decide push, question push recipient, PushRecipients
// @what: Chooses who is told about an approval (people with push on who may decide it, four-eyes rule included) or a question (the person who must answer).
// @flow: Used by PushService on the delivery thread; reads PushSubscriptionStore, MemberPermissions and ApprovalService.canDecide
package os.aiworkforce.orchestrator.push;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.RunQuestion;
import os.aiworkforce.orchestrator.service.ApprovalService;
import os.aiworkforce.platform.context.Actor;

/**
 * Who hears about something waiting.
 *
 * <p>An approval goes to the people with push turned on in its workspace who could decide it right
 * now, by exactly the rule the Approvals page uses ({@link ApprovalService#canDecide}): they hold
 * the permission the approval names, usually {@code approval:decide}, and the workspace's four-eyes
 * rule does not stop them as the person who asked for the work. Only people with a subscription are
 * looked at, so the workspace's full member list is never read. A person whose permissions cannot
 * be checked is left out: telling someone about a request they may not decide is the worse mistake.
 *
 * <p>A question goes to the person it asks: the one who asked for the work.
 */
@Component
public class PushRecipients {

    /** The most people looked at for one approval; far more than any team deciding approvals. */
    static final int MAX_CANDIDATES = 500;

    private final PushSubscriptionStore subscriptions;
    private final MemberPermissions permissions;
    private final ApprovalService approvals;

    /**
     * Wires the subscription store, the permission lookup and the approval rules. The approval
     * service is lazy: it announces raised approvals to listeners, push among them, so a direct
     * reference would make a cycle.
     */
    public PushRecipients(
            PushSubscriptionStore subscriptions, MemberPermissions permissions, @Lazy ApprovalService approvals) {
        this.subscriptions = subscriptions;
        this.permissions = permissions;
        this.approvals = approvals;
    }

    // @find: who may decide this approval, push recipients for approval
    /**
     * The people to tell about {@code approval}: subscribed, active members who could decide it now.
     * Reads the store and asks the permission lookup once per subscribed person (cached there).
     */
    public List<UUID> forApproval(Approval approval) {
        List<UUID> chosen = new ArrayList<>();
        for (UUID userId : subscriptions.subscribedUsers(approval.getOrgId(), MAX_CANDIDATES)) {
            Optional<Set<String>> held = permissions.permissionsOf(approval.getOrgId(), userId);
            if (held.isEmpty() || held.get().isEmpty()) {
                continue;
            }
            Actor person = Actor.user(userId.toString(), approval.getOrgId().toString(), null, held.get(), 0L);
            if (approvals.canDecide(person, approval)) {
                chosen.add(userId);
            }
        }
        return chosen;
    }

    // @find: push recipient for question
    /**
     * The person a question waits on, when there is one and they are still a member. A question
     * from work nobody asked for (a schedule nobody owns) reaches nobody.
     */
    public List<UUID> forQuestion(RunQuestion question) {
        UUID asked = question.getRequestedBy();
        if (asked == null) {
            return List.of();
        }
        Optional<Set<String>> held = permissions.permissionsOf(question.getOrgId(), asked);
        return held.isPresent() && !held.get().isEmpty() ? List.of(asked) : List.of();
    }
}
