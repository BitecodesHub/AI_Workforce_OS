// @find: orchestrator layout, board cards, columns, queued working needs you finished, flow map geometry, ring layout, flow edges, swimlane window, time ticks, card status, goal task grouping, summary tile notes, queue reason, buildBoardCards, countCards
// @what: Pure helpers that group goals and tasks into board cards and columns and compute flow map and swimlane geometry.
// @flow: Used by Board, BoardList, FlowMap, Swimlanes, SummaryStrip and boardChanges
import type {
  Board,
  BoardGoal,
  BoardQueueEntry,
  BoardTask,
  BoardWindow,
  GoalSource,
  QueueReason,
} from '../../lib/queries'
import { isGoalActive } from '../../lib/queries'

/*
 * Pure geometry and grouping for the orchestrator board.
 *
 * Where a node sits on the flow map, how a timeline bar is placed inside its window, and which
 * column a goal's card belongs in are all worked out here, with nothing touching the DOM, so every
 * rule can be checked directly rather than by reading pixels off a rendered page.
 */

/* Tall enough that a node at the very bottom of the ring keeps its label inside the canvas. */
export const FLOW_VIEWBOX = { width: 640, height: 360 } as const
export const HUB = { x: 320, y: 172 } as const
export const RING_RADIUS = 128

export type NodePoint = { x: number; y: number }

/** Evenly spaced points around the hub, starting at the top and going clockwise. */
// @find: ring layout, place agent nodes around hub, flow map positions
export function ringLayout(count: number): NodePoint[] {
  if (count <= 0) return []
  return Array.from({ length: count }, (_, index) => {
    const angle = (2 * Math.PI * index) / count - Math.PI / 2
    return { x: HUB.x + RING_RADIUS * Math.cos(angle), y: HUB.y + RING_RADIUS * Math.sin(angle) }
  })
}

/**
 * How many characters an agent's flow-map label keeps before it is cut short with an ellipsis.
 * The ring divides the same space among every agent on it, so the more of them there are, the
 * less room each one's label gets before its neighbour's begins - a fixed limit either wasted
 * space with few agents or clipped a name like "Customer Success" down to "Customer…" with many.
 */
export function flowLabelMax(agentCount: number): number {
  if (agentCount <= 4) return 26
  if (agentCount <= 8) return 18
  return 13
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

/** Where a tooltip anchored at this point should open, so it never clips past the canvas edge. */
export function tooltipPlacement(point: NodePoint): 'above' | 'below' {
  return point.y < 70 ? 'below' : 'above'
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

const ACTIVE_EDGE_STATUS = new Set(['running', 'waiting_approval', 'waiting_input'])

/**
 * Hub-to-first-agent, then agent-to-agent handoffs, for every goal still active. A goal already
 * finished (shown on the board only because it finished inside the window) draws nothing: its
 * work is done, and the map is about what is moving, not a history of everything that ever ran.
 */
// @find: flow edges, goal to agent links, who hands work to whom
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
        active: ACTIVE_EDGE_STATUS.has(status),
        completed: status === 'completed',
      })
    })
  }
  return edges
}

/* ---- Swimlanes ----------------------------------------------------------------------------------- */

export const SWIMLANE_WINDOW_MINUTES = 120
const DEFAULT_TICK_STEP_MS = 15 * 60 * 1000

export type TimeWindow = { start: number; end: number }

/** The window ending now, `windowMinutes` long (two hours unless the caller asks for another). */
export function laneWindow(nowMs: number, windowMinutes: number = SWIMLANE_WINDOW_MINUTES): TimeWindow {
  return { start: nowMs - windowMinutes * 60 * 1000, end: nowMs }
}

/**
 * Where a run's bar sits in its lane, as a left offset and width in percent, clamped to the
 * window. A bar is never thinner than the eye can find, even for a run that only just started.
 */
// @find: swimlane bar rectangle, run bar position in lane
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

/** Tick marks across the window, `stepMs` apart (15 minutes unless the caller asks for another). */
export function laneTicks(window: TimeWindow, stepMs: number = DEFAULT_TICK_STEP_MS): number[] {
  const ticks: number[] = []
  const first = Math.ceil(window.start / stepMs) * stepMs
  for (let tick = first; tick <= window.end; tick += stepMs) ticks.push(tick)
  return ticks
}

