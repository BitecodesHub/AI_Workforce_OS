// @find: run reference, run id and org id pair, record
// @what: Tiny record naming a run and its workspace.
// @flow: Returned by QuestionService sweeps
package os.aiworkforce.orchestrator.service;

import java.util.UUID;

/** A run and its workspace, as a sweep hands it to {@link RunExecutor#submitResume}. */
public record RunRef(UUID orgId, UUID runId) {}
