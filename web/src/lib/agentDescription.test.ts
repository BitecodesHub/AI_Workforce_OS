// @find: tests for agent description, describeAgent, second person rewrite, welcome screen
// @what: Unit tests for turning agent summaries into plain descriptions.
import { describe, expect, it } from 'vitest'
import { agentDescription } from './agentDescription'

describe('agentDescription', () => {
  it('uses the description written for the agent, on one line', () => {
    expect(agentDescription({ description: '  Sorts the support\n queue. ', summary: 'You triage tickets.' })).toBe(
      'Sorts the support queue.',
    )
  })

  it('falls back to a line written about the agent from its instructions', () => {
    expect(agentDescription({ description: null, summary: 'You triage support tickets and draft replies.' })).toBe(
      'Triages support tickets and drafts replies.',
    )
    expect(agentDescription({ description: '   ', summary: 'You triage tickets.' })).toBe('Triages tickets.')
  })

  it('falls back to the given text when neither says what it does', () => {
    expect(agentDescription({ summary: 'You are a helpful assistant.' }, 'Support')).toBe('Support')
    expect(agentDescription({})).toBe('')
  })
})
