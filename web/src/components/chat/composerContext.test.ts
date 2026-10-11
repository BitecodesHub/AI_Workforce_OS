// @find: tests for composerContext, context payload, toggle knowledge, repo label, skill draft from conversation, sent context
// @what: Automated tests for the composer's "+" menu model.
// @flow: Run with the web test runner; covers composerContext.
import { describe, expect, it } from 'vitest'
import {
  EMPTY_CONTEXT,
  MAX_KNOWLEDGE,
  contextPayload,
  draftSkillFromConversation,
  normalisePath,
  repoLabel,
  sentContextOf,
  toggleKnowledge,
} from './composerContext'

describe('contextPayload', () => {
  it('adds nothing when nothing is attached, so old payloads are unchanged', () => {
    expect(contextPayload(EMPTY_CONTEXT)).toEqual({})
    expect(contextPayload(undefined)).toEqual({})
  })

  it('carries knowledge, repo and skillId', () => {
    expect(
      contextPayload({
        knowledge: [{ sourceId: 's', documentId: null, title: 'Handbook' }],
        repo: { owner: 'acme', name: 'site', branch: null, path: null },
        skill: { id: 'k1', name: 'Report', defaultAgentId: null, defaultAgentName: null },
      }),
    ).toEqual({
      knowledge: [{ sourceId: 's', documentId: null, title: 'Handbook' }],
      repo: { owner: 'acme', name: 'site', branch: null, path: null },
      skillId: 'k1',
    })
  })
})

describe('toggleKnowledge', () => {
  it('adds and removes; a whole source replaces its single documents; a document under a whole source is a no-op', () => {
    let context = toggleKnowledge(EMPTY_CONTEXT, { sourceId: 's', documentId: 'd1', title: 'Policy' })
    expect(context.knowledge).toHaveLength(1)
    context = toggleKnowledge(context, { sourceId: 's', documentId: null, title: 'Handbook' })
    expect(context.knowledge).toEqual([{ sourceId: 's', documentId: null, title: 'Handbook' }])
    expect(toggleKnowledge(context, { sourceId: 's', documentId: 'd2', title: 'Other' })).toBe(context)
    expect(toggleKnowledge(context, { sourceId: 's', documentId: null, title: 'Handbook' }).knowledge).toEqual([])
  })

  it('never holds more than the limit', () => {
    let context = EMPTY_CONTEXT
    for (let i = 0; i < MAX_KNOWLEDGE + 3; i++) context = toggleKnowledge(context, { sourceId: `s${i}`, documentId: null, title: `S${i}` })
    expect(context.knowledge).toHaveLength(MAX_KNOWLEDGE)
  })
})

describe('repoLabel and normalisePath', () => {
  it('reads owner/repo@branch/path', () => {
    expect(repoLabel({ owner: 'acme', name: 'site', branch: 'main', path: 'src' })).toBe('acme/site@main/src')
    expect(repoLabel({ owner: 'acme', name: 'site', branch: null, path: 'src' })).toBe('acme/site:src')
    expect(repoLabel({ owner: 'acme', name: 'site', branch: null, path: null })).toBe('acme/site')
    expect(normalisePath(' /src/app/ ')).toBe('src/app')
    expect(normalisePath('  ')).toBeNull()
  })
})

describe('draftSkillFromConversation', () => {
  it('names the skill after the conversation and turns the requests into goal and steps', () => {
    const draft = draftSkillFromConversation('Weekly ops summary', [
      'Summarise what the ops team shipped this week',
      'Group it by project',
      '',
    ])
    expect(draft.name).toBe('Weekly ops summary')
    expect(draft.description).toContain('Weekly ops summary')
    expect(draft.instructions).toContain('## Goal\nSummarise what the ops team shipped this week')
    expect(draft.instructions).toContain('3. Group it by project')
    expect(draft.instructions).toContain('## Output')
  })

  it('falls back to the first request when the conversation has no title', () => {
    expect(draftSkillFromConversation(' ', ['Triage the inbox']).name).toBe('Triage the inbox')
  })
})

describe('sentContextOf', () => {
  it('reads what a sent message carried, defensively', () => {
    expect(sentContextOf({})).toBeNull()
    expect(sentContextOf({ context: 'nonsense' })).toBeNull()
    expect(
      sentContextOf({
        context: {
          knowledge: [{ sourceId: 's', title: 'Handbook' }, { bad: true }],
          repo: { owner: 'acme', name: 'site', branch: 'main' },
          skill: { id: 'k', name: 'Report' },
        },
      }),
    ).toEqual({
      knowledge: [{ sourceId: 's', title: 'Handbook' }],
      repo: { owner: 'acme', name: 'site', branch: 'main', path: null },
      skill: { id: 'k', name: 'Report' },
    })
  })
})
