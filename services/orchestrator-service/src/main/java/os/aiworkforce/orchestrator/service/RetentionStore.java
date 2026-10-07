package os.aiworkforce.orchestrator.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The deletes behind {@link RetentionService}, kept apart from its rules so the rules - which
 * workspace keeps what for how long, how big a batch is, what happens when another instance is
 * already at it - can be tested without a database, and the SQL can be tested against one.
 *
 * <p>Every instance of the service runs the nightly job, so the store hands out at most one
 * {@link Session} at a time across all of them.
 */
public interface RetentionStore {

    /**
     * Takes the cluster-wide right to purge, or answers empty when another instance holds it.
     * The right is held until the returned session is closed.
     */
    Optional<Session> tryOpen();

    /** One instance's turn at the purge. Closing it gives the turn up, however the work went. */
    interface Session extends AutoCloseable {

        /** The workspaces that have a run that finished before {@code cutoff}. */
        List<UUID> workspacesWithRunsFinishedBefore(Instant cutoff);

        /**
         * Removes the bulky text from the steps of one workspace's runs that finished before
         * {@code finishedBefore}: what each tool returned to the model and the arguments it was
         * called with. The step rows, their summaries, statuses, timings and cost stay.
         *
         * @return how many steps were changed, at most {@code batch}
         */
        int clearRunDetail(UUID orgId, Instant finishedBefore, int batch);

        /** Deletes model-usage rows recorded before {@code cutoff}; at most {@code batch} of them. */
        int deleteUsageBefore(Instant cutoff, int batch);

        @Override
        void close();
    }
}
