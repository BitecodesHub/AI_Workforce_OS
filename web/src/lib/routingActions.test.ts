// @find: tests for routing actions, routingTarget, warningOf, latencyText, checkFailureText, usageSentence, test now, remove from routing
// @what: Unit tests for the routing action helpers.
import { describe, expect, it } from 'vitest'
import { ApiError } from './api'
import { checkFailureText, latencyText, routingTarget, usageSentence, warningOf } from './routingActions'

describe('routing actions', () => {
  it('encodes model ids, which contain slashes and colons', () => {
    expect(routingTarget('openrouter', 'meta-llama/llama-3.3-70b-instruct:free')).toBe(
      'providerId=openrouter&modelId=meta-llama%2Fllama-3.3-70b-instruct%3Afree',
    )
    expect(routingTarget('groq')).toBe('providerId=groq')
  })

  it('reads a warning only when it is a sentence', () => {
    expect(warningOf({ warning: 'Heads up.' })).toBe('Heads up.')
    expect(warningOf({ warning: '  ' })).toBeNull()
    expect(warningOf({ warning: null })).toBeNull()
    expect(warningOf(undefined)).toBeNull()
  })

  it('says how long a test took', () => {
    expect(latencyText(840)).toBe('840 ms')
    expect(latencyText(1234)).toBe('1.2 s')
    expect(latencyText(null)).toBeNull()
  })

  it('asks to wait after too many tests', () => {
    expect(checkFailureText(new ApiError(429, 'RATE_LIMITED', 'x', false, {}))).toBe(
      'Too many tests in the last minute. Wait a moment, then try again.',
    )
  })

  it('names who uses a model in plain words', () => {
    expect(usageSentence({ workspace: false, agents: [{ id: 'a', name: 'A' }, { id: 'b', name: 'B' }] }, 'this model')).toBe(
      'These agents list this model and will stop using it: A and B.',
    )
    expect(usageSentence({ workspace: true, agents: [{ id: 'a', name: 'A' }] }, 'this model')).toBe(
      'The workspace routing lists this model, and it will be taken out. This agent lists this model and will stop using it: A.',
    )
    expect(usageSentence({ workspace: false, agents: [] }, 'this model')).toBe(
      'No routing lists this model right now, so nothing changes.',
    )
  })
})
