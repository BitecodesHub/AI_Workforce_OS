import type { Board, BoardGoal, BoardQueueEntry, BoardTask, GoalSource, QueueReason } from '../../lib/queries'
import { isGoalActive } from '../../lib/queries'

/*
 * Pure geometry and grouping for the orchestrator board.
 *
 * Where a node sits on the flow map, how a timeline bar is placed inside its two-hour window, and
 * which column a goal's card belongs in are all worked out here, with nothing touching the DOM,
 * so every rule can be checked directly rather than by reading pixels off a rendered page.
 */

export const FLOW_VIEWBOX = { width: 640, height: 340 } as const
export const HUB = { x: 320, y: 176 } as const
export const RING_RADIUS = 128

export type NodePoint = { x: number; y: number }

/** Evenly spaced points around the hub, starting at the top and going clockwise. */
export function ringLayout(count: number): NodePoint[] {
  if (count <= 0) return []
  return Array.from({ length: count }, (_, index) => {
    const angle = (2 * Math.PI * index) / count - Math.PI / 2
    return { x: HUB.x + RING_RADIUS * Math.cos(angle), y: HUB.y + RING_RADIUS * Math.sin(angle) }
  })
}

/** A curved path between two points, bowed so that edges sharing an endpoint never overlap. */
export function edgePath(from: NodePoint, to: NodePoint): string {
  const midX = (from.x + to.x) / 2
  const midY = (from.y + to.y) / 2
  const dx = to.x - from.x
  const dy = to.y - from.y
  const length = Math.hypot(dx, dy) || 1
  const bow = Math.min(28, length * 0.18)
  const nx = -dy / length
  const ny = dx / length
  const controlX = midX + nx * bow
  const controlY = midY + ny * bow
  return `M ${from.x.toFixed(1)} ${from.y.toFixed(1)} Q ${controlX.toFixed(1)} ${controlY.toFixed(1)} ${to.x.toFixed(1)} ${to.y.toFixed(1)}`
}

export type FlowEdge = {
  key: string
  goalId: string
  /** Null for the first edge of a chain: the coordinator hub, not another agent. */
  fromAgentId: string | null
  toAgentId: string
  active: boolean
  completed: boolean
}

/**
 * Hub-to-first-agent, then agent-to-agent handoffs, for every goal still active. A goal already
 * finished (shown on the board only because it finished in the last two hours) draws nothing: its
 * work is done, and the map is about what is moving, not a history of everything that ever ran.
 */
export function flowEdges(goals: readonly BoardGoal[]): FlowEdge[] {
  const edges: FlowEdge[] = []
  for (const goal of goals) {
    if (!isGoalActive(goal)) continue
    const chain = [...goal.tasks].sort((a, b) => a.position - b.position).filter((task) => task.agentId)
    chain.forEach((task, index) => {
      const fromAgentId = index === 0 ? null : chain[index - 1]!.agentId
      const status = task.status.toLowerCase()
      edges.push({
        key: `${goal.id}:${task.id}`,
        goalId: goal.id,
        fromAgentId,
        toAgentId: task.agentId!,
        active: status === 'running' || status === 'waiting_approval',
        completed: status === 'completed',
      })
    })
  }
  return edges
}

/* ---- Swimlanes ----------------------------------------------------------------------------------- */

export const SWIMLANE_WINDOW_MS = 2 * 60 * 60 * 1000
const TICK_STEP_MS = 15 * 60 * 1000

export type TimeWindow = { start: number; end: number }

/** The last two hours, ending now. */
export function laneWindow(nowMs: number): TimeWindow {
  return { start: nowMs - SWIMLANE_WINDOW_MS, end: nowMs }
}

/**
 * Where a run's bar sits in its lane, as a left offset and width in percent, clamped to the
 * window. A bar is never thinner than the eye can find, even for a run that only just started.
 */
export function laneBarRect(
  entry: { startedAt: string; completedAt: string | null },
  window: TimeWindow,
): { leftPct: number; widthPct: number } {
  const total = Math.max(window.end - window.start, 1)
  const rawStart = Date.parse(entry.startedAt)
  const rawEnd = entry.completedAt ? Date.parse(entry.completedAt) : window.end
  const start = Math.min(Math.max(rawStart, window.start), window.end)
  const end = Math.min(Math.max(rawEnd, start), window.end)
  const leftPct = ((start - window.start) / total) * 100
  const widthPct = Math.max(((end - start) / total) * 100, 0.8)
  return { leftPct, widthPct }
}

/** Tick marks every 15 minutes across the window, as timestamps to format in the local timezone. */
export function laneTicks(window: TimeWindow): number[] {
  const ticks: number[] = []
  const first = Math.ceil(window.start / TICK_STEP_MS) * TICK_STEP_MS
  for (let tick = first; tick <= window.end; tick += TICK_STEP_MS) ticks.push(tick)
  return ticks
}

