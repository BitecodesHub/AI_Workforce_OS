package os.aiworkforce.orchestrator.service;

import java.util.UUID;

/**
 * A task in this workspace just settled - it finished, or went back to wait for another attempt -
 * so another task may now be ready to start, or may now fit under the workspace's cap.
 *
 * <p>Published by {@link TaskProgress} once the change has committed, and heard by {@link
 * RunExecutor}, which dispatches the workspace's next tasks straight away instead of leaving them
 * for the goal sweep's next tick. An event rather than a call, because the executor already depends
 * on the task's progress through the goal service and the runner, and a call back would be a cycle.
 *
 * @param orgId the workspace the task belongs to
 */
public record TaskSettledEvent(UUID orgId) {}
