package os.aiworkforce.orchestrator.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Postgres behind {@link RetentionStore}.
 *
 * <p>One connection is taken for the whole purge and the cluster-wide right to run is a session
 * advisory lock on it: {@code pg_try_advisory_lock} answers at once, so an instance that finds
 * the lock taken simply skips the night rather than queueing behind it. A session lock belongs to
 * the connection, not to a transaction, which is why the connection is kept rather than borrowed
 * per statement; each statement commits on its own, so a batch is never held open while the next
 * is prepared.
 */
@Component
public class JdbcRetentionStore implements RetentionStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcRetentionStore.class);

    /** "AIWOSRET" in ASCII: names the job, and no other part of the platform takes an advisory lock. */
    static final long ADVISORY_LOCK = 0x4149574F53524554L;

    /** A run in any of these has ended, and nothing will read its steps back into a conversation. */
    private static final String FINISHED = "('completed', 'failed', 'cancelled', 'abandoned')";

    private static final String WORKSPACES =
            "select distinct org_id from runs where completed_at < ? and status in " + FINISHED;

    /**
     * Rewrites a step's JSON without the bulky keys, for one batch of finished runs' steps. A step
     * that has nothing left to remove no longer matches, so a night that has nothing to do reads
     * the old steps once and changes none.
     *
     * <p>{@code modelContent} is what a tool returned to the model and {@code arguments} what it was
     * called with; the model's own calls keep theirs as {@code argumentsJson} inside {@code
     * toolCallRecords}, which is cleared the same way. Everything else - kind, tool, status,
     * summary, timings - stays, and the step records when its text was removed.
     */
    static final String CLEAR_RUN_DETAIL =
            """
            update run_steps s
               set detail = scrubbed.detail
              from (
                    select rs.id,
                           (case
                                when jsonb_typeof(rs.detail -> 'toolCallRecords') = 'array' then
                                    jsonb_set(
                                        rs.detail - 'modelContent' - 'arguments',
                                        '{toolCallRecords}',
                                        coalesce(
                                            (select jsonb_agg(
                                                        case when jsonb_typeof(call.value) = 'object'
                                                             then call.value - 'argumentsJson'
                                                             else call.value end
                                                        order by call.position)
                                               from jsonb_array_elements(rs.detail -> 'toolCallRecords')
                                                        with ordinality as call(value, position)),
                                            '[]'::jsonb))
                                else rs.detail - 'modelContent' - 'arguments'
                            end)
                           || jsonb_build_object(
                                  'detailPurgedAt',
                                  to_char(now() at time zone 'utc', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'))
                           as detail
                      from run_steps rs
                      join runs r on r.id = rs.run_id
                     where r.org_id = ?
                       and r.completed_at < ?
                       and r.status in ('completed', 'failed', 'cancelled', 'abandoned')
                       and (jsonb_exists_any(rs.detail, array['modelContent', 'arguments'])
                            or jsonb_path_exists(rs.detail, '$.toolCallRecords[*].argumentsJson'))
                     limit ?
                   ) scrubbed
             where s.id = scrubbed.id
            """;

    static final String DELETE_USAGE =
            "delete from llm_usage where id in (select id from llm_usage where occurred_at < ? limit ?)";

    private final DataSource dataSource;

    public JdbcRetentionStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<Session> tryOpen() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
            if (!tryLock(connection)) {
                connection.close();
                return Optional.empty();
            }
            return Optional.of(new JdbcSession(connection));
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException("The retention purge could not take its lock: " + e.getMessage(), e);
        }
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, ADVISORY_LOCK);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Could not close the retention connection: {}", e.getMessage());
        }
    }

    private static final class JdbcSession implements Session {

        private final Connection connection;

        private JdbcSession(Connection connection) {
            this.connection = connection;
        }

        @Override
        public List<UUID> workspacesWithRunsFinishedBefore(Instant cutoff) {
            try (PreparedStatement statement = connection.prepareStatement(WORKSPACES)) {
                statement.setTimestamp(1, Timestamp.from(cutoff));
                List<UUID> found = new ArrayList<>();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        found.add(result.getObject(1, UUID.class));
                    }
                }
                return found;
            } catch (SQLException e) {
                throw new IllegalStateException("Could not list workspaces for the retention purge", e);
            }
        }

        @Override
        public int clearRunDetail(UUID orgId, Instant finishedBefore, int batch) {
            try (PreparedStatement statement = connection.prepareStatement(CLEAR_RUN_DETAIL)) {
                statement.setObject(1, orgId);
                statement.setTimestamp(2, Timestamp.from(finishedBefore));
                statement.setInt(3, batch);
                return statement.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Could not clear run detail for workspace " + orgId, e);
            }
        }

        @Override
        public int deleteUsageBefore(Instant cutoff, int batch) {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_USAGE)) {
                statement.setTimestamp(1, Timestamp.from(cutoff));
                statement.setInt(2, batch);
                return statement.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Could not delete old usage rows", e);
            }
        }

        /**
         * Gives the lock up, and the connection back. A lock that could not be released would stay
         * on a pooled connection and silence the job on every instance, so a connection that will
         * not unlock is aborted, which ends the database session and the lock with it.
         */
        @Override
        public void close() {
            try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                statement.setLong(1, ADVISORY_LOCK);
                statement.execute();
                connection.close();
            } catch (SQLException e) {
                log.warn("The retention lock could not be released; ending its database session: {}", e.getMessage());
                try {
                    connection.abort(Runnable::run);
                } catch (SQLException | RuntimeException abortFailed) {
                    log.warn("Could not end the retention session either: {}", abortFailed.getMessage());
                }
            }
        }
    }
}
