// @find: tests for team room store sql, private rule on every feed branch, bound parameters do not grow with hidden conversations, bind parameter limit, TeamRoomStoreSqlTest, TeamRoomStore
// @what: Checks the team room's SQL without a database: every kind of event that can come from a conversation carries the private rule, and no statement binds a list that grows with the private conversations.
// @flow: Exercises TeamRoomStore with a mocked NamedParameterJdbcTemplate, capturing the statements and their parameters
package os.aiworkforce.orchestrator.teamroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import os.aiworkforce.orchestrator.teamroom.TeamRoomStore.FeedBranch;
import os.aiworkforce.orchestrator.teamroom.TeamRoomStore.Viewer;

/**
 * The private-conversation rule as the statements carry it. The persistence test runs the rule
 * against Postgres when a database is at hand; this one runs everywhere, so a select that loses the
 * rule, or a statement that starts binding the hidden conversations one by one (which Postgres caps
 * at 65,535 parameters), fails the build.
 */
class TeamRoomStoreSqlTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final Viewer PERSON = Viewer.person("00000000-0000-7000-8000-0000000000f1");

    private NamedParameterJdbcTemplate jdbc;
    private TeamRoomStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        store = new TeamRoomStore(jdbc, new ObjectMapper());
    }

    /** The selects of a feed statement, one per kind, in stacking order. */
    private static List<String> branches(String sql) {
        return Arrays.asList(sql.split("\nunion all\n"));
    }

    /** Asserts that every parameter is one value, never a list: the count cannot grow with the data. */
    private static void assertNoListParameters(SqlParameterSource params) {
        for (String name : params.getParameterNames()) {
            assertThat(params.getValue(name)).as(name).isNotInstanceOf(Collection.class);
        }
    }

    // @find: test every feed branch carries the private rule, team room store sql
    @Test
    @DisplayName("every kind of event that can come from a conversation leaves out private work; direct runs need no rule")
    void everyBranchCarriesTheRule() {
        String sql = TeamRoomStore.feedSql(PERSON, true, true, new MapSqlParameterSource());
        List<String> selects = branches(sql);

        assertThat(selects).hasSize(TeamRoomStore.FEED_BRANCHES.size());
        for (int index = 0; index < selects.size(); index++) {
            FeedBranch branch = TeamRoomStore.FEED_BRANCHES.get(index);
            String select = selects.get(index);
            assertThat(select).as(branch.kind()).contains("'" + branch.kind() + "' as kind");
            if (branch.conversation() == null) {
                assertThat(select).as(branch.kind()).doesNotContain("conversations hc").doesNotContain("conversations gc");
                assertThat(select).as(branch.kind()).contains("r.task_id is null");
            } else {
                assertThat(select)
                        .as(branch.kind())
                        .contains("and not (" + branch.conversation() + " is not null and (")
                        // A deleted private conversation's work keeps its id and stays hidden.
                        .contains("not exists (select 1 from conversations gc where gc.id = " + branch.conversation() + ")")
                        .contains("or exists (select 1 from conversations hc")
                        .contains("where hc.id = " + branch.conversation())
                        .contains("hc.visibility = 'private'")
                        .contains("coalesce(hc.created_by, '') <> :me")
                        .contains("hp.user_id = :me");
            }
        }
        // Only runs started straight on an agent are free of the rule, as on the run pages.
        assertThat(TeamRoomStore.FEED_BRANCHES.stream().filter(branch -> branch.conversation() == null))
                .extracting(FeedBranch::kind)
                .containsExactly("run_requested", "run_finished");
        // A question can carry its conversation without a goal, so the rule reads both.
        assertThat(TeamRoomStore.FEED_BRANCHES.stream().filter(branch -> branch.kind().startsWith("question")))
                .extracting(FeedBranch::conversation)
                .containsOnly("coalesce(g.conversation_id, q.conversation_id)");
    }

    // @find: test a reader of all conversations gets no rule, team room store sql
    @Test
    @DisplayName("a holder of chat:read_all reads without the rule and binds no person")
    void aReaderOfAllGetsNoRule() {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = TeamRoomStore.feedSql(Viewer.seeingAll(), false, false, params);

        assertThat(sql).doesNotContain("conversations hc").doesNotContain("conversations gc").doesNotContain(":me");
        assertThat(params.hasValue("me")).isFalse();
    }

    // @find: test the feed binds the same few values whoever is hidden, team room store sql
    @Test
    @DisplayName("the feed binds the same few single values for any viewer, never a list of conversations")
    @SuppressWarnings("unchecked")
    void theFeedBindsOnlySingleValues() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);

        store.feedRows(ORG, PERSON, new FeedCursor(Instant.parse("2026-10-12T09:00:00Z"), "g:1"), true, 51);

        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertNoListParameters(params.getValue());
        assertThat(params.getValue().getParameterNames())
                .containsExactlyInAnyOrder("orgId", "limit", "textLimit", "cursorAt", "cursorId", "me");
        assertThat(params.getValue().getValue("me")).isEqualTo(PERSON.personId());
        assertThat(branches(sql.getValue())).hasSize(TeamRoomStore.FEED_BRANCHES.size());
    }

    // @find: test the presence reads bind only single values, team room store sql
    @Test
    @DisplayName("the presence reads carry the rule and bind the viewer once, as one value")
    @SuppressWarnings("unchecked")
    void thePresenceReadsBindOnlySingleValues() {
        ArgumentCaptor<String> runsSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> runsParams = ArgumentCaptor.forClass(SqlParameterSource.class);
        store.activeRuns(ORG, PERSON);
        verify(jdbc).query(runsSql.capture(), runsParams.capture(), any(RowMapper.class));
        assertThat(runsSql.getValue())
                .contains("not exists (select 1 from conversations gc where gc.id = g.conversation_id)")
                .contains("or exists (select 1 from conversations hc")
                .contains("as hidden")
                // A parked run's approval is read without its payload.
                .doesNotContain("payload");
        assertThat(runsParams.getValue().getValue("me")).isEqualTo(PERSON.personId());
        assertThat(runsParams.getValue().getValue("statuses")).isEqualTo(TeamRoomStore.ACTIVE_STATUSES);

        ArgumentCaptor<String> queuedSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> queuedParams = ArgumentCaptor.forClass(SqlParameterSource.class);
        Map<UUID, Integer> queued = store.queuedByAgent(ORG, PERSON);
        verify(jdbc).query(queuedSql.capture(), queuedParams.capture(), any(RowCallbackHandler.class));
        assertThat(queued).isEmpty();
        assertThat(queuedSql.getValue())
                .contains("and not (g.conversation_id is not null and (")
                .contains("or exists (select 1 from conversations hc");
        assertNoListParameters(queuedParams.getValue());
    }
}
