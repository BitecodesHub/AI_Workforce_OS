// @find: tests for workflow replay, status mapping, replay timeline builder, events from timestamps, waits, retries, hand-offs, state at time, tokens, next and previous event, live hand-offs between polls, goal canvas from workflow snapshot, auto layout for any goal, event sentences
// @what: Tests for the pure replay model in lib/workflowReplay.
// @flow: Exercises lib/workflowReplay directly
import { describe, expect, it } from 'vitest'
import {
  buildReplay,
  dependencies,
  eventSentence,
  goalCanvas,
  handoffsAt,
  isGoalSettled,
  liveStates,
  newHandoffs,
  nextEventTime,
  previousEventTime,
  stateAt,
  stepStatusOf,
} from './workflowReplay'
import type { GoalTimeline, TimelineTask } from './workflowQueries'

const T0 = Date.parse('2026-10-12T09:00:00Z')
const at = (seconds: number) => new Date(T0 + seconds * 1000).toISOString()

const task = (id: string, position: number, extra: Partial<TimelineTask> = {}): TimelineTask => ({
  id,
  title: `Task ${id}`,
  agentId: `agent-${id}`,
  agentName: `Agent ${id}`,
  agentCategory: 'operations',
  position,
  dependsOn: [],
  status: 'pending',
  startedAt: null,
  completedAt: null,
  runId: null,
  ...extra,
})

/**
 * A finished goal: research (0-60 s, waits for approval 10-40 s) hands to draft (61-120 s, which
 * failed once at 80 s and was retried at 90 s); review was skipped when the goal ended.
 */
function finished(): GoalTimeline {
  return {
    goalId: 'goal-1',
    title: 'Reply to a customer',
    status: 'completed',
    createdAt: at(0),
    completedAt: at(130),
    now: at(500),
    tasks: [
      task('research', 0, { status: 'completed', startedAt: at(1), completedAt: at(60), runId: 'run-r' }),
      task('draft', 1, { dependsOn: ['research'], status: 'completed', startedAt: at(61), completedAt: at(120), runId: 'run-d2' }),
      task('review', 2, { dependsOn: ['draft'], status: 'skipped' }),
    ],
    runs: [
      { id: 'run-r', taskId: 'research', status: 'completed', startedAt: at(1), completedAt: at(60) },
      { id: 'run-d1', taskId: 'draft', status: 'failed', startedAt: at(61), completedAt: at(80) },
      { id: 'run-d2', taskId: 'draft', status: 'completed', startedAt: at(90), completedAt: at(120) },
    ],
    waits: [{ runId: 'run-r', taskId: 'research', kind: 'approval', startedAt: at(10), endedAt: at(40), outcome: 'approved' }],
    workflow: null,
  }
}

describe('status mapping', () => {
  it('maps every task status to a canvas status, unknown ones to queued', () => {
    expect(stepStatusOf('pending')).toBe('queued')
    expect(stepStatusOf('ready')).toBe('queued')
    expect(stepStatusOf('running')).toBe('running')
    expect(stepStatusOf('waiting_approval')).toBe('approval')
    expect(stepStatusOf('WAITING_INPUT')).toBe('question')
    expect(stepStatusOf('completed')).toBe('completed')
    expect(stepStatusOf('failed')).toBe('failed')
    expect(stepStatusOf('cancelled')).toBe('cancelled')
    expect(stepStatusOf('skipped')).toBe('skipped')
    expect(stepStatusOf(null)).toBe('queued')
    expect(isGoalSettled('completed')).toBe(true)
    expect(isGoalSettled('waiting')).toBe(false)
  })
})

