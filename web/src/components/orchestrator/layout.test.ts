import { describe, expect, it } from 'vitest'
import {
  FLOW_VIEWBOX,
  buildBoardCards,
  cardMetaParts,
  columnTitle,
  countCards,
  directRunsTileNote,
  heldTileNote,
  waitingTileNote,
  edgePath,
  flowEdges,
  flowLabelMax,
  HUB,
  laneBarRect,
  laneTicks,
  laneWindow,
  pctOf,
  queueReasonText,
  ringLayout,
  showsStepProgress,
  stepLabel,
  tickStepFor,
  tooltipPlacement,
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

describe('flowLabelMax', () => {
  it('gives a small ring generous room per label', () => {
    expect(flowLabelMax(3)).toBeGreaterThan(flowLabelMax(6))
  })

  it('shrinks as more agents share the ring', () => {
    expect(flowLabelMax(6)).toBeGreaterThan(flowLabelMax(12))
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

describe('tooltipPlacement', () => {
  it('opens below a node near the top of the canvas, so the tooltip is not clipped', () => {
    expect(tooltipPlacement({ x: 300, y: 40 })).toBe('below')
  })

  it('opens above a node with room above it', () => {
    expect(tooltipPlacement({ x: 300, y: 200 })).toBe('above')
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

  it('treats a task waiting for an answer as an active edge too', () => {
    const g = goal({
      id: 'g1b',
      status: 'waiting',
      tasks: [task({ id: 't1', agentId: 'hr', position: 0, status: 'waiting_input' })],
    })
    expect(flowEdges([g])[0]).toMatchObject({ active: true })
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

  it('spans exactly two hours ending now, with the old single-argument call', () => {
    expect(window.end - window.start).toBe(2 * 60 * 60 * 1000)
  })

  it('spans whatever window a caller asks for', () => {
    const sixHours = laneWindow(Date.parse('2026-09-28T12:00:00Z'), 360)
    expect(sixHours.end - sixHours.start).toBe(6 * 60 * 60 * 1000)
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

  it('produces a tick every fifteen minutes with the old single-argument call', () => {
    const ticks = laneTicks(window)
    expect(ticks).toHaveLength(9)
    expect(ticks[1]! - ticks[0]!).toBe(15 * 60 * 1000)
  })

  it('produces ticks at whatever step a caller asks for', () => {
    const ticks = laneTicks(window, 60 * 60 * 1000)
    expect(ticks[1]! - ticks[0]!).toBe(60 * 60 * 1000)
  })

  it('places "now" at the right edge', () => {
    expect(pctOf(window.end, window)).toBeCloseTo(100, 5)
  })
})

describe('tickStepFor', () => {
  it('keeps a quarter-hour step for the shorter windows', () => {
    expect(tickStepFor('PT1H')).toBe(15 * 60 * 1000)
    expect(tickStepFor('PT2H')).toBe(15 * 60 * 1000)
  })

  it('widens to an hour for six hours', () => {
    expect(tickStepFor('PT6H')).toBe(60 * 60 * 1000)
  })

  it('widens to three hours for a full day and for today', () => {
    expect(tickStepFor('PT24H')).toBe(3 * 60 * 60 * 1000)
    expect(tickStepFor('TODAY')).toBe(3 * 60 * 60 * 1000)
  })
})

describe('buildBoardCards', () => {
  it('makes exactly one card per goal, keyed by the goal id (D1)', () => {
    const g = goal({
      id: 'g1',
      tasks: [
        task({ id: 't1', position: 0, status: 'waiting_approval' }),
        task({ id: 't2', position: 1, status: 'running' }),
      ],
    })
    const cards = buildBoardCards({ goals: [g], queue: [] })
    expect(cards).toHaveLength(1)
    expect(cards[0]!.id).toBe('g1')
  })

  it('reads a task waiting for an answer or an approval into the renamed needs_you column', () => {
    const waitingForAnswer = goal({
      id: 'g1',
      tasks: [task({ id: 't1', position: 0, status: 'waiting_input' })],
    })
    const waitingForApproval = goal({
      id: 'g1b',
      tasks: [task({ id: 't1', position: 0, status: 'waiting_approval' })],
    })
    const [answerCard] = buildBoardCards({ goals: [waitingForAnswer], queue: [] })
    const [approvalCard] = buildBoardCards({ goals: [waitingForApproval], queue: [] })
    expect(answerCard).toMatchObject({ column: 'needs_you', statusKey: 'needs_you', waitingKind: 'answer' })
    expect(approvalCard).toMatchObject({ column: 'needs_you', statusKey: 'needs_you', waitingKind: 'approval' })
  })

  it('reads a failed goal into the finished column with its ending task and a failed status key (D3)', () => {
    const g = goal({
      id: 'g2',
      status: 'failed',
      tasks: [
        task({ id: 't1', position: 0, status: 'failed' }),
        task({ id: 't2', position: 1, status: 'cancelled' }),
      ],
    })
    const [card] = buildBoardCards({ goals: [g], queue: [] })
    expect(card!.column).toBe('finished')
    expect(card!.statusKey).toBe('failed')
    // The first failed or cancelled task by position, not the last task in the chain.
    expect(card!.task?.id).toBe('t1')
  })

  it('reads a completed goal into the finished column with its last completed task', () => {
    const g = goal({
      id: 'g2b',
      status: 'completed',
      tasks: [task({ id: 't1', position: 0, status: 'completed' }), task({ id: 't2', position: 1, status: 'completed' })],
    })
    const [card] = buildBoardCards({ goals: [g], queue: [] })
    expect(card!.column).toBe('finished')
    expect(card!.statusKey).toBe('finished')
    expect(card!.task?.id).toBe('t2')
  })

  it('makes a held card for a queue entry paused on its agent', () => {
    const g = goal({ id: 'g3', status: 'planning', tasks: [task({ id: 't1', position: 0, status: 'pending' })] })
    const entry: BoardQueueEntry = {
      goalId: 'g3',
      goalTitle: 'Goal',
      taskId: 't1',
      agentId: 'agent-1',
      position: 0,
      reason: 'agent_paused',
      requestedBy: null,
      source: 'manual',
      createdAt: '2026-09-28T00:00:00Z',
    }
    const [card] = buildBoardCards({ goals: [g], queue: [entry] })
    expect(card).toMatchObject({ column: 'queued', statusKey: 'held', reason: 'agent_paused', queuePosition: 0 })
  })

  it('ranks a queued goal by its place in the one shared queue, not its own task\'s stored position', () => {
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
    expect(card).toMatchObject({ column: 'queued', statusKey: 'queued', reason: 'ready', queuePosition: null })
  })
})

describe('countCards', () => {
  it('equals the number of cards each tile\'s own filter would show', () => {
    const goals = [
      goal({ id: 'g1', tasks: [task({ id: 't1', position: 0, status: 'running' })] }),
      goal({ id: 'g2', tasks: [task({ id: 't2', position: 0, status: 'waiting_input' })] }),
      goal({ id: 'g3', status: 'planning', tasks: [task({ id: 't3', position: 0, status: 'pending' })] }),
    ]
    const cards = buildBoardCards({ goals, queue: [] })
    expect(countCards(cards, 'working')).toBe(cards.filter((c) => c.statusKey === 'working').length)
    expect(countCards(cards, 'needs_you')).toBe(1)
    expect(countCards(cards, 'queued')).toBe(1)
    expect(countCards(cards, 'held')).toBe(0)
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

describe('columnTitle', () => {
  it('names the finished column by the window it covers', () => {
    expect(columnTitle('finished', 'PT2H', 120)).toBe('Finished (last 2 h)')
    expect(columnTitle('finished', 'PT6H', 360)).toBe('Finished (last 6 h)')
    expect(columnTitle('finished', 'TODAY', 300)).toBe('Finished (today)')
  })

  it('names the other columns plainly', () => {
    expect(columnTitle('needs_you', 'PT2H', 120)).toBe('Needs you')
    expect(columnTitle('queued', 'PT2H', 120)).toBe('Queued')
    expect(columnTitle('working', 'PT2H', 120)).toBe('Working')
  })
})

describe('showsStepProgress', () => {
  it('shows progress only for a goal of more than one step', () => {
    expect(showsStepProgress(goal({ id: 'g1', tasks: [task({ id: 't1' })] }))).toBe(false)
    expect(showsStepProgress(goal({ id: 'g1', tasks: [] }))).toBe(false)
    expect(showsStepProgress(goal({ id: 'g1', tasks: [task({ id: 't1' }), task({ id: 't2', position: 1 })] }))).toBe(true)
  })
})

describe('cardMetaParts', () => {
  it('joins agent, source, requester and timing in that order', () => {
    expect(cardMetaParts({ agent: 'HR', source: 'From Chat', requester: 'You', timing: '172 ms' })).toEqual([
      'HR',
      'From Chat',
      'You',
      '172 ms',
    ])
  })

  it('drops blank parts and appends a cost when there is one', () => {
    expect(cardMetaParts({ agent: null, source: 'Started by hand', requester: '', timing: '', cost: 'US$0.02' })).toEqual([
      'Started by hand',
      'US$0.02',
    ])
  })

  it('never repeats a requester that only restates the source', () => {
    expect(cardMetaParts({ agent: 'Ops', source: 'Scheduled', requester: 'Scheduled', timing: '2 s' })).toEqual(['Ops', 'Scheduled', '2 s'])
  })
})

describe('ringLayout within the canvas', () => {
  it('keeps every node and its label inside the viewBox, however many agents there are', () => {
    for (const count of [1, 2, 3, 4, 5, 6, 7, 8, 12]) {
      for (const point of ringLayout(count)) {
        // 25 is the outer status ring; 38 is where the label's baseline sits below the node.
        expect(point.y - 25).toBeGreaterThanOrEqual(0)
        expect(point.y + 38 + 4).toBeLessThanOrEqual(FLOW_VIEWBOX.height)
      }
    }
  })
})

describe('summary tile notes', () => {
  it('shows no note for a zero figure', () => {
    expect(heldTileNote(0)).toBeNull()
    expect(waitingTileNote(0, 0)).toBeNull()
    expect(directRunsTileNote(0)).toBeNull()
  })

  it('explains a figure above zero, in the singular or plural', () => {
    expect(heldTileNote(2)).toBe('Agent paused')
    expect(waitingTileNote(1, 0)).toBe('1 question')
    expect(waitingTileNote(2, 1)).toBe('2 questions · 1 approval')
    expect(waitingTileNote(0, 3)).toBe('3 approvals')
    expect(directRunsTileNote(1)).toBe('and 1 direct run')
  })
})