/**
 * How far apart the timeline's tick marks should be, so a 24-hour or "today" window is not asked
 * to draw a tick every 15 minutes: 15 min for 1 h and 2 h, 1 h for 6 h, 3 h for 24 h and today.
 */
export function tickStepFor(window: BoardWindow): number {
  switch (window) {
    case 'PT1H':
    case 'PT2H':
      return 15 * 60 * 1000
    case 'PT6H':
      return 60 * 60 * 1000
    default:
      return 3 * 60 * 60 * 1000
  }
}

/** Where a moment sits across the window, in percent from the left. */
export function pctOf(atMs: number, window: TimeWindow): number {
  const total = Math.max(window.end - window.start, 1)
  return ((atMs - window.start) / total) * 100
}

/* ---- Board columns -------------------------------------------------------------------------------- */

export type BoardColumn = 'queued' | 'working' | 'needs_you' | 'finished'
export type CardStatusKey = 'queued' | 'held' | 'working' | 'needs_you' | 'finished' | 'failed'

export type BoardCard = {
  /** The goal's own id: one card per goal, so the sheet stays keyed to the same card through a
      column move instead of losing it the moment the goal's task changes state. */
  id: string
  column: BoardColumn
  statusKey: CardStatusKey
  waitingKind: 'answer' | 'approval' | null
  goal: BoardGoal
  task: BoardTask | null
  reason: QueueReason | null
  /** This task's rank (0-based) in the workspace's one shared claim queue, not its step within its
   * own goal - see the comment below. Null once a task is running, waiting, finished, or has not
   * been queued by the engine's own sweep yet. */
  queuePosition: number | null
}

const FINISHED_GOAL = new Set(['completed', 'failed', 'cancelled'])
const WAITING_TASK = new Set(['waiting_input', 'waiting_approval'])

function endingTask(tasks: readonly BoardTask[]): BoardTask | null {
  const stoppedEarly = tasks.find((task) => task.status.toLowerCase() === 'failed' || task.status.toLowerCase() === 'cancelled')
  if (stoppedEarly) return stoppedEarly
  const lastCompleted = [...tasks].reverse().find((task) => task.status.toLowerCase() === 'completed')
  if (lastCompleted) return lastCompleted
  return tasks[tasks.length - 1] ?? null
}

/**
 * One card per goal (D1), sorted into the column its state belongs in: needing a person beats
 * working beats finished beats queued, so a goal never shows as two things at once.
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
    const waitingTask = tasks.find((task) => WAITING_TASK.has(task.status.toLowerCase()))
    if (waitingTask) {
      const waitingKind = waitingTask.status.toLowerCase() === 'waiting_input' ? 'answer' : 'approval'
      cards.push({
        id: goal.id,
        column: 'needs_you',
        statusKey: 'needs_you',
        waitingKind,
        goal,
        task: waitingTask,
        reason: null,
        queuePosition: null,
      })
      continue
    }

    const runningTask = tasks.find((task) => task.status.toLowerCase() === 'running')
    if (runningTask) {
      cards.push({
        id: goal.id,
        column: 'working',
        statusKey: 'working',
        waitingKind: null,
        goal,
        task: runningTask,
        reason: null,
        queuePosition: null,
      })
      continue
    }

    if (FINISHED_GOAL.has(goal.status.toLowerCase())) {
      cards.push({
        id: goal.id,
        column: 'finished',
        statusKey: goal.status.toLowerCase() === 'failed' ? 'failed' : 'finished',
        waitingKind: null,
        goal,
        task: endingTask(tasks),
        reason: null,
        queuePosition: null,
      })
      continue
    }

    const queueEntries = queueByGoal.get(goal.id) ?? []
    if (queueEntries.length > 0) {
      const best = queueEntries.reduce((lowest, candidate) =>
        (rankByTask.get(candidate.taskId) ?? Infinity) < (rankByTask.get(lowest.taskId) ?? Infinity) ? candidate : lowest,
      )
      cards.push({
        id: goal.id,
        column: 'queued',
        statusKey: best.reason === 'agent_paused' ? 'held' : 'queued',
        waitingKind: null,
        goal,
        task: tasks.find((candidate) => candidate.id === best.taskId) ?? null,
        reason: best.reason,
        queuePosition: rankByTask.get(best.taskId) ?? null,
      })
      continue
    }

    // A brand-new goal, created after the last sweep queued anything: read as ready rather than
    // left off the board until the next sweep catches up.
    const next = tasks.find((task) => task.status.toLowerCase() === 'pending' || task.status.toLowerCase() === 'ready')
    cards.push({
      id: goal.id,
      column: 'queued',
      statusKey: 'queued',
      waitingKind: null,
      goal,
      task: next ?? null,
      reason: 'ready',
      queuePosition: null,
    })
  }
  return cards
}

/** How many of these cards carry this status key - what a SummaryStrip tile's click filters to. */
export function countCards(cards: readonly BoardCard[], statusKey: CardStatusKey): number {
  return cards.reduce((count, card) => count + (card.statusKey === statusKey ? 1 : 0), 0)
}

