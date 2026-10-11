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

    /**
     * The main topic name for a subject, for example {@code aiwos.task.requested}.
     *
     * <p>Pure string concatenation with no side effects. Callers pass the configured
     * {@code aiwos.events.topic-prefix} and one of the subject constants above.
     *
     * @param prefix the deployment's topic prefix
     * @param subject a subject constant such as {@link #TASK_REQUESTED}
     * @return the full topic name
     */
    public static String main(String prefix, String subject) {
        return prefix + "." + subject;
    }

    /**
     * The retry topic for a subject: the main name with a {@code .retry} suffix.
     *
     * <p>A failed message is republished here and consumed on a delay, so a struggling dependency
     * is not hammered. See {@link #main(String, String)} for the arguments.
     */
    public static String retry(String prefix, String subject) {
        return prefix + "." + subject + ".retry";
    }

    /**
     * The dead-letter topic for a subject: the main name with a {@code .dlt} suffix.
     *
     * <p>A message lands here once its delivery attempts are exhausted, and stays until a person
     * replays or discards it. See {@link #main(String, String)} for the arguments.
     */
    public static String deadLetter(String prefix, String subject) {
        return prefix + "." + subject + ".dlt";
    }
}
