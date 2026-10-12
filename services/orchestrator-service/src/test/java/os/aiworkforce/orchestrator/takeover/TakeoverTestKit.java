// @find: tests for takeover, takeover test kit, takeover service with fake clock and sleeper, shared test fixture
// @what: Test helper that builds a TakeoverService with a chosen clock, wait and poll interval, for tests outside its package.
// @flow: Used by AgentRunnerTakeoverTest and TakeoverServiceTest
package os.aiworkforce.orchestrator.takeover;

import java.time.Clock;
import java.time.Duration;

import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.service.AuditClient;

/** Builds a {@link TakeoverService} whose clock, wait and poll interval a test controls. */
public final class TakeoverTestKit {

    /** What the service does instead of sleeping: a test can advance a clock or change state here. */
    @FunctionalInterface
    public interface Wait {
        /** Called for each pause of {@code duration}. */
        void pause(Duration duration) throws InterruptedException;
    }

    private TakeoverTestKit() {}

    /** A service over the given collaborators, reading {@code clock}, pausing with {@code wait} every {@code poll}. */
    public static TakeoverService service(
            RunTakeovers takeovers,
            Runs runs,
            Tasks tasks,
            Goals goals,
            TakeoverBrowser browser,
            AuditClient audit,
            Clock clock,
            Wait wait,
            Duration poll) {
        TakeoverService service = new TakeoverService(takeovers, runs, tasks, goals, browser, audit);
        service.useClock(clock);
        service.useSleeper(wait::pause);
        service.setPollInterval(poll);
        return service;
    }

    /** A clock a test moves forward by hand. */
    public static final class MovableClock extends Clock {
        private java.time.Instant now;

        /** Starts at {@code start}. */
        public MovableClock(java.time.Instant start) {
            this.now = start;
        }

        /** Moves the clock forward. */
        public void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }
}
