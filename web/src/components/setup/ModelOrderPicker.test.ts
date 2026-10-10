// @find: tests for model order picker, initialChoices, orderedCandidates, sandbox last, backup model, model policy, setup
// @what: Unit tests for the simple model order helpers.
// @flow: Covers initialChoices and orderedCandidates in ModelOrderPicker.tsx.
import { describe, expect, it } from 'vitest'
import type { ModelPolicy, Provider } from '../../lib/queries'
import { initialChoices, orderedCandidates } from './ModelOrderPicker'

const policy = (candidates: Array<[string, string]>): ModelPolicy => ({
  configured: true,
  exhaustedBehaviour: 'FAIL_CLOSED',
  maxAttemptsPerCandidate: 2,
  overallDeadlineSeconds: 300,
  compactOnOverflow: true,
  candidates: candidates.map(([providerId, modelId], position) => ({ position, providerId, modelId })),
})

const provider = (id: string) => ({ id, displayName: id, kind: 'OPENAI_COMPATIBLE' }) as Provider

describe('the simple model order', () => {
  it('starts from the first two live models already in the order', () => {
    const current = policy([['sandbox', 'sandbox-1'], ['nvidia', 'a'], ['groq', 'b']])
    expect(initialChoices(current, [provider('nvidia'), provider('groq')])).toEqual([
      { providerId: 'nvidia', modelId: 'a' },
      { providerId: 'groq', modelId: 'b' },
    ])
    expect(initialChoices(undefined, [provider('nvidia')])[1]).toEqual({ providerId: '', modelId: '' })
  })

  it('puts the two choices first, keeps the rest in order, and the sandbox last', () => {
    const current = policy([['nvidia', 'a'], ['sandbox', 'sandbox-1'], ['groq', 'b'], ['openrouter', 'c']])
    const saved = orderedCandidates(
      current,
      [
        { providerId: 'groq', modelId: 'b' },
        { providerId: 'nvidia', modelId: 'a' },
      ],
      new Set(['sandbox']),
    )
    expect(saved).toEqual([
      { providerId: 'groq', modelId: 'b' },
      { providerId: 'nvidia', modelId: 'a' },
      { providerId: 'openrouter', modelId: 'c' },
      { providerId: 'sandbox', modelId: 'sandbox-1' },
    ])
  })

  it('leaves out an empty backup', () => {
    expect(orderedCandidates(undefined, [{ providerId: 'nvidia', modelId: 'a' }, { providerId: '', modelId: '' }], new Set())).toEqual([
      { providerId: 'nvidia', modelId: 'a' },
    ])
  })
})
