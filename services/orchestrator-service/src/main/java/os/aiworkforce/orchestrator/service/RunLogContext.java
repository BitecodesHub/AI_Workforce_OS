package os.aiworkforce.orchestrator.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;

/**
 * Puts the identifiers of the work in hand into the logging context while it runs, and puts back
 * what was there when it is done.
 *
 * <p>An agent run executes on a thread of its own, long after the request that started it has
 * answered, so none of its log lines carried a run, a goal, an agent or a workspace: finding what
 * happened to one run meant reading the whole log for the minute it ran in. With these in the
 * context, every line written on the thread - by the runner, the router, a tool - carries them
 * (the plain-text pattern prints run and workspace; the JSON format prints every key).
 *
 * <p>Use it in a try-with-resources around the call. Closing restores the previous values rather
 * than clearing the context, so a sweep that sets a workspace and then a run keeps the workspace
 * after the run, and a thread that was handed a context by its submitter gets it back.
 */
final class RunLogContext implements AutoCloseable {

    static final String RUN_ID = "runId";
    static final String GOAL_ID = "goalId";
    static final String TASK_ID = "taskId";
    static final String ORG_ID = "orgId";
    static final String AGENT_ID = "agentId";
    static final String SWEEP = "sweep";

    /** The value each key held before this scope began; null where it held none. */
    private final Map<String, String> previous = new LinkedHashMap<>();

    private RunLogContext(Map<String, String> entries) {
        entries.forEach(this::with);
    }

    /** The identifiers of one run; any that are not known are left out. */
    static RunLogContext run(UUID orgId, UUID goalId, UUID runId, UUID agentId) {
        return of(Map.of()).with(ORG_ID, orgId).with(GOAL_ID, goalId).with(RUN_ID, runId).with(AGENT_ID, agentId);
    }

    /** One workspace's work, such as a dispatch pass. */
    static RunLogContext workspace(UUID orgId) {
        return of(Map.of()).with(ORG_ID, orgId);
    }

    /** A named sweep, so lines it writes can be told from a request's. */
    static RunLogContext sweep(String name) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(SWEEP, name);
        return of(entries);
    }

    static RunLogContext of(Map<String, String> entries) {
        return new RunLogContext(entries);
    }

    /** Adds one more identifier to this scope, remembered so closing restores it too. */
    RunLogContext with(String key, UUID value) {
        return with(key, value == null ? null : value.toString());
    }

    RunLogContext with(String key, String value) {
        if (value != null) {
            // Only the first change to a key records what was there; later ones would record this
            // scope's own earlier value and restore that instead of the original.
            if (!previous.containsKey(key)) {
                previous.put(key, MDC.get(key));
            }
            MDC.put(key, value);
        }
        return this;
    }

    @Override
    public void close() {
        previous.forEach((key, before) -> {
            if (before == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, before);
            }
        });
        previous.clear();
    }
}
