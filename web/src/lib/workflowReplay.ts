// @find: workflow replay, time-lapse, replay timeline, events from timestamps, state at time, step status, status mapping, hand-off tokens, live status, new hand-offs, goal canvas from timeline, auto layout goal, workflowReplay
// @what: Pure functions that turn a goal's timeline (task, run, approval and question timestamps) into replay events, the state of every step at any moment, hand-off tokens, and the canvas a goal is drawn on.
// @flow: Used by routes/WorkflowRun.tsx and components/workflows/ReplayPlayer.tsx; reads the GET /api/goals/{id}/timeline shape from lib/workflowQueries
import type { GoalTimeline, TimelineTask } from './workflowQueries'
import { layeredLayout, normaliseGraph } from './workflowGraph'
import type { WorkflowGraph, WorkflowNode } from './workflowGraph'

/*
 * A replay is worked out entirely from timestamps the platform already keeps: when each task's
 * run started and ended, when it asked for an approval or an answer and when that was settled.
 * Nothing is recorded for the replay itself, so any finished goal can be replayed, including ones
 * that ran before workflows existed.
 */

/** What a step on the canvas is doing, in the canvas's own words. */
export type StepStatus = 'idle' | 'queued' | 'running' | 'approval' | 'question' | 'completed' | 'failed' | 'cancelled' | 'skipped'

/** Plain words for each step status, shown on the node and read out by a screen reader. */
export const STEP_STATUS_LABEL: Record<StepStatus, string> = {
  idle: 'Not started',
  queued: 'Queued',
  running: 'Running',
  approval: 'Waiting for approval',
  question: 'Waiting for an answer',
  completed: 'Completed',
  failed: 'Failed',
  cancelled: 'Cancelled',
  skipped: 'Skipped',
}

// @find: task status to step status, status mapping
/** The canvas status for a task status from the server. Unknown values read as queued. */
export function stepStatusOf(taskStatus: string | null | undefined): StepStatus {
  switch ((taskStatus ?? '').toLowerCase()) {
    case 'running':
      return 'running'
    case 'waiting_approval':
      return 'approval'
    case 'waiting_input':
      return 'question'
    case 'completed':
      return 'completed'
    case 'failed':
      return 'failed'
    case 'cancelled':
      return 'cancelled'
    case 'skipped':
      return 'skipped'
    default:
      return 'queued'
  }
}

/** Whether a step status is final. */
export function isSettled(status: StepStatus): boolean {
  return status === 'completed' || status === 'failed' || status === 'cancelled' || status === 'skipped'
}

/** One moment in a replay. */
export type ReplayEvent = {
  /** Milliseconds since the epoch. */
  at: number
  taskId: string
  kind: 'started' | 'waiting' | 'resumed' | 'retrying' | 'finished' | 'handoff'
  /** The step's status from this moment; absent for a hand-off, which changes no status. */
  status?: StepStatus
  /** For a hand-off: the step that handed over. */
  fromTaskId?: string
}

export type Replay = { start: number; end: number; events: ReplayEvent[] }

/** An ISO timestamp as milliseconds since the epoch, or null when absent or unreadable. */
const time = (iso: string | null | undefined): number | null => {
  if (!iso) return null
  const ms = Date.parse(iso)
  return Number.isNaN(ms) ? null : ms
}

/**
 * Order of kinds at the same instant, which is a step's own lifecycle order: a hand-off arrives
 * before the start it causes, and a step that starts and finishes within one millisecond still
 * ends finished.
 */
const KIND_ORDER: Record<ReplayEvent['kind'], number> = {
  handoff: 0,
  started: 1,
  waiting: 2,
  resumed: 3,
  retrying: 4,
  finished: 5,
}

// @find: build replay from timeline, replay timeline builder
/**
 * Every event of a goal's life, in time order.
 *
 * Per task: each run's start; each wait for an approval or answer and its end; between attempts,
 * a retry; and the finish, at the task's completion time with its final status. A task that never
 * ran but ended (skipped, cancelled) finishes when the goal did. When a task starts, every step
 * that handed over to it and had finished by then hands over. The replay runs from the goal's
 * creation to its completion, or to the server's "now" while it is still going.
 */
