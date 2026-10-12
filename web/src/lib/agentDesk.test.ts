// @find: tests for agent desk rules, working now tiles from board, frame click mapping, step caption, tool sentence, who has control, minutes left, wheel distance, control ended message
// @what: Unit tests for agentDesk.ts: tiles from board data, mapping a click on the live frame to page coordinates, captions as sentences, and the takeover helpers.
import { describe, expect, it } from 'vitest'
import {
  controlEndedMessage,
  framePoint,
  holderName,
  minutesLeft,
  startedAtOf,
  stepCaption,
  toolSentence,
  wheelDistance,
  workingNow,
} from './agentDesk'
import type { TakeoverView } from './agentDeskQueries'
import type { Board, BoardAgent, RunStep } from './queries'

function agent(id: string, name: string, runs: Partial<Pick<BoardAgent, 'runningRunIds' | 'waitingRunIds' | 'askingRunIds'>> = {}): BoardAgent {
  return {
    id,
    name,
    category: 'operations',
    status: 'active',
    fallback: false,
    runningRunIds: runs.runningRunIds ?? [],
    waitingRunIds: runs.waitingRunIds ?? [],
    askingRunIds: runs.askingRunIds ?? [],
    queued: 0,
  }
}

function board(agents: BoardAgent[], timeline: Board['timeline'] = []): Board {
  return {
    generatedAt: '2026-10-12T09:00:00Z',
    timezone: 'UTC',
    window: 'PT2H',
    windowMinutes: 120,
    stats: {
      running: 0,
      waitingApproval: 0,
      waitingInput: 0,
      queued: 0,
      held: 0,
      completedToday: 0,
      failedToday: 0,
      spendToday: 0,
      goalsCompletedToday: 0,
      goalsFailedToday: 0,
      directRuns: 0,
    },
    agents,
    goals: [],
    queue: [],
    timeline,
    questions: [],
    approvals: [],
    failedToday: [],
  }
}

function step(kind: string, detail: Record<string, unknown>, occurredAt = '2026-10-12T09:00:00Z'): RunStep {
  return { id: `${kind}-${Math.random()}`, position: 0, kind, detail, promptTokens: 0, completionTokens: 0, durationMs: 0, occurredAt }
}

describe('workingNow', () => {
  it('gives one tile per agent with a running or waiting run, working agents first, then by name', () => {
    const tiles = workingNow(
      board(
        [
          agent('a-support', 'Support', { waitingRunIds: ['r-support'] }),
          agent('a-idle', 'Idle'),
          agent('a-research', 'Research', { runningRunIds: ['r-research', 'r-older'] }),
          agent('a-hr', 'HR', { askingRunIds: ['r-hr'] }),
          agent('a-eng', 'Engineering', { runningRunIds: ['r-eng'] }),
        ],
        [{ runId: 'r-research', agentId: 'a-research', goalId: null, status: 'running', startedAt: '2026-10-12T08:55:00Z', completedAt: null }],
      ),
    )
    expect(tiles.map((tile) => [tile.name, tile.runId, tile.state])).toEqual([
      ['Engineering', 'r-eng', 'running'],
      ['Research', 'r-research', 'running'],
      ['Support', 'r-support', 'waiting'],
      ['HR', 'r-hr', 'asking'],
    ])
    expect(tiles[1]!.startedAt).toBe('2026-10-12T08:55:00Z')
    expect(tiles[0]!.startedAt).toBeNull()
  })

  it('is empty without a board and keeps to the limit', () => {
    expect(workingNow(undefined)).toEqual([])
    const many = Array.from({ length: 20 }, (_, index) => agent(`a-${index}`, `Agent ${index}`, { runningRunIds: [`r-${index}`] }))
    expect(workingNow(board(many), 12)).toHaveLength(12)
  })

  it('takes the start time from the run itself when the timeline does not have it', () => {
    const [tile] = workingNow(board([agent('a', 'A', { runningRunIds: ['r'] })]))
    expect(startedAtOf(tile!, '2026-10-12T07:00:00Z')).toBe('2026-10-12T07:00:00Z')
    expect(startedAtOf(tile!, undefined)).toBeNull()
  })
})

describe('framePoint', () => {
  it('maps a click on the drawn picture to the frame’s own pixels', () => {
    const box = { left: 100, top: 50, width: 640, height: 400 }
    expect(framePoint({ clientX: 420, clientY: 250 }, box, { width: 1280, height: 800 })).toEqual({ x: 640, y: 400, width: 1280, height: 800 })
    expect(framePoint({ clientX: 100, clientY: 50 }, box, { width: 1280, height: 800 })).toEqual({ x: 0, y: 0, width: 1280, height: 800 })
    expect(framePoint({ clientX: 740, clientY: 450 }, box, { width: 1280, height: 800 })).toEqual({ x: 1280, y: 800, width: 1280, height: 800 })
    expect(framePoint({ clientX: 101, clientY: 51 }, { left: 100, top: 50, width: 320, height: 200 }, { width: 1280, height: 800 })).toEqual({
      x: 4,
      y: 4,
      width: 1280,
      height: 800,
    })
  })

  it('answers nothing for a point outside the picture or an unknown size', () => {
    const box = { left: 100, top: 50, width: 640, height: 400 }
    expect(framePoint({ clientX: 99, clientY: 60 }, box, { width: 1280, height: 800 })).toBeNull()
    expect(framePoint({ clientX: 200, clientY: 451 }, box, { width: 1280, height: 800 })).toBeNull()
    expect(framePoint({ clientX: 200, clientY: 60 }, { ...box, width: 0 }, { width: 1280, height: 800 })).toBeNull()
    expect(framePoint({ clientX: 200, clientY: 60 }, box, { width: 0, height: 800 })).toBeNull()
  })
})

