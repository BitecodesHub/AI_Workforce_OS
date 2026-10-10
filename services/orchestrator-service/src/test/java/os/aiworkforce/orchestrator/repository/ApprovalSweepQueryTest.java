// @find: tests for approval sweep query, repository, starts from parked runs, only the newest approval counts, ApprovalSweepQueryTest, ApprovalSweepQuery
// @what: Tests for ApprovalSweepQuery in the orchestrator repository package (2 test methods).
// @flow: Exercises ApprovalSweepQuery
package os.aiworkforce.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

/**
 * The resume sweep runs every minute on every instance, so what it starts from decides what it
 * costs: a query that began at approvals would rescan every approval ever granted, for ever. This
 * guards its shape on every build; {@code ApprovalQueriesPersistenceTest} runs it against Postgres
 * when Docker is there.
 */
class ApprovalSweepQueryTest {

    private static String sweepQuery() throws NoSuchMethodException {
        return Approvals.class
                .getMethod("findApprovedAwaitingResume", Instant.class, Pageable.class)
                .getAnnotation(Query.class)
                .value()
                .replaceAll("\\s+", " ");
    }

    // @find: test starts from parked runs, approval sweep query
    @Test
    @DisplayName("the sweep starts from the runs that are parked, not from every approval ever granted")
    void startsFromParkedRuns() throws NoSuchMethodException {
        String query = sweepQuery();

        assertThat(query).contains("from Run r join Approval a on a.runId = r.id");
        assertThat(query).contains("r.status = 'waiting_approval'");
    }

    // @find: test only the newest approval counts, approval sweep query
    @Test
    @DisplayName("only a run's newest approval counts, and only once it was approved before the cutoff")
    void onlyTheNewestApprovalCounts() throws NoSuchMethodException {
        String query = sweepQuery();

        assertThat(query).contains("a.status = 'approved'").contains("a.decidedAt < :cutoff");
        assertThat(query).contains("a.requestedAt = (select max(p.requestedAt) from Approval p where p.runId = r.id)");
    }
}
