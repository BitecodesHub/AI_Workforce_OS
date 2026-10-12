// @find: push rate limit, too many notifications, per user push limit, notification flood, sliding window limiter, PushRateLimiter
// @what: Counts events per person over a sliding window and refuses those beyond the limit; bounded in memory.
// @flow: Used by PushService for notifications sent to a person and for the test button
package os.aiworkforce.orchestrator.push;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most {@code limit} events per person in any {@code window}.
 *
 * <p>A schedule that raises thirty approvals in a minute should not ring somebody's phone thirty
 * times: the first few say that work is waiting, and the rest are on the Approvals page when they
 * look. Kept in memory on each instance, which is enough for a courtesy limit; the map is bounded,
 * and people whose window has passed are dropped first.
 */
public class PushRateLimiter {

    private static final int MAX_PEOPLE = 10_000;

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Map<UUID, Deque<Instant>> recent = new ConcurrentHashMap<>();

    /** A limiter allowing {@code limit} events per person per {@code window}. */
    public PushRateLimiter(int limit, Duration window, Clock clock) {
        this.limit = Math.max(1, limit);
        this.window = window;
        this.clock = clock;
    }

    // @find: may this person get another push
    /** Whether one more event for {@code userId} is allowed now; when it is, it is counted. Thread-safe. */
    public boolean tryAcquire(UUID userId) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        if (recent.size() >= MAX_PEOPLE) {
            recent.values().removeIf(times -> {
                synchronized (times) {
                    return times.isEmpty() || !times.peekLast().isAfter(cutoff);
                }
            });
            if (recent.size() >= MAX_PEOPLE) {
                recent.clear();
            }
        }
        Deque<Instant> times = recent.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
                times.pollFirst();
            }
            if (times.size() >= limit) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }
}
