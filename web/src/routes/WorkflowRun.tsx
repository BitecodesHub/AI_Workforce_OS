// @find: workflow run view, live workflow, watch a run, task status on canvas, hand-off token animation, replay, time-lapse, replay a goal, event log, open a step's run, /workflows/run/:goalId, WorkflowRun
// @what: The run view of a goal on its canvas: live statuses and hand-off tokens while it runs, then a time-lapse replay with an event log once it has finished; a step opens its run.
// @flow: Routed from App.tsx at /workflows/run/:goalId; polls useGoalTimeline while the goal is active; builds the replay with lib/workflowReplay; draws with WorkflowCanvas; steps open StepRunSheet
import { useEffect, useMemo, useRef, useState } from 'react'
import { PageHeader, StatusTag, Time } from '../components/ui'
import { BackLink, QueryState } from '../components/ui/QueryState'
import { NOTHING_SELECTED, WorkflowCanvas } from '../components/workflows/WorkflowCanvas'
import type { NodeFace, Selection, Token } from '../components/workflows/WorkflowCanvas'
import { ReplayPlayer, clockTime, useReplayClock } from '../components/workflows/ReplayPlayer'
import { StepRunSheet } from '../components/workflows/StepRunSheet'
import { useReducedMotion } from '../hooks/useReducedMotion'
import { plural } from '../lib/format'
import { useDocumentTitle } from '../lib/router'
import type { Viewport, WorkflowGraph } from '../lib/workflowGraph'
import {
  STEP_STATUS_LABEL,
  buildReplay,
  dependencies,
  eventSentence,
  goalCanvas,
  handoffsAt,
  isGoalSettled,
  liveStates,
  newHandoffs,
  stateAt,
} from '../lib/workflowReplay'
import type { GoalCanvas, StepStatus } from '../lib/workflowReplay'
import { useGoalTimeline } from '../lib/workflowQueries'
import type { GoalTimeline } from '../lib/workflowQueries'

/*
 * One page for both halves of a run's life. While the goal is under way the canvas shows each
 * step's status as the server reports it, read every few seconds while the tab is visible, and a
 * token travels along a connection when a step hands over. Once it has finished, the same canvas
 * becomes a replay: the clock moves through the recorded timestamps and the canvas shows the state
 * at each moment. Under reduced motion nothing travels: the connection that handed over is
 * highlighted instead, and the replay moves one event at a time.
 */

/** How long a live hand-off token travels, and how long a highlight lasts without motion. */
const TRAVEL_MS = 900
const HIGHLIGHT_MS = 2_500
/** How long, in real time, a replay token takes along a connection at any speed. */
const REPLAY_TRAVEL_REAL_MS = 900

// @find: workflow run page component
/** The /workflows/run/:goalId screen: loads the timeline and shows the live view or the replay. */
export function WorkflowRun({ goalId }: { goalId: string }) {
  const query = useGoalTimeline(goalId)
  return (
    <div className="page wf-run">
      <BackLink href="/workflows" label="Workflows" />
      <QueryState
        query={query}
        permission="task:read"
        what="this run"
        keepData
        notFound={<BackLink href="/workflows" label="Back to Workflows" />}
      >
        {(timeline) => <RunView key={isGoalSettled(timeline.status) ? 'replay' : 'live'} timeline={timeline} />}
      </QueryState>
    </div>
  )
}

/** The edge id between two task nodes on a goal canvas, if they are connected. */
function edgeBetween(canvas: GoalCanvas, fromTask: string, toTask: string): string | null {
  const from = canvas.nodeOfTask.get(fromTask)
  const to = canvas.nodeOfTask.get(toTask)
  return canvas.graph.edges.find((edge) => edge.from === from && edge.to === to)?.id ?? null
}

