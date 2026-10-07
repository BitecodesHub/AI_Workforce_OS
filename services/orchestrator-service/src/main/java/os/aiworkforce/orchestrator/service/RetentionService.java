package os.aiworkforce.orchestrator.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import os.aiworkforce.platform.runtimeconfig.ConfigKey;
import os.aiworkforce.platform.runtimeconfig.RuntimeConfigService;

/**
 * How long the platform keeps what agents saw and did, and the nightly purge that enforces it.
 *
 * <p>A run's steps hold the text of what each tool returned to the model - an email's body, an
 * issue's description - and the arguments it was called with. That text is what grows without
 * limit, and what a buyer asks about: how long is it kept, and who can change that? Each workspace
 * answers for itself with {@code retention.runDetailDays}, 180 by default. Once a run has been
 * finished that long, the purge removes that text from its steps. The steps themselves stay, with
 * their summaries, timings and cost, so a run's history still reads as an outline and the spend
 * report still adds up; so do the audit log and the answers on the tasks, which are not traces.
 *
 * <p>Model-usage rows are kept for {@value #USAGE_DAYS} days, for every workspace: they hold no
 * text, only counts and cost, and the spend reports read a year back.
 *
 * <p>Chat messages need no sweep of their own. They belong to their conversation by a foreign
 * key that deletes with it (see {@code V5__chat.sql}), so deleting a conversation has always
 * deleted its messages, and the ratings on them with it.
 *
 * <p>Every instance runs the nightly job; {@link RetentionStore#tryOpen} lets one at a time
 * through, and the others skip the night. The work is done in batches of {@value #BATCH}, each its
 * own statement, so it never holds a long transaction or a long lock on a table agents write to.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /** How many rows one statement changes. Small enough to commit quickly, large enough to finish the night. */
    static final int BATCH = 1_000;

    /**
     * The most batches one statement is repeated in a night: a million rows, which a normal day
     * never reaches. A backlog bigger than that - the first night after retention is switched on -
     * is finished over the next few nights rather than in one long session.
     */
    static final int MAX_BATCHES = 1_000;

    /** How long a model-usage row is kept. */
    public static final int USAGE_DAYS = 400;

    static final Duration USAGE_RETENTION = Duration.ofDays(USAGE_DAYS);

    /** The default, and the answer when the setting cannot be read. */
    public static final int DEFAULT_RUN_DETAIL_DAYS = 180;

    /**
     * How many days a finished run keeps the text of its steps. A week is the least: shorter than
     * that and a failure noticed on Monday is already unreadable on the next Monday.
     */
    static final ConfigKey RUN_DETAIL_DAYS = ConfigKey.integer(
            "retention.runDetailDays",
            ConfigKey.Scope.WORKSPACE,
            DEFAULT_RUN_DETAIL_DAYS,
            7,
            3650,
            "How many days a finished run keeps what its tools returned and were asked. After that only"
                    + " the outline, summaries and cost remain.");

    /** What one night's purge did. {@code ran} is false when another instance already had the night. */
    public record Result(boolean ran, int workspaces, int stepsCleared, int usageRowsDeleted) {

        static Result skipped() {
            return new Result(false, 0, 0, 0);
        }
    }

    private final RetentionStore store;
    private final RuntimeConfigService config;
    private final Clock clock;

    @Autowired
    public RetentionService(RetentionStore store, RuntimeConfigService config) {
        this(store, config, Clock.systemUTC());
    }

    RetentionService(RetentionStore store, RuntimeConfigService config, Clock clock) {
        this.store = store;
        this.config = config;
        this.clock = clock;
        if (config != null) {
            config.register(List.of(RUN_DETAIL_DAYS));
        }
    }

    /** How many days this workspace keeps run detail: its own setting, or the default. */
    public int runDetailDays(UUID orgId) {
        if (config == null) {
            return DEFAULT_RUN_DETAIL_DAYS;
        }
        try {
            int days = config.getInt(RUN_DETAIL_DAYS, orgId.toString());
            return Math.min(max(), Math.max(min(), days));
        } catch (RuntimeException unreadable) {
            log.warn(
                    "Could not read {} for workspace {}; keeping {} days: {}",
                    RUN_DETAIL_DAYS.name(),
                    orgId,
                    DEFAULT_RUN_DETAIL_DAYS,
                    unreadable.getMessage());
            return DEFAULT_RUN_DETAIL_DAYS;
        }
    }

    /**
     * Sets how many days this workspace keeps run detail.
     *
     * @throws os.aiworkforce.platform.error.ApiException a validation error when the number is outside
     *     the allowed range
     */
    public void setRunDetailDays(UUID orgId, int days, String actorId) {
        config.set(RUN_DETAIL_DAYS.name(), orgId.toString(), Integer.toString(days), actorId);
    }

    public static int min() {
        return RUN_DETAIL_DAYS.minimum().intValue();
    }

    public static int max() {
        return RUN_DETAIL_DAYS.maximum().intValue();
    }

    /**
     * Runs one night's purge, unless another instance is already running it.
     *
     * <p>A workspace whose purge fails is logged and left for the next night; it never stops the
     * others, or the usage purge after them.
     */
    public Result purge() {
        Optional<RetentionStore.Session> opened = store.tryOpen();
        if (opened.isEmpty()) {
            log.info("Another instance is already running the retention purge; skipping this one");
            return Result.skipped();
        }
        try (RetentionStore.Session session = opened.get()) {
            Instant now = clock.instant();

            // Anyone with a run old enough for even the shortest allowed setting is a candidate;
            // each then answers for its own number of days.
            List<UUID> workspaces = session.workspacesWithRunsFinishedBefore(now.minus(Duration.ofDays(min())));
            int cleared = 0;
            int purged = 0;
            for (UUID orgId : workspaces) {
                try (RunLogContext ignored = RunLogContext.workspace(orgId)) {
                    Instant cutoff = now.minus(Duration.ofDays(runDetailDays(orgId)));
                    int changed = drain(() -> session.clearRunDetail(orgId, cutoff, BATCH));
                    cleared += changed;
                    purged += changed > 0 ? 1 : 0;
                } catch (RuntimeException e) {
                    log.error("The retention purge failed for workspace {}", orgId, e);
                }
            }

            int usage = 0;
            try {
                usage = drain(() -> session.deleteUsageBefore(now.minus(USAGE_RETENTION), BATCH));
            } catch (RuntimeException e) {
                log.error("The usage purge failed", e);
            }

            log.info(
                    "Retention purge: removed the text of {} run step(s) in {} workspace(s) and {} usage row(s)"
                            + " older than {} days",
                    cleared,
                    purged,
                    usage,
                    USAGE_DAYS);
            return new Result(true, purged, cleared, usage);
        }
    }

    /** Repeats a batch until one comes back short, or the night's allowance is used. */
    private static int drain(IntSupplier batch) {
        int total = 0;
        for (int round = 0; round < MAX_BATCHES; round++) {
            int changed = batch.getAsInt();
            total += changed;
            if (changed < BATCH) {
                return total;
            }
        }
        log.info("A retention statement used its night's allowance of {} batches; the rest waits", MAX_BATCHES);
        return total;
    }
}
