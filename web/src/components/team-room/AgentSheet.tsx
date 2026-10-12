// @find: agent sheet, what is this agent doing, current run, compact trace, browser preview, live view, private work, idle agent, Team room, AgentSheet
// @what: The side sheet that opens from an agent in the team room: its live state and, when the viewer may see it, its current run as a compact trace with the live browser preview.
// @flow: Opened by routes/TeamRoom.tsx; reuses RunTraceCompact (which includes BrowserPreview, WaitingForApproval and WaitingForAnswer) from components/run.
import { Notice, Time } from '../ui'
import { AgentAvatar } from '../ui/AgentAvatar'
import { Sheet } from '../ui/Sheet'
import { RunTraceCompact } from '../run/RunTraceCompact'
import { roleLine } from './PresenceCard'
import { useMediaQuery } from '../../hooks/useMediaQuery'
import { presenceCaption } from '../../lib/teamRoomQueries'
import type { AgentPresence, PersonNamer } from '../../lib/teamRoomQueries'
import { useRouter } from '../../lib/router'
import { can } from '../../lib/session'

// @find: AgentSheet component, open agent in team room, current run trace
/**
 * What one agent is doing, opened from its card. It follows the live presence: the run shown is
 * the one the agent is on now, or, once that finishes, the run it was on when the sheet opened, so
 * the finished answer stays readable. The run part is the shared compact trace (RunTraceCompact),
 * which polls only while the run is active and shows the web browser's live preview while the
 * agent browses. Work from someone else's private conversation is never opened: the sheet says the
 * agent is busy and no more. Non-modal at 1024 pixels and wider, modal below; full width on phones.
 */
export function AgentSheet({
  presence,
  openedRunId,
  nameOf,
  onClose,
}: {
  presence: AgentPresence
  /** The run the agent was on when the sheet opened, kept once that run finishes. */
  openedRunId: string | null
  nameOf: PersonNamer
  onClose: () => void
}) {
  const { navigate } = useRouter()
  const atLeast1024 = useMediaQuery('(min-width: 1024px)')
  const atLeast768 = useMediaQuery('(min-width: 768px)')
  const runId = presence.state === 'private' ? null : (presence.runId ?? openedRunId)
  const caption = presenceCaption(presence, nameOf)

  return (
    <Sheet
      open
      onClose={onClose}
      side="right"
      modal={!atLeast1024}
      width={atLeast1024 ? 'md' : atLeast768 ? 'sm' : 'full'}
      eyebrow="What they are doing"
      title={presence.name}
      footer={
        <>
          {runId && can('run:read') && (
            <a className="link" href={`/runs/${runId}`}>
              Open run
            </a>
          )}
          {can('agent:read') && (
            <a className="link" href={`/agents/${presence.id}`}>
              Open {presence.name}
            </a>
          )}
        </>
      }
    >
      <div className="stack tr-sheet">
        <div className="tr-sheet-status" data-state={presence.state}>
          <span className="tr-agent-figure" aria-hidden="true">
            <span className="tr-agent-halo" />
            {presence.state === 'browsing' && <span className="tr-agent-orbit" />}
            <AgentAvatar name={presence.name} category={presence.category} fallback={presence.fallback} />
            <span className="tr-agent-badge" />
          </span>
          <div>
            <p className="tr-sheet-caption">{caption}</p>
            <p className="caption">{roleLine(presence)}</p>
            {presence.since && (
              <p className="caption">
                Since <Time iso={presence.since} />
              </p>
            )}
          </div>
        </div>

        {presence.taskTitle && (
          <p>
            <span className="caption">Working on </span>
            {presence.taskTitle}
            {presence.goalTitle && presence.goalTitle !== presence.taskTitle && (
              <span className="caption"> for {presence.goalTitle}</span>
            )}
          </p>
        )}

        {presence.state === 'private' ? (
          <Notice tone="info">
            {presence.name} is working on something from a private conversation. Only the people in that
            conversation can see it.
          </Notice>
        ) : runId ? (
          <RunTraceCompact
            key={runId}
            runId={runId}
            headingLevel="h3"
            onGoToQuestion={() => navigate(`/runs/${runId}`)}
          />
        ) : (
          <p className="caption">
            {presence.state === 'paused'
              ? `${presence.name} is paused and will not start new work until someone resumes it.`
              : `${presence.name} has nothing in progress right now.`}
            {presence.queued > 0 &&
              (presence.queued === 1 ? ' One task is waiting to start.' : ` ${presence.queued} tasks are waiting to start.`)}
          </p>
        )}
      </div>
    </Sheet>
  )
}
