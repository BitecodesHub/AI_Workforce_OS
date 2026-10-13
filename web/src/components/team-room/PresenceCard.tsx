// @find: presence card, agent presence, animated avatar, live status, idle, thinking, browsing, using, waiting for you, paused, private work, Team room, PresenceCard
// @what: One agent in the team room: its avatar with an animated state ring, name, role line, what it is doing now and, when it waits on the viewer, the way to act.
// @flow: Rendered by routes/TeamRoom.tsx for each agent of useTeamRoom; opens AgentSheet through onOpen.
import { Time } from '../ui'
import { AgentAvatar } from '../ui/AgentAvatar'
import { CATEGORY_LABEL } from '../../lib/labels'
import { readableRequestSentence } from '../../lib/approvals'
import { isBusy, isWaiting, presenceCaption, withoutAgentName } from '../../lib/teamRoomQueries'
import type { AgentPresence, PersonNamer } from '../../lib/teamRoomQueries'
import { can } from '../../lib/session'

/** The agent's role in one line: what it was written to do, else its team. */
export function roleLine(presence: Pick<AgentPresence, 'description' | 'category' | 'fallback'>): string {
  if (presence.description && presence.description.trim()) return presence.description
  if (presence.fallback) return 'Takes any request no specialist covers'
  return `${CATEGORY_LABEL[presence.category ?? ''] ?? 'General'} team`
}

/**
 * What the agent is working on, in a few words, when the viewer may see it: the request it waits
 * on (its tool named as every other screen names it), the question it asked, or its task, without
 * the agent's name a workflow step's title opens with (the card already shows it).
 */
function workLine(presence: AgentPresence): string | null {
  if (isWaiting(presence.state) && presence.waitingText) {
    return presence.state === 'waiting_approval' ? readableRequestSentence(presence.waitingText) : presence.waitingText
  }
  if (presence.taskTitle) return `On: ${withoutAgentName(presence.taskTitle, presence.name)}`
  if (presence.state === 'idle' && presence.queued > 0) {
    return presence.queued === 1 ? '1 task waiting to start' : `${presence.queued} tasks waiting to start`
  }
  return null
}

/** Where the viewer acts on what the agent waits for: the approval, or the run that asked. */
function actionFor(presence: AgentPresence): { href: string; label: string } | null {
  if (!presence.waitingForYou) return null
  if (presence.state === 'waiting_approval' && presence.approvalId && can('approval:read')) {
    return { href: `/approvals#approval-${presence.approvalId}`, label: 'Review and decide' }
  }
  if (presence.state === 'waiting_input' && presence.runId) {
    return { href: `/runs/${presence.runId}`, label: 'Answer the question' }
  }
  return null
}

// @find: PresenceCard component, agent presence card, animated state, open agent
/**
 * One agent's card. The avatar wears a ring whose motion says the state (breathing while thinking,
 * an orbiting dot while browsing, a quick pulse while using a connector, a slow glow while waiting;
 * still when idle, paused or busy with private work). All motion is CSS and stops under
 * prefers-reduced-motion. The whole card is one button that opens what the agent is doing
 * (onOpen); when the agent waits on the viewer, a separate link leads to the decision or answer.
 * The state is in the text, so nothing depends on the animation or the colour alone.
 */
export function PresenceCard({
  presence,
  nameOf,
  onOpen,
}: {
  presence: AgentPresence
  nameOf: PersonNamer
  onOpen: (agentId: string) => void
}) {
  const caption = presenceCaption(presence, nameOf)
  const work = workLine(presence)
  const action = actionFor(presence)
  const more = presence.activeRuns > 1 ? presence.activeRuns - 1 : 0
  return (
    <li
      className="tr-agent"
      data-state={presence.state}
      data-busy={isBusy(presence.state) || undefined}
      data-for-you={presence.waitingForYou || undefined}
    >
      <button type="button" className="tr-agent-open" onClick={() => onOpen(presence.id)}>
        <span className="tr-agent-figure" aria-hidden="true">
          <span className="tr-agent-halo" />
          {presence.state === 'browsing' && <span className="tr-agent-orbit" />}
          <AgentAvatar name={presence.name} category={presence.category} fallback={presence.fallback} />
          <span className="tr-agent-badge" />
        </span>
        <span className="tr-agent-text">
          <span className="tr-agent-name">{presence.name}</span>
          <span className="tr-agent-role caption">{roleLine(presence)}</span>
          <span className="tr-agent-state">
            <span className="tr-agent-caption">{caption}</span>
            {presence.state === 'thinking' && (
              <span className="tr-typing" aria-hidden="true">
                <span />
                <span />
                <span />
              </span>
            )}
          </span>
          {work && <span className="tr-agent-work caption">{work}</span>}
          {(presence.since || more > 0) && (
            <span className="tr-agent-since caption">
              {presence.since && (
                <>
                  Since <Time iso={presence.since} />
                </>
              )}
              {presence.since && more > 0 && ' · '}
              {more > 0 && (more === 1 ? '1 more piece of work' : `${more} more pieces of work`)}
            </span>
          )}
        </span>
      </button>
      {action && (
        <a className="tr-agent-action link" href={action.href}>
          {action.label}
          <span className="visually-hidden"> for {presence.name}</span>
        </a>
      )}
    </li>
  )
}
