// @find: team room views, team room response, agent presence view, feed page, feed event, hand-off event, presence json, TeamRoomViews, Team room page
// @what: The JSON shapes the team room endpoints return: every agent's live presence, and pages of the hand-off conversation.
// @flow: Built by TeamRoomService; returned by TeamRoomController to the Team room page (web/src/lib/teamRoomQueries.ts).
package os.aiworkforce.orchestrator.teamroom;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the team room endpoints answer with. Plain records, so the wire format is exactly these
 * fields; the web client mirrors them in {@code web/src/lib/teamRoomQueries.ts}.
 */
public final class TeamRoomViews {

    private TeamRoomViews() {}

    /**
     * Every active and paused agent of the workspace (retired ones are left out), in name order, as
     * at {@code generatedAt}.
     */
    public record TeamRoom(Instant generatedAt, List<AgentPresence> agents) {}

    /**
     * One agent and what it is doing now.
     *
     * <p>{@code state} is one of {@code idle}, {@code thinking}, {@code browsing}, {@code using_tool},
     * {@code waiting_approval}, {@code waiting_input}, {@code paused} or {@code private}. The run,
     * goal and task fields name the work the state is about, and are null for {@code idle},
     * {@code paused} and {@code private}: work from a private conversation the viewer is not part of
     * is shown only as "private work", never by its title or run.
     *
     * @param description the agent's one-line role, or null when none was written
     * @param agentStatus the agent's own status: active or paused
     * @param since when the current state began, or null when the agent is idle or paused
     * @param server the tool server of the call under way (browsing and using_tool)
     * @param tool the tool of the call under way, without the server (browsing and using_tool)
     * @param site the web site the browser is on, without "www." (browsing, when known)
     * @param approvalId the approval the run waits on (waiting_approval)
     * @param questionId the question the run waits on (waiting_input)
     * @param waitingText the approval's summary or the question's first line (waiting states)
     * @param waitingOn for a question, the person the work was asked for by, who is usually the one to
     *     answer it; null for an approval, whose requester may be the one person a four-eyes rule
     *     stops from deciding it
     * @param waitingForYou the viewer may decide the approval or answer the question now
     * @param activeRuns how many of the agent's runs are in progress, private ones included
     * @param queued how many of its tasks wait to start, private ones left out
     */
    public record AgentPresence(
            UUID id,
            String name,
            String category,
            String description,
            boolean fallback,
            String agentStatus,
            String state,
            Instant since,
            UUID runId,
            UUID goalId,
            String goalTitle,
            UUID taskId,
            String taskTitle,
            String server,
            String tool,
            String site,
            UUID approvalId,
            UUID questionId,
            String waitingText,
            UUID waitingOn,
            boolean waitingForYou,
            int activeRuns,
            int queued) {}

    /**
     * One page of the conversation, oldest first so it reads top to bottom with the newest last.
     *
     * @param newestCursor pass as {@code after} to ask for what happened since; null on an earlier page
     * @param olderCursor pass as {@code before} to ask for the page before this one; null when there is none
     * @param hasOlder whether events older than this page exist
     * @param hasNewer whether more events after this page exist than one page held
     */
    public record FeedPage(
            Instant generatedAt,
            List<FeedEvent> events,
            String newestCursor,
            String olderCursor,
            boolean hasOlder,
            boolean hasNewer) {}

    /**
     * One line of the conversation.
     *
     * <p>{@code kind} is one of {@code goal_requested} (a person asked an agent), {@code
     * schedule_started} (a schedule started a goal), {@code task_started}, {@code task_handed_off}
     * (finished and passed to the next agent), {@code task_finished}, {@code task_failed}, {@code
     * run_requested} (work started straight on an agent, not through a goal), {@code run_finished},
     * {@code run_failed}, {@code approval_requested}, {@code approval_approved}, {@code
     * approval_rejected}, {@code approval_sent_back}, {@code approval_expired}, {@code
     * question_asked} or {@code question_answered}. The id is stable: the same event always has the
     * same id, so a client merging pages drops repeats by id.
     *
     * @param agentName the agent's name as it is now, or null when the agent was deleted
     * @param personId who asked, decided or answered, or who started a direct run; the client names
     *     them. For approval_requested it is who asked for the work, never who is to decide it
     * @param nextAgentId the agent the work was handed to (task_handed_off)
     * @param title the goal title (goal_requested, schedule_started), the task title (task events)
     *     or the first line of what a direct run was asked (run events)
     * @param text the approval summary, the question, the one-line result or why the work failed
     * @param scheduleName the schedule that started the goal (schedule_started)
     * @param forYou approval_requested only: the approval is still waiting and this viewer may
     *     decide it now
     * @param cursor this event's position, to pass as {@code before} when the client has let the
     *     events older than it go and wants them back
     */
    public record FeedEvent(
            String id,
            String cursor,
            String kind,
            Instant at,
            UUID agentId,
            String agentName,
            String agentCategory,
            boolean agentFallback,
            UUID personId,
            UUID nextAgentId,
            String nextAgentName,
            String nextAgentCategory,
            String nextTaskTitle,
            UUID goalId,
            UUID taskId,
            UUID runId,
            UUID approvalId,
            UUID questionId,
            String title,
            String text,
            String scheduleName,
            boolean forYou) {}
}
