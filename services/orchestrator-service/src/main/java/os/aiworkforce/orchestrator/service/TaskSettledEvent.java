// @find: task settled event, next task ready, event after commit, dispatch
// @what: Event published after a task settles so RunExecutor can start the next ready tasks.
// @flow: Published by TaskProgress; heard by RunExecutor
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
