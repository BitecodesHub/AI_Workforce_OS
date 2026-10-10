// @find: tests for agent queries, bulk pause, resume all, bulk message, success rate figure, cost figure, change summary, not enough runs
// @what: Unit tests for bulk agent targeting, toast messages, outcome figures and revision summaries.
import { describe, expect, it } from 'vitest'
import {
  NOT_ENOUGH_RUNS,
  bulkMessage,
  bulkTargets,
  changeSummary,
  costFigure,
  successRateFigure,
  type AgentOutcome,
} from './agentQueries'

const general = { id: 'general', status: 'active', fallback: true }
const legal = { id: 'legal', status: 'active', fallback: false }
const hr = { id: 'hr', status: 'active', fallback: false }
const pausedGeneral = { id: 'general', status: 'paused', fallback: true }
const pausedLegal = { id: 'legal', status: 'paused', fallback: false }
const retired = { id: 'old', status: 'retired', fallback: false }

const ids = (agents: Array<{ id: string }>) => agents.map((agent) => agent.id)

describe('bulkTargets', () => {
  it('pauses every active agent, the General Employee included by default', () => {
    expect(ids(bulkTargets([general, legal, pausedLegal, retired], 'pause', true))).toEqual(['general', 'legal'])
  })

  it('leaves the General Employee out of Pause all when asked', () => {
    expect(ids(bulkTargets([general, legal], 'pause', false))).toEqual(['legal'])
  })

  it('resumes every paused agent, the General Employee included', () => {
    // The old filter excluded the fallback from Resume all, so Pause all then Resume all left it paused.
    expect(ids(bulkTargets([pausedGeneral, pausedLegal, hr], 'resume', true))).toEqual(['general', 'legal'])
    expect(ids(bulkTargets([pausedGeneral, pausedLegal, hr], 'resume', false))).toEqual(['general', 'legal'])
  })

  it('sends nothing to an agent that is already in the state asked for', () => {
    expect(bulkTargets([general, legal], 'resume', true)).toEqual([])
    expect(bulkTargets([pausedGeneral, pausedLegal], 'pause', true)).toEqual([])
  })

  it('never touches a retired agent, which the API refuses', () => {
    expect(bulkTargets([retired], 'pause', true)).toEqual([])
    expect(bulkTargets([retired], 'resume', true)).toEqual([])
  })

  it('leaves the General Employee active after Pause all then Resume all', () => {
    const everyone = [general, legal, hr]
    const paused = bulkTargets(everyone, 'pause', true).map((agent) => ({ ...agent, status: 'paused' }))
    expect(ids(paused)).toEqual(['general', 'legal', 'hr'])
    expect(ids(bulkTargets(paused, 'resume', true))).toEqual(['general', 'legal', 'hr'])
  })
})

describe('bulkMessage', () => {
  it('counts only what changed', () => {
    expect(bulkMessage('pause', 3, 0)).toBe('Paused 3 agents.')
    expect(bulkMessage('resume', 1, 0)).toBe('Resumed 1 agent.')
  })

  it('says what could not be done', () => {
    expect(bulkMessage('resume', 2, 1)).toBe('Resumed 2 agents. 1 agent could not be resumed.')
    expect(bulkMessage('pause', 0, 2)).toBe('2 agents could not be paused.')
  })

  it('says so when there was nothing to do, instead of claiming a change', () => {
    expect(bulkMessage('pause', 0, 0)).toBe('There was no active agent to pause.')
    expect(bulkMessage('resume', 0, 0)).toBe('There was no paused agent to resume.')
  })
})

const outcome = (change: Partial<AgentOutcome> = {}): AgentOutcome => ({
  agentId: 'a',
  runs: 12,
  finishedRuns: 10,
  completed: 9,
  failed: 1,
  enoughRuns: true,
  successRate: 0.9,
  totalCost: 1.5,
  ...change,
})

describe('successRateFigure', () => {
  it('shows the rate over finished runs', () => {
    const figure = successRateFigure(outcome())
    expect(figure.value).toBe('90%')
    expect(figure.note).toBe('9 of 10 finished runs completed.')
  })

  it('says there are not enough runs below five finished runs, never 0% or 100%', () => {
    const three = outcome({ runs: 3, finishedRuns: 3, completed: 3, failed: 0, enoughRuns: false, successRate: null })
    expect(successRateFigure(three).value).toBe(NOT_ENOUGH_RUNS)
    expect(successRateFigure(three).note).toBe('3 runs finished so far, five are needed.')
    expect(successRateFigure(undefined).value).toBe(NOT_ENOUGH_RUNS)
  })

  it('works the threshold out itself when the service sends no flag', () => {
    expect(successRateFigure(outcome({ enoughRuns: undefined, finishedRuns: 4, completed: 4, failed: 0, successRate: null })).value).toBe(
      NOT_ENOUGH_RUNS,
    )
    expect(successRateFigure(outcome({ enoughRuns: undefined, finishedRuns: 5, completed: 4, failed: 1, successRate: null })).value).toBe('80%')
  })

  it('does not count runs still going as failures', () => {
    // Twelve runs started, ten finished: the rate is over the ten.
    expect(successRateFigure(outcome({ runs: 12, finishedRuns: 10, completed: 5, failed: 5, successRate: 0.5 })).value).toBe('50%')
  })
})

describe('costFigure', () => {
  it('shows what the runs cost at catalogue prices', () => {
    expect(costFigure(outcome())).toEqual({ value: 'US$1.50', note: 'At catalogue prices.' })
  })

  it('names unpriced runs instead of showing US$0', () => {
    expect(costFigure(outcome({ totalCost: null, unpricedRuns: 4 }))).toEqual({
      value: 'Unpriced',
      note: '4 runs on a model with no catalogue price.',
    })
  })

  it('says Free when nothing was spent because the models used are free in the catalogue', () => {
    expect(costFigure(outcome({ totalCost: 0, freeRuns: 3 }))).toEqual({
      value: 'Free',
      note: '3 runs on models that are free in the catalogue.',
    })
  })

  it('says what a total leaves out', () => {
    expect(costFigure(outcome({ unpricedRuns: 1 })).note).toBe('Leaves out 1 run with no catalogue price.')
  })

  it('shows a real zero for an agent with no runs, and Free for sandbox runs, as the run page does', () => {
    expect(costFigure(undefined)).toEqual({ value: 'US$0.00', note: 'No runs in this window.' })
    expect(costFigure(outcome({ totalCost: 0, runs: 4, sandboxRuns: 4 }))).toEqual({
      value: 'Free',
      note: 'Offline sandbox runs cost nothing.',
    })
  })
})

describe('changeSummary', () => {
  it('names what changed in the words of the edit form', () => {
    expect(changeSummary({ initial: false, changedFields: ['systemPrompt'] })).toBe('Instructions changed')
    expect(changeSummary({ initial: false, changedFields: ['systemPrompt', 'maxSteps'] })).toBe('Instructions and step limit changed')
    expect(changeSummary({ initial: false, changedFields: ['systemPrompt', 'goals', 'temperature'] })).toBe(
      'Instructions, goals and temperature changed',
    )
  })

  it('says so for the first revision and for a save with no change', () => {
    expect(changeSummary({ initial: true, changedFields: [] })).toBe('First revision')
    expect(changeSummary({ initial: false, changedFields: [] })).toBe('Saved with no change')
  })
})