describe('building a replay from timestamps', () => {
  it('turns starts, waits, retries, hand-offs and finishes into events in time order', () => {
    const replay = buildReplay(finished())
    const summary = replay.events.map((event) => `${(event.at - T0) / 1000}:${event.taskId}:${event.kind}`)
    expect(summary).toEqual([
      '1:research:started',
      '10:research:waiting',
      '40:research:resumed',
      '60:research:finished',
      '61:draft:handoff',
      '61:draft:started',
      '80:draft:retrying',
      '90:draft:started',
      '120:draft:finished',
      '130:review:finished',
    ])
    expect(replay.start).toBe(T0)
    expect(replay.end).toBe(T0 + 130_000)
    expect(replay.events.find((event) => event.kind === 'handoff')?.fromTaskId).toBe('research')
  })

  it('gives the state of every step at any moment', () => {
    const replay = buildReplay(finished())
    const ids = ['research', 'draft', 'review']
    const state = (seconds: number) => Object.fromEntries(stateAt(replay, ids, T0 + seconds * 1000))
    expect(state(0)).toEqual({ research: 'queued', draft: 'queued', review: 'queued' })
    expect(state(5)).toMatchObject({ research: 'running' })
    expect(state(20)).toMatchObject({ research: 'approval' })
    expect(state(50)).toMatchObject({ research: 'running' })
    expect(state(70)).toMatchObject({ research: 'completed', draft: 'running' })
    expect(state(85)).toMatchObject({ draft: 'queued' })
    expect(state(125)).toMatchObject({ draft: 'completed', review: 'queued' })
    expect(state(130)).toEqual({ research: 'completed', draft: 'completed', review: 'skipped' })
  })

  it('ends a wait that settled as the step ended at the finish, not back at running', () => {
    const timeline = finished()
    timeline.tasks[0] = { ...timeline.tasks[0]!, status: 'failed' }
    timeline.waits = [{ runId: 'run-r', taskId: 'research', kind: 'approval', startedAt: at(10), endedAt: at(60), outcome: 'rejected' }]
    const replay = buildReplay(timeline)
    expect(stateAt(replay, ['research'], T0 + 60_000).get('research')).toBe('failed')
    expect(replay.events.some((event) => event.kind === 'resumed')).toBe(false)
  })

  it('runs a goal still going until the server clock, with a question still open', () => {
    const live: GoalTimeline = {
      ...finished(),
      status: 'running',
      completedAt: null,
      now: at(300),
      tasks: [task('research', 0, { status: 'waiting_input', startedAt: at(1), runId: 'run-r' })],
      runs: [{ id: 'run-r', taskId: 'research', status: 'waiting_input', startedAt: at(1), completedAt: null }],
      waits: [{ runId: 'run-r', taskId: 'research', kind: 'question', startedAt: at(5), endedAt: null, outcome: 'pending' }],
    }
    const replay = buildReplay(live)
    expect(replay.end).toBe(T0 + 300_000)
    expect(stateAt(replay, ['research'], replay.end).get('research')).toBe('question')
  })

  it('moves between events and finds hand-offs under way', () => {
    const replay = buildReplay(finished())
    expect(nextEventTime(replay, T0)).toBe(T0 + 1_000)
    expect(nextEventTime(replay, T0 + 130_000)).toBe(replay.end)
    expect(previousEventTime(replay, T0 + 61_000)).toBe(T0 + 60_000)
    expect(previousEventTime(replay, T0)).toBe(replay.start)

    expect(handoffsAt(replay, T0 + 61_500, 1_000)).toEqual([{ fromTaskId: 'research', toTaskId: 'draft', progress: 0.5 }])
    expect(handoffsAt(replay, T0 + 62_500, 1_000)).toEqual([])
    expect(handoffsAt(replay, T0 + 61_500, 0)).toEqual([])
  })

  it('says each event in plain words', () => {
    const replay = buildReplay(finished())
    const name = (id: string) => ({ research: 'Research', draft: 'Draft', review: 'Review' })[id] ?? id
    expect(replay.events.map((event) => eventSentence(event, name))).toEqual([
      'Research started',
      'Research is waiting for approval',
      'Research carried on',
      'Research: completed',
      'Research handed over to Draft',
      'Draft started',
      'Draft will try again',
      'Draft started',
      'Draft: completed',
      'Review: skipped',
    ])
  })

  it('drops the full stop that ends a step named after its instruction, so the sentence reads cleanly', () => {
    // Seen live on 13 Oct 2026: "...one sentence each.: completed".
    const titled = (taskId: string) =>
      taskId === 'a' ? 'General Employee: List three tips, one sentence each.' : 'Research: Make a checklist. '
    expect(eventSentence({ kind: 'finished', taskId: 'a', status: 'completed', at: T0 }, titled)).toBe(
      'General Employee: List three tips, one sentence each: completed',
    )
    expect(eventSentence({ kind: 'started', taskId: 'a', at: T0 }, titled)).toBe(
      'General Employee: List three tips, one sentence each started',
    )
    expect(eventSentence({ kind: 'handoff', taskId: 'b', fromTaskId: 'a', at: T0 }, titled)).toBe(
      'General Employee: List three tips, one sentence each handed over to Research: Make a checklist',
    )
  })
})

