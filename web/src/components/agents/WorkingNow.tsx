// @find: working now, agents working now, live tiles, watch agents work live, agent desk tiles, current step caption, elapsed time, small live browser frame, Agents page
// @what: The "Working now" section at the top of the Agents page: one live tile per agent with a running or waiting run, each opening the Agent desk.
// @flow: Rendered by routes/Agents.tsx for people with run:read; reads useBoard, useRunGlance and useTileLive; opens AgentDesk
import { useId, useState } from 'react'
import { AgentAvatar } from '../ui/AgentAvatar'
import { AgentDesk } from './AgentDesk'
import { startedAtOf, stepCaption, workingNow } from '../../lib/agentDesk'
import type { WorkingTile } from '../../lib/agentDesk'
import { tileInterval, useRunGlance, useTileLive } from '../../lib/agentDeskQueries'
import { formatCount, formatElapsed } from '../../lib/format'
import { useBoard } from '../../lib/queries'
import { useNow } from '../../lib/useNow'

/** Plain words for a tile's state, read before its caption. */
const STATE_LABEL: Record<WorkingTile['state'], string> = {
  running: 'Working',
  waiting: 'Waiting for an approval',
  asking: 'Waiting for an answer',
}

// @find: WorkingNow, working now section, live tiles of busy agents
/**
 * One live tile per agent that has a run working or waiting on a person, from the board the
 * Orchestrator reads (useBoard, which refreshes every few seconds while work is moving and slowly
 * otherwise). Clicking a tile opens the Agent desk for that run. Shows a quiet line while loading, a
 * plain sentence when nobody is working or the board could not be read. Render it only for people
 * who hold run:read; the board needs it.
 *
 * Polling stays light: each tile reads its run at a glance (not the whole trace) every five
 * seconds, or every eight when more than six tiles show, and its small frame as often while it
 * browses; every tile stops while a desk is open, since the desk reads that run faster itself.
 */
export function WorkingNow() {
  const board = useBoard()
  const headingId = useId()
  const [open, setOpen] = useState<WorkingTile | null>(null)
  const tiles = workingNow(board.data)
  const interval = tileInterval(tiles.length)

  return (
    <section className="desk-working" aria-labelledby={headingId}>
      <div className="desk-working-head">
        <h2 id={headingId} className="section-heading">
          Working now
        </h2>
        {tiles.length > 0 && <span className="caption muted">{`${formatCount(tiles.length)} working`}</span>}
      </div>
      {board.isLoading ? (
        <p className="caption muted">Checking who is working…</p>
      ) : board.isError && !board.data ? (
        <p className="caption muted">What the agents are doing right now could not be loaded. It refreshes by itself.</p>
      ) : tiles.length === 0 ? (
        <p className="caption muted">No agent is working right now. Work given to an agent shows here while it runs.</p>
      ) : (
        <ul className="desk-tiles" aria-label="Agents working now">
          {tiles.map((tile) => (
            <li key={tile.runId}>
              <WorkingTileCard tile={tile} paused={open !== null} interval={interval} onOpen={() => setOpen(tile)} />
            </li>
          ))}
        </ul>
      )}
      {open && <AgentDesk open onClose={() => setOpen(null)} runId={open.runId} agentName={open.name} />}
    </section>
  )
}

/**
 * One working agent: its avatar, name, what it is doing now, how long the run has gone, and while
 * it browses a small live frame of its page. The whole tile is one button that opens the desk.
 * Reads its run at a glance every `interval` milliseconds while the run works, and its frame as
 * often while it browses; neither while `paused` (a desk is open).
 */
function WorkingTileCard({
  tile,
  paused,
  interval,
  onOpen,
}: {
  tile: WorkingTile
  paused: boolean
  interval: number
  onOpen: () => void
}) {
  const working = tile.state === 'running'
  const glance = useRunGlance(tile.runId, working && !paused, interval).data
  const browsing = working && Boolean(glance?.browsing)
  const live = useTileLive(tile.runId, browsing && !paused, interval).data ?? null
  const now = useNow(1000)
  const captionId = useId()
  const latest = glance?.kind ? [{ kind: glance.kind, detail: glance.detail ?? {} }] : glance ? [] : undefined
  const caption = stepCaption(latest, tile.state, live)
  // The state is read before the caption, unless the caption already says it: a waiting tile was
  // read as "Waiting for an approval. Waiting for an approval" (13 Oct 2026).
  const stateFirst = caption.startsWith(STATE_LABEL[tile.state]) ? null : `${STATE_LABEL[tile.state]}. `
  const startedAt = startedAtOf(tile, glance?.startedAt)

  return (
    <button
      type="button"
      className="desk-tile"
      data-state={tile.state}
      onClick={onOpen}
      aria-label={`Watch ${tile.name} live`}
      aria-describedby={captionId}
    >
      <span className="desk-tile-head">
        <span className="desk-tile-avatar">
          <AgentAvatar name={tile.name} category={tile.category} fallback={tile.fallback} size="sm" />
          <span className="desk-tile-dot" data-state={tile.state} aria-hidden="true" />
        </span>
        <span className="desk-tile-name">{tile.name}</span>
        {startedAt && <span className="desk-tile-time caption tabular">{formatElapsed(startedAt, null, now)}</span>}
      </span>
      <span id={captionId} className="desk-tile-caption caption">
        {stateFirst && <span className="visually-hidden">{stateFirst}</span>}
        {caption}
      </span>
      {browsing && live?.frame && (
        <span className="desk-tile-frame">
          <img src={`data:image/jpeg;base64,${live.frame}`} alt="" />
          <span className="desk-tile-live caption">
            <span className="chat-pulse-dot" aria-hidden="true" />
            Live
          </span>
        </span>
      )}
    </button>
  )
}
