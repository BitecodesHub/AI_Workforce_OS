// @find: push recipients, who gets a push, who can decide an approval, four-eyes push, approval:decide push, question push recipient, PushRecipients
// @what: Chooses who is told about an approval (people with push on who may decide it, four-eyes rule included) or a question (the person who must answer).
// @flow: Used by PushService on the delivery thread; reads PushSubscriptionStore page by page, MemberPermissions in batches and ApprovalService.canDecide
package os.aiworkforce.orchestrator.push;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * rule does not stop them as the person who asked for the work. Every subscribed person is looked
 * at, a page of {@value #PAGE} at a time, and each page's permissions are asked for in one call.
 * Only people with a subscription are looked at, so the workspace's full member list is never
 * read. A person whose permissions cannot be checked is left out: telling someone about a request
 * they may not decide is the worse mistake.
 *
 * <p>At most {@value #CONCURRENT_SELECTIONS} choices run at once; a schedule that raises thirty
 * approvals together has the rest wait for the first ones, which fill the permission cache the
 * later ones then read.
 *
 * <p>A question goes to the person it asks: the one who asked for the work.
 */
@Component
public class PushRecipients {

    private static final Logger log = LoggerFactory.getLogger(PushRecipients.class);

    /** How many subscribed people are read, and asked about, at a time. */
    static final int PAGE = 200;
    /** How many choices may run at once. */
    static final int CONCURRENT_SELECTIONS = 2;
    /** How long a choice waits for its turn before it gives up, in seconds. */
    static final long TURN_WAIT_SECONDS = 30;

    private final PushSubscriptionStore subscriptions;
    private final MemberPermissions permissions;
    private final ApprovalService approvals;
    private final Semaphore turns = new Semaphore(CONCURRENT_SELECTIONS, true);

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
     * Reads the store a page at a time and asks the permission lookup once per page (cached
     * there). Waits for a turn first; a choice that cannot get one in time reaches nobody, and
     * says so in the log.
     */
    public List<UUID> forApproval(Approval approval) {
        boolean turn;
        try {
            turn = turns.tryAcquire(TURN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        }
        if (!turn) {
            log.warn("Push for approval {} skipped: other approvals were still being sent", approval.getId());
            return List.of();
        }
        try {
            return choose(approval);
        } finally {
            turns.release();
        }
    }

    // Every subscribed person in the approval's workspace, page by page, who may decide it.
    private List<UUID> choose(Approval approval) {
        List<UUID> chosen = new ArrayList<>();
        UUID after = null;
        while (true) {
            List<UUID> page = subscriptions.subscribedUsersAfter(approval.getOrgId(), after, PAGE);
            if (page.isEmpty()) {
                break;
            }
            Map<UUID, Set<String>> held = permissions.permissionsOf(approval.getOrgId(), page);
            for (UUID userId : page) {
                Set<String> codes = held.get(userId);
                if (codes == null || codes.isEmpty()) {
                    continue;
                }
                Actor person = Actor.user(userId.toString(), approval.getOrgId().toString(), null, codes, 0L);
                if (approvals.canDecide(person, approval)) {
                    chosen.add(userId);
                }
            }
            if (page.size() < PAGE) {
                break;
            }
            after = page.get(page.size() - 1);
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
        Set<String> held = permissions.permissionsOf(question.getOrgId(), List.of(asked)).get(asked);
        return held != null && !held.isEmpty() ? List.of(asked) : List.of();
    }
}
