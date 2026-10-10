// @find: tests for flow map summary, workforce summary, agents busy idle paused, Orchestrator flow map, workforceSummary
// @what: Unit tests for the one-line workforce summary shown under the flow map.
// @flow: Exercises workforceSummary in FlowMap.tsx
import { describe, expect, it } from 'vitest'
import type { BoardAgent } from '../../lib/queries'
import { workforceSummary } from './FlowMap'

const agent = (status: string, running = 0): BoardAgent =>
  ({
    id: `${status}-${running}-${Math.random()}`,
    name: 'A',
    category: 'operations',
    status,
    fallback: false,
    runningRunIds: Array.from({ length: running }, (_, index) => `r${index}`),
    waitingRunIds: [],
    askingRunIds: [],
    queued: 0,
  }) as BoardAgent

describe('workforceSummary', () => {
  it('says all idle only when nobody is busy or paused', () => {
    expect(workforceSummary([agent('active'), agent('active')])).toBe('2 agents · all idle')
  })

  it('counts paused agents instead of calling them idle, and says when every one is paused', () => {
    expect(workforceSummary([agent('active', 1), agent('paused'), agent('active')])).toBe('3 agents · 1 busy · 1 paused')
    expect(workforceSummary([agent('paused'), agent('paused')])).toBe('2 agents · all paused')
  })

  it('leaves retired agents out', () => {
    expect(workforceSummary([agent('active'), agent('retired')])).toBe('1 agent · all idle')
  })
})
