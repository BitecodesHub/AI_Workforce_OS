package os.aiworkforce.orchestrator.service;

import java.util.UUID;

/** A run and its workspace, as a sweep hands it to {@link RunExecutor#submitResume}. */
public record RunRef(UUID orgId, UUID runId) {}
