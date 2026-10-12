// @find: team room card, command map link, your AI team at work, open team room, agents at work now, TeamRoomCard
// @what: The Command Map's link card to the team room, with the agents' avatars and how many are at work.
// @flow: Rendered by routes/CommandMap.tsx for roles with run:read; reads useTeamRoom once (no polling).
import { Card, Eyebrow } from '../ui'
import { AgentAvatar } from '../ui/AgentAvatar'
import { isBusy, isWaiting, useTeamRoom } from '../../lib/teamRoomQueries'
import { can } from '../../lib/session'

/** Most avatars drawn on the card; the rest are counted. */
const AVATARS = 5

// @find: TeamRoomCard component, link card to the team room
/**
 * A card that opens the team room. It reads the presence once (no polling on the Command Map) to
 * draw a few avatars and say how many agents are at work or waiting; while that loads, or when it
 * fails, the card still links through with its plain description. Hidden without run:read.
 */
export function TeamRoomCard() {
  const allowed = can('run:read')
  const room = useTeamRoom({ enabled: allowed, live: false })
  if (!allowed) return null
  const agents = room.data?.agents ?? []
  const busy = agents.filter((agent) => isBusy(agent.state)).length
  const waiting = agents.filter((agent) => isWaiting(agent.state)).length
  const summary =
    agents.length === 0
      ? 'See each agent at work and how they hand work to each other.'
      : [
          busy === 0 ? 'No one is at work right now' : busy === 1 ? '1 agent at work now' : `${busy} agents at work now`,
          waiting > 0 ? (waiting === 1 ? '1 waiting on a person' : `${waiting} waiting on a person`) : null,
        ]
          .filter(Boolean)
          .join(', ') + '.'

  return (
    <Card href="/team-room" className="tr-card" aria-label={`Team room. ${summary}`}>
      <Eyebrow>Team room</Eyebrow>
      <span className="tr-card-row">
        {agents.length > 0 && (
          <span className="tr-card-avatars" aria-hidden="true">
            {agents.slice(0, AVATARS).map((agent) => (
              <span key={agent.id} className="tr-card-avatar" data-state={agent.state}>
                <AgentAvatar name={agent.name} category={agent.category} fallback={agent.fallback} size="sm" />
              </span>
            ))}
            {agents.length > AVATARS && <span className="tr-card-more caption">+{agents.length - AVATARS}</span>}
          </span>
        )}
        <span className="tr-card-text">
          <span className="tr-card-title">Your AI team at work, live</span>
          <span className="caption">{summary}</span>
        </span>
      </span>
    </Card>
  )
}
