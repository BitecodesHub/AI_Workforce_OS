// @find: team room glance, command map team room, agents at work now, waiting for you, latest hand-offs, last three lines, open team room, give an agent a task, TeamRoomGlance
// @what: The Command Map's glance at the team room: every agent with its live state, the last three lines of the team conversation, and the ways on to the team room and to give an agent a task.
// @flow: Rendered first by routes/CommandMap.tsx; reads useTeamRoom and useTeamRoomFeed every five seconds while the tab is visible (run:read only); words lines with feedLine and states with presenceCaption.
import type { ReactNode } from 'react'
import { Button, Eyebrow, Time } from '../ui'
import { AgentAvatar } from '../ui/AgentAvatar'
import { actionFor } from './PresenceCard'
import { Speaker } from './TeamFeed'
import { usePersonNamer } from './usePersonNamer'
import { feedLine, feedSpeaker, isBusy, isWaiting, presenceCaption, useTeamRoom, useTeamRoomFeed } from '../../lib/teamRoomQueries'
import type { AgentPresence, PersonNamer } from '../../lib/teamRoomQueries'
import { can } from '../../lib/session'

/*
 * The first thing on the Command Map. It answers "what is my AI team doing right now" in one look:
 * each agent with the same animated state the team room shows (thinking, browsing a site, using a
 * connector, waiting for you), the last three lines of how they handed work to each other, and a
 * way on to the full team room. It reads every five seconds rather than the team room's two and
 * three, because it is a glance, and only while the tab is visible (React Query pauses then).
 */

/** Lines of the team conversation the glance shows, newest last. */
export const GLANCE_LINES = 3

/** How often the glance reads the presence and the conversation while the tab is visible. */
export const GLANCE_POLL_MS = 5_000

// @find: glance summary, agents at work count, waiting for you count
/**
 * The header's one line: how many agents are at work and how many wait on the viewer, or that the
 * team is quiet. Waiting counts only what waits on this viewer, so it never asks someone to act on
 * a request they cannot decide. Pure.
 */
export function glanceSummary(agents: readonly AgentPresence[]): string {
  if (agents.length === 0) return 'No agents yet.'
  const busy = agents.filter((agent) => isBusy(agent.state)).length
  const forYou = agents.filter((agent) => isWaiting(agent.state) && agent.waitingForYou).length
  const parts = [
    busy === 0 ? 'Nobody is at work right now' : busy === 1 ? '1 agent at work' : `${busy} agents at work`,
    forYou === 0 ? null : forYou === 1 ? '1 waiting for you' : `${forYou} waiting for you`,
  ]
  return `${parts.filter(Boolean).join(', ')}.`
}

/** One agent in the glance: its animated figure, name and what it is doing, linked to where to act or to the team room. */
function GlanceAgent({ presence, nameOf }: { presence: AgentPresence; nameOf: PersonNamer }) {
  const caption = presenceCaption(presence, nameOf)
  const action = actionFor(presence)
  return (
    <li
      className="tr-agent tr-glance-agent"
      data-state={presence.state}
      data-busy={isBusy(presence.state) || undefined}
      data-for-you={presence.waitingForYou || undefined}
    >
      <a className="tr-glance-agent-link" href={action?.href ?? '/team-room'}>
        <span className="tr-agent-figure" aria-hidden="true">
          <span className="tr-agent-halo" />
          {presence.state === 'browsing' && <span className="tr-agent-orbit" />}
          <AgentAvatar name={presence.name} category={presence.category} fallback={presence.fallback} size="sm" />
          <span className="tr-agent-badge" />
        </span>
        <span className="tr-glance-agent-text">
          <span className="tr-agent-name">{presence.name}</span>
          <span className="tr-agent-caption caption">{action ? `${caption}. ${action.label}` : caption}</span>
        </span>
      </a>
    </li>
  )
}

// @find: TeamRoomGlance component, command map team room glance
/**
 * The glance. Header: the eyebrow the screen opens with, "Team room", the summary line, and the
 * page's own action (Give an agent a task) beside "Open team room". Body: one chip per agent (its
 * link goes to the approval or question waiting on the viewer, otherwise to the team room) and the
 * last GLANCE_LINES lines of the conversation. States: loading, could not be read (with Try again),
 * no agents, nothing said yet. A role without run:read sees only the header's action, so the page
 * keeps its way to give a task.
 */
export function TeamRoomGlance({ eyebrow, action }: { eyebrow: string; action?: ReactNode }) {
  const allowed = can('run:read')
  const room = useTeamRoom({ enabled: allowed, pollMs: GLANCE_POLL_MS })
  const feed = useTeamRoomFeed({ enabled: allowed, pollMs: GLANCE_POLL_MS })
  const nameOf = usePersonNamer()

  if (!allowed) {
    return action ? <div className="tr-glance-actions">{action}</div> : null
  }

  const agents = room.data?.agents ?? []
  const lines = (feed.data?.events ?? []).slice(-GLANCE_LINES)

  return (
    <section className="card tr-glance" aria-labelledby="tr-glance-title">
      <div className="tr-glance-head">
        <div className="tr-glance-heading">
          <Eyebrow>{eyebrow}</Eyebrow>
          <h2 id="tr-glance-title" className="section-heading tr-glance-title">
            Team room
          </h2>
          <p className="caption" aria-live="polite">
            {room.isPending ? 'Looking at what your agents are doing.' : room.isError ? '' : glanceSummary(agents)}
          </p>
        </div>
        <div className="tr-glance-actions">
          {action}
          <a className="button button-quiet" href="/team-room">
            Open team room
          </a>
        </div>
      </div>

      {room.isError ? (
        <p className="caption">
          What your agents are doing could not be read just now.{' '}
          <Button variant="outline" className="button-sm" onClick={() => void room.refetch()}>
            Try again
          </Button>
        </p>
      ) : room.isPending ? null : agents.length === 0 ? (
        <p className="caption">
          Add an agent on the{' '}
          <a className="link" href="/agents">
            Agents
          </a>{' '}
          page to see it here.
        </p>
      ) : (
        <ul className="tr-glance-agents" aria-label="Your agents now">
          {agents.map((presence) => (
            <GlanceAgent key={presence.id} presence={presence} nameOf={nameOf} />
          ))}
        </ul>
      )}

      {!room.isError && agents.length > 0 && (
        <div className="tr-glance-feed">
          <p className="caption tr-glance-feed-label" id="tr-glance-feed-label">
            Latest in the team room
          </p>
          {feed.isError ? (
            <p className="caption">The latest lines could not be read just now.</p>
          ) : feed.isPending ? null : lines.length === 0 ? (
            <p className="caption">Nothing yet. Work an agent picks up shows here as it happens.</p>
          ) : (
            <ol className="tr-glance-lines" aria-labelledby="tr-glance-feed-label">
              {lines.map((event) => {
                const line = feedLine(event, nameOf)
                return (
                  <li key={event.id} className="tr-glance-line" data-speaker={feedSpeaker(event)}>
                    <Speaker event={event} nameOf={nameOf} />
                    <span className="tr-glance-line-text">{line.text}</span>
                    <span className="caption tr-glance-line-time">
                      <Time iso={event.at} />
                    </span>
                  </li>
                )
              })}
            </ol>
          )}
        </div>
      )}
    </section>
  )
}