/** The run view for one timeline: live while the goal runs, a replay once it has settled. */
function RunView({ timeline }: { timeline: GoalTimeline }) {
  const reduceMotion = useReducedMotion()
  const live = !isGoalSettled(timeline.status)
  const canvas = useMemo(() => goalCanvas(timeline), [timeline])
  const replay = useMemo(() => buildReplay(timeline), [timeline])
  const before = useMemo(() => dependencies(timeline.tasks), [timeline.tasks])
  const taskIds = useMemo(() => timeline.tasks.map((task) => task.id), [timeline.tasks])
  const tasksById = useMemo(() => new Map(timeline.tasks.map((task) => [task.id, task])), [timeline.tasks])
  const [viewport, setViewport] = useState<Viewport>({ x: 32, y: 32, zoom: 1 })
  const [selection, setSelection] = useState<Selection>(NOTHING_SELECTED)
  const [opened, setOpened] = useState<string | null>(null)
  const clock = useReplayClock(replay, reduceMotion)
  const flights = useLiveHandoffs(timeline, canvas, before, live, reduceMotion)

  const title = timeline.workflow?.name ?? timeline.title
  useDocumentTitle(title)

  // What each step shows: the live status while running, the state at the clock in a replay.
  const states: Map<string, StepStatus> = live ? liveStates(timeline) : stateAt(replay, taskIds, clock.at)
  let tokens: Token[] = []
  let activeEdges = new Set<string>()
  if (live) {
    tokens = flights.tokens
    activeEdges = flights.active
  } else {
    const travel = reduceMotion ? 1 : REPLAY_TRAVEL_REAL_MS * clock.speed
    for (const handoff of handoffsAt(replay, clock.at, travel)) {
      const edgeId = edgeBetween(canvas, handoff.fromTaskId, handoff.toTaskId)
      if (!edgeId) continue
      tokens.push({ edgeId, progress: handoff.progress })
      activeEdges.add(edgeId)
    }
  }

  const nameOf = (taskId: string) => {
    const task = tasksById.get(taskId)
    return task ? task.title : 'A step'
  }
  const face = (node: WorkflowGraph['nodes'][number]): NodeFace => {
    const taskId = canvas.taskOfNode.get(node.id)
    const task = taskId ? tasksById.get(taskId) : undefined
    return {
      agentName: task?.agentName ?? 'An agent no longer in the workspace',
      agentCategory: task?.agentCategory ?? null,
      fallback: false,
      heading: task?.title ?? node.title ?? 'Step',
      number: (task?.position ?? 0) + 1,
      connectors: [],
      problem: null,
      problemText: null,
      status: taskId ? (states.get(taskId) ?? 'queued') : 'idle',
    }
  }

  const visibleEvents = live ? replay.events : replay.events.filter((event) => event.at <= clock.at)
  const currentEvent = visibleEvents[visibleEvents.length - 1]
  const openedTask = opened ? tasksById.get(opened) : undefined
  const counts = countStates(states)

  return (
    <>
      <PageHeader
        eyebrow={timeline.workflow ? 'Workflow run' : 'Goal replay'}
        title={title}
        description={
          live
            ? 'Watching live. The canvas updates every few seconds while the work is under way. Select a step to see its run.'
            : 'Replay how the work moved, using the times each step started, waited, finished and handed over. Select a step to see its run.'
        }
        meta={
          <>
            <StatusTag kind="goal" status={timeline.status} withDot />
            <span className="caption muted">
              Started <Time iso={timeline.createdAt} />
              {timeline.completedAt ? (
                <>
                  , finished <Time iso={timeline.completedAt} />
                </>
              ) : null}
            </span>
            {timeline.workflow?.workflowId && (
              <a className="link" href={`/workflows/${timeline.workflow.workflowId}`}>
                Open the workflow
              </a>
            )}
          </>
        }
      />

      {live ? (
        <p className="wf-live" role="status">
          <span className="wf-live-dot" aria-hidden="true" />
          Live: {summary(counts, timeline.tasks.length)}
        </p>
      ) : (
        <ReplayPlayer
          replay={replay}
          at={clock.at}
          playing={clock.playing}
          speed={clock.speed}
          reduceMotion={reduceMotion}
          onPlay={clock.play}
          onPause={clock.pause}
          onSeek={clock.seek}
          onSpeed={clock.setSpeed}
          current={currentEvent ? `${clockTime(currentEvent.at)}: ${eventSentence(currentEvent, nameOf)}` : null}
        />
      )}

      <div className="wf-run-body">
        <WorkflowCanvas
          graph={canvas.graph}
          label={`${live ? 'Live view' : 'Replay'} of ${title}`}
          mode="view"
          viewport={viewport}
          onViewport={setViewport}
          selection={selection}
          onSelect={setSelection}
          face={face}
          tokens={tokens}
          activeEdges={activeEdges}
          reduceMotion={reduceMotion}
          onOpenNode={(nodeId) => {
            const taskId = canvas.taskOfNode.get(nodeId)
            if (taskId) setOpened(taskId)
          }}
        />
        <section className="wf-events" aria-labelledby="wf-events-heading">
          <h2 id="wf-events-heading" className="section-heading">
            What happened
          </h2>
          {visibleEvents.length === 0 ? (
            <p className="caption muted">{live ? 'Nothing has started yet.' : 'Move the replay forward to see events.'}</p>
          ) : (
            <ol className="wf-event-list">
              {visibleEvents.map((event, index) => (
                <li
                  key={`${event.taskId}-${event.kind}-${event.at}-${index}`}
                  data-current={event === currentEvent || undefined}
                  aria-current={event === currentEvent ? 'step' : undefined}
                >
                  {live ? (
                    <span>
                      <time dateTime={new Date(event.at).toISOString()} className="caption muted">
                        {clockTime(event.at)}
                      </time>{' '}
                      {eventSentence(event, nameOf)}
                    </span>
                  ) : (
                    <button type="button" className="wf-event-button" onClick={() => clock.seek(event.at)}>
                      <time dateTime={new Date(event.at).toISOString()} className="caption muted">
                        {clockTime(event.at)}
                      </time>{' '}
                      {eventSentence(event, nameOf)}
                    </button>
                  )}
                </li>
              ))}
            </ol>
          )}
          <Legend />
        </section>
      </div>

      {openedTask && <StepRunSheet task={openedTask} goalStatus={timeline.status} onClose={() => setOpened(null)} />}
    </>
  )
}

