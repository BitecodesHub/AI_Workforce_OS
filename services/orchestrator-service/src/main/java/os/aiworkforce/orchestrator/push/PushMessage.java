// @find: push message text, notification title body, push payload, deep link /approvals#approval-, what a push says, no payload body in push, PushMessage
// @what: What one push notification says: a title, a sentence, a tag that replaces earlier ones and a link into the app; never what the agent wanted to send.
// @flow: Built by PushService for approvals, questions and the test button; serialised to JSON and encrypted by PushDelivery
package os.aiworkforce.orchestrator.push;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import os.aiworkforce.mcp.catalog.ToolLabels;
import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.RunQuestion;

/**
 * One notification as the device shows it.
 *
 * <p>Words the platform wrote, from what kind of action it is and which tool it uses, and a link
 * back into the app. Never the tool's arguments - the email's recipient and body, the message, the
 * record to delete - and never the goal's own title or the question's text: those hold customers'
 * and colleagues' names, and a notification is read on a lock screen by whoever is holding the
 * phone. The service worker shows {@code title} and {@code body}, replaces an earlier notification
 * with the same {@code tag}, and opens {@code url} when it is tapped.
 *
 * @param kind {@code approval}, {@code question} or {@code test}
 * @param ttlSeconds how long the push service should keep trying to deliver it
 */
public record PushMessage(String kind, String title, String body, String tag, String url, long ttlSeconds) {

    /** The shortest time a push service is asked to keep a message. */
    static final long MIN_TTL = 60;
    /** The longest: a day, which is also how long an approval waits. */
    static final long MAX_TTL = 86_400;

    // @find: push text for approval
    /** "An agent needs your approval" with the action in plain words, linking to that approval's card. */
    public static PushMessage forApproval(Approval approval, Instant now) {
        return new PushMessage(
                "approval",
                "An agent needs your approval",
                approvalSentence(approval),
                "approval-" + approval.getId(),
                "/approvals#approval-" + approval.getId(),
                ttlUntil(approval.getExpiresAt(), now));
    }

    // @find: push text for question
    /**
     * "An agent has a question for you", linking to the conversation that raised it, or to the run
     * that is waiting when it came from elsewhere.
     */
    public static PushMessage forQuestion(RunQuestion question, Instant now) {
        String url = question.getConversationId() != null
                ? "/chat?c=" + question.getConversationId() + "#question-" + question.getId()
                : "/runs/" + question.getRunId();
        return new PushMessage(
                "question",
                "An agent has a question for you",
                "It is waiting for your answer before it carries on with work you asked for.",
                "question-" + question.getId(),
                url,
                ttlUntil(question.getExpiresAt(), now));
    }

    /** What the Send a test notification button sends. */
    public static PushMessage test() {
        return new PushMessage(
                "test",
                "Push notifications are on",
                "This device will be told when an approval or a question is waiting for you.",
                "aiwos-test",
                "/profile",
                300);
    }

    /**
     * The action in words: "Send an email in Gmail. It sends something outside the workspace. Open
     * it to approve or reject." A removal says that it cannot be undone.
     */
    static String approvalSentence(Approval approval) {
        String tool = approval.getTool();
        String action = "An action";
        if (tool != null && tool.indexOf('.') > 0 && tool.indexOf('.') < tool.length() - 1) {
            int dot = tool.indexOf('.');
            action = ToolLabels.label(tool.substring(0, dot), tool.substring(dot + 1));
        }
        String kind = approval.getActionClass() == null ? "" : approval.getActionClass().toUpperCase(Locale.ROOT);
        String note = switch (kind) {
            case "DESTRUCTIVE" -> " It removes something and cannot be undone.";
            case "OUTBOUND" -> " It sends something outside the workspace.";
            default -> "";
        };
        return action + "." + note + " Open it to approve or reject.";
    }

    // Seconds from now until the deadline, held between a minute and a day.
    private static long ttlUntil(Instant deadline, Instant now) {
        if (deadline == null) {
            return MAX_TTL;
        }
        long seconds = Duration.between(now, deadline).toSeconds();
        return Math.max(MIN_TTL, Math.min(MAX_TTL, seconds));
    }
}