export function buildReplay(timeline: GoalTimeline): Replay {
  const events: ReplayEvent[] = []
  const runsByTask = new Map<string, GoalTimeline['runs']>()
  for (const run of timeline.runs) {
    if (!run.taskId) continue
    runsByTask.set(run.taskId, [...(runsByTask.get(run.taskId) ?? []), run])
  }
  const goalStart = time(timeline.createdAt)
  const goalEnd = time(timeline.completedAt)
  const finishedAt = new Map<string, number>()

  for (const task of timeline.tasks) {
    const runs = [...(runsByTask.get(task.id) ?? [])].sort((a, b) => (time(a.startedAt) ?? 0) - (time(b.startedAt) ?? 0))
    const final = stepStatusOf(task.status)
    if (runs.length === 0) {
      const started = time(task.startedAt)
      if (started !== null) events.push({ at: started, taskId: task.id, kind: 'started', status: 'running' })
      if (isSettled(final)) {
        const ended = time(task.completedAt) ?? goalEnd ?? started
        if (ended !== null) {
          events.push({ at: ended, taskId: task.id, kind: 'finished', status: final })
          if (final === 'completed') finishedAt.set(task.id, ended)
        }
      }
      continue
    }
    runs.forEach((run, index) => {
      const started = time(run.startedAt)
      if (started !== null) events.push({ at: started, taskId: task.id, kind: 'started', status: 'running' })
      const last = index === runs.length - 1
      const ended = time(run.completedAt)
      if (!last && ended !== null) events.push({ at: ended, taskId: task.id, kind: 'retrying', status: 'queued' })
    })
    const taskEnd = isSettled(final) ? (time(task.completedAt) ?? time(runs[runs.length - 1]!.completedAt) ?? goalEnd) : null
    for (const wait of timeline.waits) {
      if (wait.taskId !== task.id && !runs.some((run) => run.id === wait.runId)) continue
      const from = time(wait.startedAt)
      if (from === null) continue
      events.push({ at: from, taskId: task.id, kind: 'waiting', status: wait.kind === 'question' ? 'question' : 'approval' })
      const until = time(wait.endedAt)
      // A wait settled as the step ended (a refusal that failed it) hands straight to the finish.
      if (until !== null && (taskEnd === null || until < taskEnd)) {
        events.push({ at: until, taskId: task.id, kind: 'resumed', status: 'running' })
      }
    }
    if (taskEnd !== null) {
      events.push({ at: taskEnd, taskId: task.id, kind: 'finished', status: final })
      if (final === 'completed') finishedAt.set(task.id, taskEnd)
    }
  }

  // Hand-offs: when a step first starts, each predecessor that had completed by then hands over
  // its result. A step that failed or was skipped hands nothing on.
  const before = dependencies(timeline.tasks)
  for (const task of timeline.tasks) {
    const firstStart = events
      .filter((event) => event.taskId === task.id && event.kind === 'started')
      .reduce<number | null>((min, event) => (min === null || event.at < min ? event.at : min), null)
    if (firstStart === null) continue
    for (const fromId of before.get(task.id) ?? []) {
      const done = finishedAt.get(fromId)
      if (done !== undefined && done <= firstStart) {
        events.push({ at: firstStart, taskId: task.id, kind: 'handoff', fromTaskId: fromId })
      }
    }
  }

  events.sort((a, b) => a.at - b.at || KIND_ORDER[a.kind] - KIND_ORDER[b.kind])
  const first = events[0]?.at ?? goalStart ?? 0
  const start = goalStart !== null ? Math.min(goalStart, first) : first
  const lastEvent = events[events.length - 1]?.at ?? start
  const now = time(timeline.now)
  const end = Math.max(lastEvent, goalEnd ?? (isGoalSettled(timeline.status) ? lastEvent : (now ?? lastEvent)), start)
  return { start, end, events }
}

/** Whether a goal status is final. */
export function isGoalSettled(status: string): boolean {
  return status === 'completed' || status === 'failed' || status === 'cancelled'
}

/**
 * What each task waits for. A workflow's task (explicitDependencies) waits for exactly the tasks
 * it names, so one that names none starts at once. Any other task with no dependencies follows the
 * old rule chat chains use: it waits for the task just before it. (The server waits for every
 * earlier one; drawing only the nearest keeps the picture a chain rather than a fan.)
 */
export function dependencies(tasks: readonly TimelineTask[]): Map<string, string[]> {
  const byPosition = [...tasks].sort((a, b) => a.position - b.position)
  const known = new Set(tasks.map((task) => task.id))
  const before = new Map<string, string[]>()
  byPosition.forEach((task, index) => {
    const explicit = task.dependsOn.filter((id) => known.has(id))
    if (explicit.length > 0 || task.explicitDependencies) before.set(task.id, explicit)
    else if (index > 0 && task.dependsOn.length === 0) before.set(task.id, [byPosition[index - 1]!.id])
    else before.set(task.id, [])
  })
  return before
}

// @find: state at time, replay state, status at scrubbed time
/** The status of every task at a moment of the replay: queued until its first event, then whatever the latest event says. */
export function stateAt(replay: Replay, taskIds: readonly string[], at: number): Map<string, StepStatus> {
  const state = new Map<string, StepStatus>(taskIds.map((id) => [id, 'queued']))
  for (const event of replay.events) {
    if (event.at > at) break
    if (event.status) state.set(event.taskId, event.status)
  }
  return state
}

/** One token on its way along a connection. */
export type Handoff = { fromTaskId: string; toTaskId: string; progress: number }

// @find: hand-off tokens at time, token travelling along edge
/** Hand-offs under way at a moment: each travels for `travelMs` of replay time after it happens. */
export function handoffsAt(replay: Replay, at: number, travelMs: number): Handoff[] {
  if (travelMs <= 0) return []
  return replay.events
    .filter((event) => event.kind === 'handoff' && event.fromTaskId && event.at <= at && at < event.at + travelMs)
    .map((event) => ({ fromTaskId: event.fromTaskId!, toTaskId: event.taskId, progress: (at - event.at) / travelMs }))
}

