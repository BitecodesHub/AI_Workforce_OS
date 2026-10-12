// @find: team room page, living team room, agents as colleagues, live presence, hand-off conversation, who is doing what, /team-room, Team room, TeamRoom
// @what: The Team room page: every agent with an animated live state, and the team's hand-off conversation, newest at the bottom.
// @flow: Routed from App.tsx at /team-room (run:read); reads useTeamRoom and useTeamRoomFeed; opens AgentSheet for one agent.
import { useState } from 'react'
import { Card, EmptyState, Eyebrow, PageHeader, Tag } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { AgentSheet } from '../components/team-room/AgentSheet'
import { PresenceCard } from '../components/team-room/PresenceCard'
import { FeedAnnouncer, TeamFeed } from '../components/team-room/TeamFeed'
import { usePersonNamer } from '../components/team-room/usePersonNamer'
import { useReducedMotion } from '../hooks/useReducedMotion'
import { isBusy, isWaiting, useTeamRoom, useTeamRoomFeed } from '../lib/teamRoomQueries'
import type { AgentPresence } from '../lib/teamRoomQueries'
import { can } from '../lib/session'

/*
 * The team room makes the AI team feel like colleagues: each agent is a person-shaped card whose
 * avatar moves the way it works (breathing while it thinks, an orbiting dot while it browses), and
 * the conversation beside them reads like a team chat - who asked whom, who handed what to whom,
 * who is waiting on a decision. Both parts poll while the tab is visible (2 s and 3 s) and stop
 * when it is hidden. Motion is CSS only and stops under prefers-reduced-motion.
 */

/** Counts for the header: agents at work, waiting on a person, and waiting on the viewer. */
function summary(agents: AgentPresence[]): { busy: number; waiting: number; forYou: number } {
  return {
    busy: agents.filter((agent) => isBusy(agent.state)).length,
    waiting: agents.filter((agent) => isWaiting(agent.state)).length,
    forYou: agents.filter((agent) => agent.waitingForYou).length,
  }
}

// @find: TeamRoom page component, /team-room
/**
 * The Team room (route /team-room, permission run:read). Header: what the page is, with counts of
 * agents at work and waiting (one tag for what waits on the viewer) and links to the Orchestrator
 * and Chat. Body: "Who is here" (PresenceCard for every agent that is not retired) and "Team
 * conversation" (TeamFeed), each behind QueryState for its loading, empty, error, permission and
 * stale states, with the polite announcer of new lines (FeedAnnouncer) mounted outside it from the
 * start so its first announcement is heard. Choosing an agent opens AgentSheet with its current run.
 */
export function TeamRoom() {
  const room = useTeamRoom()
  const feed = useTeamRoomFeed()
  const nameOf = usePersonNamer()
  const reducedMotion = useReducedMotion()
  const [selected, setSelected] = useState<{ agentId: string; runId: string | null } | null>(null)

  const agents = room.data?.agents ?? []
  const counts = summary(agents)
  const selectedPresence = selected ? agents.find((agent) => agent.id === selected.agentId) : undefined

  const open = (agentId: string) => {
    const presence = agents.find((agent) => agent.id === agentId)
    setSelected({ agentId, runId: presence?.state === 'private' ? null : (presence?.runId ?? null) })
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Your AI team, live"
        title="Team room"
        description="See what each agent is doing right now, and follow how they hand work to each other."
        meta={
          room.data ? (
            <>
              <Tag tone="blue">{counts.busy === 1 ? '1 at work' : `${counts.busy} at work`}</Tag>
              {counts.waiting > 0 && (
                <Tag tone="warning">{counts.waiting === 1 ? '1 waiting on a person' : `${counts.waiting} waiting on a person`}</Tag>
              )}
              {counts.forYou > 0 && (
                <Tag tone="warning">{counts.forYou === 1 ? '1 waiting for you' : `${counts.forYou} waiting for you`}</Tag>
              )}
            </>
          ) : undefined
        }
        action={
          <>
            <a className="button button-quiet" href="/orchestrator">
              Orchestrator
            </a>
            {can('chat:use') && (
              <a className="button button-quiet" href="/chat">
                Chat
              </a>
            )}
          </>
        }
      />

      <div className="tr-layout" data-motion={reducedMotion ? 'reduced' : 'full'}>
        <section className="tr-presence" aria-labelledby="tr-presence-heading">
          <Eyebrow as="h2" id="tr-presence-heading">
            Who is here
          </Eyebrow>
          <QueryState
            query={room}
            permission="run:read"
            what="the team"
            keepData
            rows={3}
            isEmpty={(data) => data.agents.length === 0}
            empty={
              <Card>
                <EmptyState
                  icon={<EmptyIcon kind="task" />}
                  title="No agents yet"
                  body="Add an agent to see it here, at work alongside the rest of the team."
                  action={
                    can('agent:read') ? (
                      <a className="button button-outline" href="/agents">
                        Go to Agents
                      </a>
                    ) : undefined
                  }
                />
              </Card>
            }
          >
            {(data) => (
              <ul className="tr-agents" aria-label="Agents and what they are doing">
                {data.agents.map((presence) => (
                  <PresenceCard key={presence.id} presence={presence} nameOf={nameOf} onOpen={open} />
                ))}
              </ul>
            )}
          </QueryState>
        </section>

        <section className="tr-feed" aria-labelledby="tr-feed-heading">
          <Eyebrow as="h2" id="tr-feed-heading">
            Team conversation
          </Eyebrow>
          <FeedAnnouncer state={feed.data} nameOf={nameOf} />
          <QueryState
            query={feed}
            permission="run:read"
            what="the team conversation"
            keepData
            rows={5}
            isEmpty={(data) => data.events.length === 0 && !data.hasOlder}
            empty={
              <Card>
                <EmptyState
                  icon={<EmptyIcon kind="task" />}
                  title="Nothing has happened yet"
                  body="When someone gives the team work, the asking, the hand-offs and the answers appear here as they happen."
                />
              </Card>
            }
          >
            {(data) => (
              <TeamFeed
                state={data}
                nameOf={nameOf}
                onLoadOlder={() => void feed.loadOlder()}
                loadingOlder={feed.loadingOlder}
                olderError={feed.olderError}
                onReachBottom={feed.releaseOlder}
              />
            )}
          </QueryState>
        </section>
      </div>

      {selected && selectedPresence && (
        <AgentSheet
          presence={selectedPresence}
          openedRunId={selected.runId}
          nameOf={nameOf}
          onClose={() => setSelected(null)}
        />
      )}
    </div>
  )
}