describe('stepCaption', () => {
  it('says what the agent is doing in plain words', () => {
    expect(stepCaption([], 'running')).toBe('Starting')
    expect(stepCaption([step('model_call', { toolCalls: [] })], 'running')).toBe('Thinking about the next step')
    expect(stepCaption([step('model_call', { toolCalls: ['browser.open_page'] })], 'running')).toBe('About to open a web page')
    expect(stepCaption([step('tool_call', { tool: 'browser.open_page' })], 'running')).toBe('Opened a web page')
    expect(stepCaption([{ kind: 'tool_call', detail: { tool: 'gmail.send_message' } }], 'running')).toBe('Used Gmail')
    expect(stepCaption([step('note', { type: 'taken_over' })], 'running')).toBe('A person has control of the web browser')
    expect(stepCaption([], 'waiting')).toBe('Waiting for an approval')
    expect(stepCaption([], 'asking')).toBe('Waiting for an answer to its question')
    expect(
      stepCaption([step('tool_call', { tool: 'browser.open_page' })], 'running', {
        busy: true,
        action: 'Opening example.com',
        since: null,
        url: '',
        title: '',
        frame: '',
      }),
    ).toBe('Opening example.com')
    expect(stepCaption([], 'running', null, true)).toBe('A person has control of the web browser')
  })
})

describe('toolSentence', () => {
  it('reads as a sentence, never as a heading label put after a verb', () => {
    expect(toolSentence('browser.open_page', 'done')).toBe('Opened a web page')
    expect(toolSentence('browser__click_button', 'next')).toBe('About to click a button on a web page')
    expect(toolSentence('browser.something_new', 'done')).toBe('Used the web browser')
    expect(toolSentence('person.ask_question', 'next')).toBe('About to ask a question')
    expect(toolSentence('gmail.send_message', 'next')).toBe('About to use Gmail')
    expect(toolSentence(null, 'done')).toBe('Used a tool')
    for (const tool of ['browser.open_page', 'gmail.send_message', 'crm.find_contact', 'memory.recall']) {
      expect(toolSentence(tool, 'done')).not.toContain('·')
      expect(toolSentence(tool, 'next')).not.toContain('·')
    }
  })
})

describe('controlEndedMessage', () => {
  const view = (overrides: Partial<TakeoverView>): TakeoverView => ({
    status: 'none',
    active: false,
    starting: false,
    takenBy: null,
    takenAt: null,
    endsAt: null,
    mine: false,
    canTakeOver: true,
    canRelease: false,
    actions: 0,
    releasedBy: null,
    ...overrides,
  })
  const names = { 'u-2': { displayName: 'Priya Patel' } }

  it('says why control left a person who did not hand it back', () => {
    expect(controlEndedMessage(view({ status: 'expired' }), 'u-1', 'Research', names)).toBe('Control returned to Research after 30 minutes.')
    expect(controlEndedMessage(view({ status: 'released', releasedBy: 'u-2' }), 'u-1', 'Research', names)).toBe(
      'Priya Patel handed control back to Research.',
    )
    expect(controlEndedMessage(view({ status: 'ended' }), 'u-1', 'Research', names)).toMatch(/^Control of the web browser ended because Research/)
  })

  it('says nothing to the person who handed it back, or who still has it', () => {
    expect(controlEndedMessage(view({ status: 'released', releasedBy: 'u-1' }), 'u-1', 'Research', names)).toBeNull()
    expect(controlEndedMessage(view({ status: 'active', active: true, mine: true }), 'u-1', 'Research', names)).toBeNull()
    expect(controlEndedMessage(view({ status: 'starting', starting: true, mine: true }), 'u-1', 'Research', names)).toBeNull()
    expect(controlEndedMessage(null, 'u-1', 'Research', names)).toBeNull()
  })
})

describe('takeover helpers', () => {
  it('names who has control, falling back to neutral words', () => {
    const names = { 'u-1': { displayName: 'Priya Patel' }, 'u-2': { displayName: ' ' } }
    expect(holderName('u-1', names)).toBe('Priya Patel')
    expect(holderName('u-2', names)).toBe('Another person')
    expect(holderName('u-3', names)).toBe('Another person')
    expect(holderName(null, names)).toBe('Someone')
  })

  it('counts whole minutes left before control returns, never below zero', () => {
    const now = Date.parse('2026-10-12T09:00:00Z')
    expect(minutesLeft('2026-10-12T09:29:30Z', now)).toBe(30)
    expect(minutesLeft('2026-10-12T09:00:01Z', now)).toBe(1)
    expect(minutesLeft('2026-10-12T08:00:00Z', now)).toBe(0)
    expect(minutesLeft(null, now)).toBeNull()
    expect(minutesLeft('not a date', now)).toBeNull()
  })

  it('gathers wheel movement into one scroll within the server’s limit', () => {
    expect(wheelDistance([100, 120.4, -20])).toBe(200)
    expect(wheelDistance([4000, 4000])).toBe(5000)
    expect(wheelDistance([-9000])).toBe(-5000)
    expect(wheelDistance([])).toBe(0)
  })
})