/** The time of the next event after a moment, or the end. */
export function nextEventTime(replay: Replay, at: number): number {
  return replay.events.find((event) => event.at > at)?.at ?? replay.end
}

/** The time of the last event before a moment, or the start. */
export function previousEventTime(replay: Replay, at: number): number {
  let previous = replay.start
  for (const event of replay.events) {
    if (event.at >= at) break
    previous = event.at
  }
  return previous
}

/** Live state: each task's status as the server reports it now. */
export function liveStates(timeline: GoalTimeline): Map<string, StepStatus> {
  return new Map(timeline.tasks.map((task) => [task.id, stepStatusOf(task.status)]))
}

// @find: new hand-offs between polls, live token animation
/**
 * The hand-offs that happened between two polls: a task that was queued and is now under way (or
 * done) receives from each predecessor that has completed. Nothing on the first poll, so opening
 * the page does not replay old hand-offs.
 */
export function newHandoffs(
  previous: ReadonlyMap<string, StepStatus> | null,
  next: ReadonlyMap<string, StepStatus>,
  before: ReadonlyMap<string, readonly string[]>,
): Array<{ fromTaskId: string; toTaskId: string }> {
  if (!previous) return []
  const found: Array<{ fromTaskId: string; toTaskId: string }> = []
  for (const [taskId, status] of next) {
    const was = previous.get(taskId)
    if (was !== 'queued' || status === 'queued' || status === 'skipped' || status === 'cancelled') continue
    for (const fromTaskId of before.get(taskId) ?? []) {
      if (next.get(fromTaskId) === 'completed') found.push({ fromTaskId, toTaskId: taskId })
    }
  }
  return found
}

/** A goal drawn as a canvas: the graph, and which node each task is. */
export type GoalCanvas = { graph: WorkflowGraph; nodeOfTask: Map<string, string>; taskOfNode: Map<string, string> }

// @find: canvas for a goal, workflow snapshot or auto layout
/**
 * The canvas a goal is drawn on. A goal a workflow started uses the copy of the canvas it ran,
 * each task matched to its node by position. Any other goal is laid out in columns by its
 * dependencies, one node per task.
 */
export function goalCanvas(timeline: GoalTimeline): GoalCanvas {
  const nodeOfTask = new Map<string, string>()
  const taskOfNode = new Map<string, string>()
  if (timeline.workflow) {
    const graph = normaliseGraph(timeline.workflow.graph)
    const ids = new Set(graph.nodes.map((node) => node.id))
    for (const task of timeline.tasks) {
      const nodeId = timeline.workflow.nodeIds[task.position]
      if (nodeId && ids.has(nodeId)) {
        nodeOfTask.set(task.id, nodeId)
        taskOfNode.set(nodeId, task.id)
      }
    }
    if (nodeOfTask.size === timeline.tasks.length) return { graph, nodeOfTask, taskOfNode }
    nodeOfTask.clear()
    taskOfNode.clear()
  }
  const ordered = [...timeline.tasks].sort((a, b) => a.position - b.position)
  const before = dependencies(ordered)
  const positions = layeredLayout(
    ordered.map((task) => task.id),
    before,
  )
  const nodes: WorkflowNode[] = ordered.map((task) => ({
    id: task.id,
    type: 'agent',
    agentId: task.agentId,
    title: task.title,
    instruction: '',
    connectors: [],
    x: positions.get(task.id)!.x,
    y: positions.get(task.id)!.y,
  }))
  const edges = ordered.flatMap((task) =>
    (before.get(task.id) ?? []).map((from) => ({ id: `e-${from}-${task.id}`, from, to: task.id })),
  )
  for (const task of ordered) {
    nodeOfTask.set(task.id, task.id)
    taskOfNode.set(task.id, task.id)
  }
  return { graph: { nodes, edges }, nodeOfTask, taskOfNode }
}

/**
 * A step's name ready to sit inside a sentence: a title without one runs on into its instruction,
 * which usually ends in a full stop, and "…one sentence each.: completed" was seen live on 13 Oct
 * 2026, so the closing punctuation goes.
 */
function inSentence(title: string): string {
  return title.replace(/[\s.!?;:,]+$/u, '')
}

/** The event's sentence for the replay log: "Research started", "Draft is waiting for approval". */
export function eventSentence(event: ReplayEvent, name: (taskId: string) => string): string {
  const who = inSentence(name(event.taskId))
  switch (event.kind) {
    case 'started':
      return `${who} started`
    case 'waiting':
      return event.status === 'question' ? `${who} is waiting for an answer` : `${who} is waiting for approval`
    case 'resumed':
      return `${who} carried on`
    case 'retrying':
      return `${who} will try again`
    case 'handoff':
      return `${inSentence(name(event.fromTaskId ?? ''))} handed over to ${who}`
    case 'finished':
      return `${who}: ${STEP_STATUS_LABEL[event.status ?? 'completed'].toLowerCase()}`
  }
}