/** How many steps are in each state. */
function countStates(states: ReadonlyMap<string, StepStatus>): Map<StepStatus, number> {
  const counts = new Map<StepStatus, number>()
  for (const status of states.values()) counts.set(status, (counts.get(status) ?? 0) + 1)
  return counts
}

/**
 * "2 of 5 steps completed, 1 running, 1 waiting for approval". Steps that ended without completing
 * (failed, cancelled, skipped) are named too, so a branch stopped by a rejection while another still
 * runs does not read as work yet to come (seen live on 13 Oct 2026).
 */
function summary(counts: ReadonlyMap<StepStatus, number>, total: number): string {
  const parts = [`${counts.get('completed') ?? 0} of ${plural(total, 'step', 'steps')} completed`]
  for (const status of ['running', 'approval', 'question', 'failed', 'cancelled', 'skipped'] as const) {
    const n = counts.get(status) ?? 0
    if (n > 0) parts.push(`${n} ${STEP_STATUS_LABEL[status].toLowerCase()}`)
  }
  return parts.join(', ')
}

/** The key to the step colours, in words as well as colour. */
function Legend() {
  const shown: StepStatus[] = ['queued', 'running', 'approval', 'question', 'completed', 'failed']
  return (
    <ul className="wf-legend" aria-label="What the colours mean">
      {shown.map((status) => (
        <li key={status}>
          <span className="wf-status" data-status={status}>
            <span className="wf-status-dot" />
            {STEP_STATUS_LABEL[status]}
          </span>
        </li>
      ))}
    </ul>
  )
}

// @find: live hand-off animation hook, tokens between polls
/**
 * Tokens for the hand-offs that happened since the previous poll. Each travels for TRAVEL_MS (or,
 * under reduced motion, its connection is highlighted for HIGHLIGHT_MS); an animation frame clock
 * runs only while one is in flight. The first poll starts nothing.
 */
function useLiveHandoffs(
  timeline: GoalTimeline,
  canvas: GoalCanvas,
  before: ReadonlyMap<string, readonly string[]>,
  live: boolean,
  reduceMotion: boolean,
): { tokens: Token[]; active: Set<string> } {
  const previous = useRef<Map<string, StepStatus> | null>(null)
  const [flights, setFlights] = useState<Array<{ edgeId: string; startedAt: number }>>([])
  const [now, setNow] = useState(0)
  const duration = reduceMotion ? HIGHLIGHT_MS : TRAVEL_MS

  useEffect(() => {
    if (!live) return
    const next = liveStates(timeline)
    const found = newHandoffs(previous.current, next, before)
    previous.current = next
    const edges = found
      .map((handoff) => edgeBetween(canvas, handoff.fromTaskId, handoff.toTaskId))
      .filter((edgeId): edgeId is string => edgeId !== null)
    if (edges.length === 0) return
    const frame = window.requestAnimationFrame((at) => {
      setNow(at)
      setFlights((current) => [
        ...current.filter((flight) => at - flight.startedAt < duration),
        ...edges.map((edgeId) => ({ edgeId, startedAt: at })),
      ])
    })
    return () => window.cancelAnimationFrame(frame)
  }, [timeline, canvas, before, live, duration])

  useEffect(() => {
    if (flights.length === 0) return
    let frame = 0
    const tick = (at: number) => {
      setNow(at)
      if (flights.some((flight) => at - flight.startedAt < duration)) {
        frame = window.requestAnimationFrame(tick)
      } else {
        setFlights([])
      }
    }
    frame = window.requestAnimationFrame(tick)
    return () => window.cancelAnimationFrame(frame)
  }, [flights, duration])

  const inFlight = flights.filter((flight) => now - flight.startedAt < duration)
  return {
    tokens: inFlight.map((flight) => ({ edgeId: flight.edgeId, progress: Math.max(0, (now - flight.startedAt) / duration) })),
    active: new Set(inFlight.map((flight) => flight.edgeId)),
  }
}