/* ---- Summary tile notes: a note only ever explains a figure above zero, so a "0" tile never
   reads as if something were paused or waiting. ------------------------------------------------ */

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`
}

/** "2 questions · 1 approval" under Needs you, or null when neither is waiting. */
export function waitingTileNote(questions: number, approvals: number): string | null {
  const parts = [questions > 0 ? plural(questions, 'question') : null, approvals > 0 ? plural(approvals, 'approval') : null]
  const note = parts.filter(Boolean).join(' · ')
  return note || null
}

/** "Agent paused" under Held, only when something is actually held. */
export function heldTileNote(held: number): string | null {
  return held > 0 ? 'Agent paused' : null
}

/** "and 3 direct runs" under Working now, only when there are any. */
export function directRunsTileNote(directRuns: number): string | null {
  return directRuns > 0 ? `and ${plural(directRuns, 'direct run')}` : null
}

/** 'Step 2 of 3', from the task's place in the goal's own chain, not its raw stored position. */
export function stepLabel(goal: Pick<BoardGoal, 'tasks'>, task: { id: string } | null): string {
  const total = goal.tasks.length
  if (!task || total === 0) return ''
  const ordered = [...goal.tasks].sort((a, b) => a.position - b.position)
  const index = ordered.findIndex((candidate) => candidate.id === task.id)
  return index < 0 ? '' : `Step ${index + 1} of ${total}`
}

/** Whether a card shows its step progress: only a goal of more than one step has any to show. */
export function showsStepProgress(goal: Pick<BoardGoal, 'tasks'>): boolean {
  return goal.tasks.length > 1
}

/**
 * The single meta line under a card's title - "HR · From Chat · You · 172 ms" - with blank parts
 * dropped, and a requester left out when it only repeats the source (a scheduled goal would
 * otherwise read "Scheduled · Scheduled").
 */
export function cardMetaParts(parts: {
  agent: string | null
  source: string
  requester: string
  timing: string
  cost?: string | null
}): string[] {
  const out: string[] = []
  if (parts.agent) out.push(parts.agent)
  if (parts.source) out.push(parts.source)
  if (parts.requester && parts.requester !== parts.source) out.push(parts.requester)
  if (parts.timing) out.push(parts.timing)
  if (parts.cost) out.push(parts.cost)
  return out
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

const COLUMN_LABEL: Record<BoardColumn, string> = {
  queued: 'Queued',
  working: 'Working',
  needs_you: 'Needs you',
  finished: 'Finished',
}

/** A column's heading, with the finished column naming the window it covers. */
export function columnTitle(column: BoardColumn, window: BoardWindow, windowMinutes: number): string {
  if (column !== 'finished') return COLUMN_LABEL[column]
  if (window === 'TODAY') return 'Finished (today)'
  const hours = Math.max(1, Math.round(windowMinutes / 60))
  return `Finished (last ${hours} h)`
}

export type { GoalSource }
