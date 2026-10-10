// @find: event topics, kafka topics, retry topic, dead letter topic, topic names
// @what: Names every Kafka topic and its retry and dead-letter companions.
// @flow: Used with EventEnvelope
package os.aiworkforce.platform.event;

/**
 * Every topic the platform uses, and how a topic's companions are named.
 *
 * <p>Three topics per subject, not one. The main topic carries work. The retry topic holds a
 * message that failed for a reason that might pass later, and is consumed on a delay so a
 * struggling dependency is not hammered. The dead-letter topic holds what has exhausted its
 * attempts, where it waits for a person rather than being dropped.
 *
 * <p>Dropping a failed message is the alternative, and it is the one that loses an approval or an
 * invoice with no record that anything went missing.
 */
public final class EventTopics {

    public static final String TASK_REQUESTED = "task.requested";
    public static final String TASK_COMPLETED = "task.completed";
    public static final String TASK_FAILED = "task.failed";
    public static final String RUN_EVENT = "run.event";
    public static final String APPROVAL_REQUESTED = "approval.requested";
    public static final String APPROVAL_DECIDED = "approval.decided";
    public static final String AGENT_HANDOFF = "agent.handoff";
    public static final String MEMORY_WRITTEN = "memory.written";
    public static final String DOCUMENT_INGESTED = "document.ingested";
    public static final String TOOL_INVOKED = "tool.invoked";
    public static final String AUDIT_RECORDED = "audit.recorded";
    public static final String USAGE_RECORDED = "usage.recorded";
    public static final String MEMBERSHIP_CHANGED = "membership.changed";
    public static final String SETTINGS_CHANGED = "settings.changed";

    private EventTopics() {}

    public static String main(String prefix, String subject) {
        return prefix + "." + subject;
    }

    public static String retry(String prefix, String subject) {
        return prefix + "." + subject + ".retry";
    }

    public static String deadLetter(String prefix, String subject) {
        return prefix + "." + subject + ".dlt";
    }
}