/** Where a moment sits across the window, in percent from the left. */
export function pctOf(atMs: number, window: TimeWindow): number {
  const total = Math.max(window.end - window.start, 1)
  return ((atMs - window.start) / total) * 100
}

/* ---- Board columns -------------------------------------------------------------------------------- */

export type BoardColumn = 'queued' | 'working' | 'waiting' | 'finished'

export type BoardCard = {
  id: string
  column: BoardColumn
  goal: BoardGoal
  task: BoardTask | null
  reason: QueueReason | null
  /** This task's rank (0-based) in the workspace's one shared claim queue, not its step within its
   * own goal - see the comment in {@link buildBoardCards}. Null once a task is running, waiting on
   * approval, finished, or has not been queued by the engine's own sweep yet. */
  queuePosition: number | null
}

const FINISHED_GOAL = new Set(['completed', 'failed', 'cancelled'])

/**
 * One card per goal, sorted into the column its current task is in: waiting beats working beats
 * finished, so a goal never shows as two things at once. A goal not yet claimed reads as queued -
 * with the real reason and position once the board's own queue lists it, or as simply "ready" in
 * the moment right after it was created and before the next sweep has queued it.
 */
export function buildBoardCards(board: Pick<Board, 'goals' | 'queue'>): BoardCard[] {
  const queueByGoal = new Map<string, BoardQueueEntry[]>()
  // The board's own `position` field is the task's step within its own goal (0, 1, 2…), the same
  // number every goal's own first task carries - not how far back this task sits in the workspace's
  // one shared queue. `board.queue` itself is already in claim-candidate order (the engine's own
  // oldest-first fetch), so an entry's index in that array is its real place in line.
  const rankByTask = new Map<string, number>()
  board.queue.forEach((entry, index) => {
    rankByTask.set(entry.taskId, index)
    const list = queueByGoal.get(entry.goalId)
    if (list) list.push(entry)
    else queueByGoal.set(entry.goalId, [entry])
  })

  const cards: BoardCard[] = []
  for (const goal of board.goals) {
    const tasks = [...goal.tasks].sort((a, b) => a.position - b.position)
    const waitingTask = tasks.find((task) => task.status.toLowerCase() === 'waiting_approval')
    const runningTask = tasks.find((task) => task.status.toLowerCase() === 'running')
    const queueEntries = queueByGoal.get(goal.id) ?? []

    if (waitingTask) {
      cards.push({ id: `${goal.id}:waiting`, column: 'waiting', goal, task: waitingTask, reason: null, queuePosition: null })
    } else if (runningTask) {
      cards.push({ id: `${goal.id}:working`, column: 'working', goal, task: runningTask, reason: null, queuePosition: null })
    } else if (FINISHED_GOAL.has(goal.status.toLowerCase())) {
      const lastTask = tasks[tasks.length - 1] ?? null
      cards.push({ id: `${goal.id}:finished`, column: 'finished', goal, task: lastTask, reason: null, queuePosition: null })
    } else if (queueEntries.length > 0) {
      for (const entry of queueEntries) {
        const task = tasks.find((candidate) => candidate.id === entry.taskId) ?? null
        cards.push({
          id: `${goal.id}:${entry.taskId}`,
          column: 'queued',
          goal,
          task,
          reason: entry.reason,
          queuePosition: rankByTask.get(entry.taskId) ?? null,
        })
      }
    } else {
      const next = tasks.find((task) => ['pending', 'ready'].includes(task.status.toLowerCase()))
      if (next) {
        cards.push({ id: `${goal.id}:${next.id}`, column: 'queued', goal, task: next, reason: 'ready', queuePosition: null })
      }
    }
  }
  return cards
}

/** 'Step 2 of 3', from the task's place in the goal's own chain, not its raw stored position. */
export function stepLabel(goal: Pick<BoardGoal, 'tasks'>, task: { id: string } | null): string {
  const total = goal.tasks.length
  if (!task || total === 0) return ''
  const ordered = [...goal.tasks].sort((a, b) => a.position - b.position)
  const index = ordered.findIndex((candidate) => candidate.id === task.id)
  return index < 0 ? '' : `Step ${index + 1} of ${total}`
}

const QUEUE_REASON_TEXT: Record<QueueReason, string> = {
  ready: 'Ready to start',
  waiting_on_earlier_task: 'Waiting on an earlier step',
  agent_paused: 'Held: agent paused',
}

/** The plain-English reason a queued task has not started yet. */
export function queueReasonText(reason: QueueReason): string {
  return QUEUE_REASON_TEXT[reason]
}

export const COLUMN_TITLE: Record<BoardColumn, string> = {
  queued: 'Queued',
  working: 'Working',
  waiting: 'Waiting for approval',
  finished: 'Finished (last 2 h)',
}

export type { GoalSource }
