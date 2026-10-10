// @find: tests for setup steps, setupSteps, summarise, set up your workspace, Setup page
// @what: Unit tests for setup step status and summary rules.
import { describe, expect, it } from 'vitest'
import { setupSteps, summarise, type SetupFacts } from './setup'

/* Setup status comes from what the services say: done, needs attention, optional, or unknown. */

const everythingDone: SetupFacts = {
  model: { allowed: true, data: { live: true, providerName: 'NVIDIA NIM' } },
  order: { allowed: true, data: { readyCandidates: 2 } },
  search: { allowed: true, data: { meaning: true, model: 'nv-embed' } },
  tools: { allowed: true, data: { live: 1, available: 8 } },
  knowledge: { allowed: true, data: { documents: 3 } },
  team: { allowed: true, data: { members: 2, invited: 0 } },
  notifications: { allowed: true, data: { webhook: false, browser: true } },
  budget: { allowed: true, data: { monthlyCap: 50 } },
}

const status = (facts: SetupFacts) => Object.fromEntries(setupSteps(facts).map((step) => [step.id, step.status]))

describe('setup steps', () => {
  it('reads every step as done from real facts', () => {
    expect(Object.values(status(everythingDone))).toEqual(Array(8).fill('done'))
    expect(summarise(setupSteps(everythingDone))).toEqual({ done: 8, total: 8, remaining: 0, complete: true })
  })

  it('needs attention for a missing model or backup, and calls keyword search, notifications and a budget optional', () => {
    const fresh: SetupFacts = {
      ...everythingDone,
      model: { allowed: true, data: { live: false, providerName: null } },
      order: { allowed: true, data: { readyCandidates: 1 } },
      search: { allowed: true, data: { meaning: false, model: 'none' } },
      team: { allowed: true, data: { members: 1, invited: 0 } },
      notifications: { allowed: true, data: { webhook: false, browser: false } },
      budget: { allowed: true, data: { monthlyCap: null } },
    }
    const steps = status(fresh)
    expect(steps).toMatchObject({ model: 'attention', order: 'attention', team: 'attention', search: 'optional', notifications: 'optional', budget: 'optional' })
    const summary = summarise(setupSteps(fresh))
    expect(summary).toMatchObject({ remaining: 3, complete: false })
  })

  it('counts an invitation as the team started', () => {
    expect(status({ ...everythingDone, team: { allowed: true, data: { members: 1, invited: 1 } } }).team).toBe('done')
  })

  it('never calls setup complete while a fact is still loading, and leaves out what the role cannot do', () => {
    const loading = { ...everythingDone, tools: { allowed: true, data: undefined } }
    expect(status(loading).tools).toBe('checking')
    expect(summarise(setupSteps(loading)).complete).toBe(false)

    const narrow = { ...everythingDone, budget: { allowed: false, data: undefined } }
    expect(status(narrow).budget).toBe('not_allowed')
    expect(summarise(setupSteps(narrow))).toMatchObject({ total: 7, done: 7, complete: true })
  })
})