describe('live hand-offs', () => {
  it('reads the live state from the task statuses', () => {
    expect(Object.fromEntries(liveStates(finished()))).toEqual({ research: 'completed', draft: 'completed', review: 'skipped' })
  })

  it('finds a hand-off when a waiting step starts after its predecessor completed, and none on the first read', () => {
    const before = new Map([['draft', ['research']]])
    const previous = new Map([
      ['research', 'running' as const],
      ['draft', 'queued' as const],
    ])
    const next = new Map([
      ['research', 'completed' as const],
      ['draft', 'running' as const],
    ])
    expect(newHandoffs(previous, next, before)).toEqual([{ fromTaskId: 'research', toTaskId: 'draft' }])
    expect(newHandoffs(null, next, before)).toEqual([])
    expect(newHandoffs(next, next, before)).toEqual([])
  })
})

describe('the canvas a goal is drawn on', () => {
  it('uses the workflow snapshot, matching each task to its node by position', () => {
    const timeline: GoalTimeline = {
      ...finished(),
      workflow: {
        workflowId: 'wf-1',
        workflowRunId: 'wr-1',
        name: 'Reply',
        version: 2,
        graph: {
          nodes: [
            { id: 'n-draft', agentId: 'agent-draft', instruction: 'Draft', x: 400, y: 0 },
            { id: 'n-research', agentId: 'agent-research', instruction: 'Research', x: 0, y: 0 },
            { id: 'n-review', agentId: 'agent-review', instruction: 'Review', x: 800, y: 0 },
          ],
          edges: [
            { id: 'e1', from: 'n-research', to: 'n-draft' },
            { id: 'e2', from: 'n-draft', to: 'n-review' },
          ],
        },
        nodeIds: ['n-research', 'n-draft', 'n-review'],
      },
    }
    const canvas = goalCanvas(timeline)
    expect(canvas.graph.nodes).toHaveLength(3)
    expect(canvas.nodeOfTask.get('draft')).toBe('n-draft')
    expect(canvas.taskOfNode.get('n-review')).toBe('review')
  })

  it('lays out any other goal in columns, chaining tasks without explicit dependencies', () => {
    const timeline: GoalTimeline = {
      ...finished(),
      tasks: [task('a', 0), task('b', 1), task('c', 2, { dependsOn: ['a'] })],
    }
    const deps = dependencies(timeline.tasks)
    expect(deps.get('b')).toEqual(['a'])
    expect(deps.get('c')).toEqual(['a'])
    const canvas = goalCanvas(timeline)
    expect(canvas.graph.edges.map((edge) => `${edge.from}>${edge.to}`)).toEqual(['a>b', 'a>c'])
    const x = (id: string) => canvas.graph.nodes.find((node) => node.id === id)!.x
    expect(x('a')).toBeLessThan(x('b'))
    expect(x('b')).toBe(x('c'))
  })

  it('draws a workflow\'s steps that nothing hands over to side by side, and hands nothing between them', () => {
    // facts and research both start the run; draft waits for both. Without the explicit flag,
    // research would be drawn (and replayed) as waiting for facts.
    const tasks = [
      task('facts', 0, { explicitDependencies: true, status: 'completed', startedAt: at(1), completedAt: at(30), runId: 'run-f' }),
      task('research', 1, { explicitDependencies: true, status: 'completed', startedAt: at(2), completedAt: at(40), runId: 'run-r' }),
      task('draft', 2, { explicitDependencies: true, dependsOn: ['facts', 'research'], status: 'completed', startedAt: at(41), completedAt: at(60), runId: 'run-d' }),
    ]
    const deps = dependencies(tasks)
    expect(deps.get('research')).toEqual([])
    expect(deps.get('draft')).toEqual(['facts', 'research'])
    const timeline: GoalTimeline = {
      ...finished(),
      tasks,
      runs: [
        { id: 'run-f', taskId: 'facts', status: 'completed', startedAt: at(1), completedAt: at(30) },
        { id: 'run-r', taskId: 'research', status: 'completed', startedAt: at(2), completedAt: at(40) },
        { id: 'run-d', taskId: 'draft', status: 'completed', startedAt: at(41), completedAt: at(60) },
      ],
      waits: [],
    }
    const handoffs = buildReplay(timeline).events.filter((event) => event.kind === 'handoff')
    expect(handoffs.map((event) => `${event.fromTaskId}>${event.taskId}`)).toEqual(['facts>draft', 'research>draft'])
    const canvas = goalCanvas(timeline)
    const x = (id: string) => canvas.graph.nodes.find((node) => node.id === id)!.x
    expect(x('facts')).toBe(x('research'))
  })
})
