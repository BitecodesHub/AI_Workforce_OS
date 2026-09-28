import { describe, expect, it } from 'vitest'
import {
  buildBoardCards,
  edgePath,
  flowEdges,
  HUB,
  laneBarRect,
  laneTicks,
  laneWindow,
  pctOf,
  queueReasonText,
  ringLayout,
  stepLabel,
} from './layout'
import type { BoardGoal, BoardQueueEntry, BoardTask } from '../../lib/queries'

function task(overrides: Partial<BoardTask> & { id: string }): BoardTask {
  return {
    agentId: 'agent-1',
    title: 'Task',
    status: 'pending',
    position: 0,
    dependsOn: [],
    attempt: 1,
    maxAttempts: 2,
    result: null,
    failureReason: null,
    startedAt: null,
    completedAt: null,
    runId: null,
    runStatus: null,
    stepCount: null,
    cost: null,
    ...overrides,
  }
}

function goal(overrides: Partial<BoardGoal> & { id: string; tasks: BoardTask[] }): BoardGoal {
  return {
    title: 'Goal',
    description: '',
    status: 'running',
    createdAt: '2026-09-28T00:00:00Z',
    completedAt: null,
    source: 'manual',
    requestedBy: null,
    conversationId: null,
    scheduleId: null,
    ...overrides,
  }
}

describe('ringLayout', () => {
  it('returns nothing for zero agents', () => {
    expect(ringLayout(0)).toEqual([])
  })

  it('places the first agent directly above the hub', () => {
    const [first] = ringLayout(4)
    expect(first!.x).toBeCloseTo(HUB.x, 5)
    expect(first!.y).toBeLessThan(HUB.y)
  })

  it('spaces every agent the same distance from the hub', () => {
    const points = ringLayout(5)
    for (const point of points) {
      const distance = Math.hypot(point.x - HUB.x, point.y - HUB.y)
      expect(distance).toBeCloseTo(128, 1)
    }
  })
})

describe('edgePath', () => {
  it('starts and ends at the given points', () => {
    const d = edgePath({ x: 0, y: 0 }, { x: 100, y: 0 })
    expect(d.startsWith('M 0.0 0.0')).toBe(true)
    expect(d.endsWith('100.0 0.0')).toBe(true)
  })

  it('bows the curve away from a straight line', () => {
    const d = edgePath({ x: 0, y: 0 }, { x: 100, y: 0 })
    const controlY = Number(d.split(' ')[4])
    expect(controlY).not.toBe(0)
  })
})

describe('flowEdges', () => {
  it('draws hub-to-first and then agent-to-agent for an active chain', () => {
    const g = goal({
      id: 'g1',
      status: 'running',
      tasks: [
        task({ id: 't1', agentId: 'hr', position: 0, status: 'completed' }),
        task({ id: 't2', agentId: 'support', position: 1, status: 'running' }),
      ],
    })
    const edges = flowEdges([g])
    expect(edges).toHaveLength(2)
    expect(edges[0]).toMatchObject({ fromAgentId: null, toAgentId: 'hr', completed: true, active: false })
    expect(edges[1]).toMatchObject({ fromAgentId: 'hr', toAgentId: 'support', active: true })
  })

  it('skips a goal that has already finished', () => {
    const g = goal({
      id: 'g2',
      status: 'completed',
      tasks: [task({ id: 't1', agentId: 'hr', position: 0, status: 'completed' })],
    })
    expect(flowEdges([g])).toEqual([])
  })

  it('skips a task with no agent', () => {
    const g = goal({ id: 'g3', tasks: [task({ id: 't1', agentId: null, position: 0 })] })
    expect(flowEdges([g])).toEqual([])
  })
})

describe('swimlane geometry', () => {
  const window = laneWindow(Date.parse('2026-09-28T12:00:00Z'))

  it('spans exactly two hours ending now', () => {
    expect(window.end - window.start).toBe(2 * 60 * 60 * 1000)
  })

  it('clamps a run that started before the window to its left edge', () => {
    const rect = laneBarRect({ startedAt: '2026-09-28T09:00:00Z', completedAt: '2026-09-28T10:00:00Z' }, window)
    expect(rect.leftPct).toBe(0)
  })

  it('gives a still-running bar a width up to the window end', () => {
    const rect = laneBarRect({ startedAt: '2026-09-28T11:00:00Z', completedAt: null }, window)
    expect(rect.leftPct).toBeCloseTo(50, 5)
    expect(rect.leftPct + rect.widthPct).toBeCloseTo(100, 5)
  })

  it('never shrinks a very short run to invisible', () => {
    const rect = laneBarRect({ startedAt: '2026-09-28T11:59:59.9Z', completedAt: '2026-09-28T12:00:00Z' }, window)
    expect(rect.widthPct).toBeGreaterThan(0)
  })

  it('produces a tick every fifteen minutes', () => {
    const ticks = laneTicks(window)
    expect(ticks).toHaveLength(9)
    expect(ticks[1]! - ticks[0]!).toBe(15 * 60 * 1000)
  })

  it('places "now" at the right edge', () => {
    expect(pctOf(window.end, window)).toBeCloseTo(100, 5)
  })
})

describe('buildBoardCards', () => {
  it('puts a waiting-approval task ahead of a running one for the same goal', () => {
    const g = goal({
      id: 'g1',
      tasks: [
        task({ id: 't1', position: 0, status: 'waiting_approval' }),
        task({ id: 't2', position: 1, status: 'running' }),
      ],
    })
    const cards = buildBoardCards({ goals: [g], queue: [] })
    expect(cards).toHaveLength(1)
    expect(cards[0]!.column).toBe('waiting')
  })

  it('reads a finished goal into the finished column with its last task', () => {
    const g = goal({
      id: 'g2',
      status: 'completed',
      tasks: [task({ id: 't1', position: 0, status: 'completed' }), task({ id: 't2', position: 1, status: 'completed' })],
    })
    const [card] = buildBoardCards({ goals: [g], queue: [] })
    expect(card!.column).toBe('finished')
    expect(card!.task?.id).toBe('t2')
  })

  it('makes one queued card per queue entry, carrying its reason and its rank in the shared queue', () => {
    const g = goal({ id: 'g3', status: 'planning', tasks: [task({ id: 't1', position: 0, status: 'pending' })] })
    const entry: BoardQueueEntry = {
      goalId: 'g3',
      goalTitle: 'Goal',
      taskId: 't1',
      agentId: 'agent-1',
      // The task's own step within its goal (0) must not leak through as its queue rank - a card
      // reading "#1 in the queue" for every goal's own first task, however far back it really sits,
      // would be a lie the board tells with a straight face.
      position: 0,
      reason: 'waiting_on_earlier_task',
      requestedBy: null,
      source: 'manual',
      createdAt: '2026-09-28T00:00:00Z',
    }
    const [card] = buildBoardCards({ goals: [g], queue: [entry] })
    expect(card).toMatchObject({ column: 'queued', reason: 'waiting_on_earlier_task', queuePosition: 0 })
  })

  it('ranks a queued task by its place in the one shared queue, not its own goal\'s task position', () => {
    // Two goals, each queuing a task whose own chain position is 0 - the same number the sweep
    // would report for the first task of any goal. The one actually further back in line (behind
    // an entry from another goal) must read a higher rank, however identical their own positions.
    const g1 = goal({ id: 'g1', status: 'planning', tasks: [task({ id: 't1', position: 0, status: 'pending' })] })
    const g2 = goal({ id: 'g2', status: 'planning', tasks: [task({ id: 't2', position: 0, status: 'pending' })] })
    const entryFor = (goalId: string, taskId: string): BoardQueueEntry => ({
      goalId,
      goalTitle: 'Goal',
      taskId,
      agentId: 'agent-1',
      position: 0,
      reason: 'ready',
      requestedBy: null,
      source: 'manual',
      createdAt: '2026-09-28T00:00:00Z',
    })
    const cards = buildBoardCards({ goals: [g1, g2], queue: [entryFor('g1', 't1'), entryFor('g2', 't2')] })
    const byGoal = new Map(cards.map((card) => [card.goal.id, card]))
    expect(byGoal.get('g1')!.queuePosition).toBe(0)
    expect(byGoal.get('g2')!.queuePosition).toBe(1)
  })

  it('shows a brand-new goal as ready before the sweep has queued it', () => {
    const g = goal({ id: 'g4', status: 'planning', tasks: [task({ id: 't1', position: 0, status: 'pending' })] })
    const [card] = buildBoardCards({ goals: [g], queue: [] })
    expect(card).toMatchObject({ column: 'queued', reason: 'ready', queuePosition: null })
  })
})

describe('stepLabel', () => {
  it('numbers a task by its place in the chain, not its stored position', () => {
    const g = goal({
      id: 'g1',
      tasks: [task({ id: 't1', position: 5 }), task({ id: 't2', position: 9 })],
    })
    expect(stepLabel(g, { id: 't2' })).toBe('Step 2 of 2')
  })

  it('reads empty for no task', () => {
    expect(stepLabel(goal({ id: 'g1', tasks: [] }), null)).toBe('')
  })
})

describe('queueReasonText', () => {
  it('reads every reason in plain English', () => {
    expect(queueReasonText('ready')).toBe('Ready to start')
    expect(queueReasonText('waiting_on_earlier_task')).toBe('Waiting on an earlier step')
    expect(queueReasonText('agent_paused')).toBe('Held: agent paused')
  })
})
