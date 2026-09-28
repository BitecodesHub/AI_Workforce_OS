import { describe, expect, it } from 'vitest'
import { extractMentions, findMentionQuery, insertMention, matchAgents } from './mentions'
import type { MentionCandidate } from './mentions'

const HR = { id: 'agent-hr', name: 'HR', key: 'hr' }
const SUPPORT = { id: 'agent-support', name: 'Customer Support', key: 'support' }
const ENGINEERING = { id: 'agent-eng', name: 'Engineering Manager', key: 'engineering-manager' }
const RESEARCH = { id: 'agent-research', name: 'Research Analyst', key: 'research' }
const AGENTS: MentionCandidate[] = [HR, SUPPORT, ENGINEERING, RESEARCH]

describe('findMentionQuery', () => {
  it('finds the query at the nearest @ before the caret', () => {
    expect(findMentionQuery('Hello @res', 10)).toEqual({ query: 'res', start: 6 })
    expect(findMentionQuery('@', 1)).toEqual({ query: '', start: 0 })
  })

  it('reads the query at the caret position, not at the end of the text', () => {
    expect(findMentionQuery('@research and @support', 7)).toEqual({ query: 'resear', start: 0 })
  })

  it('returns null once a space or another @ ends the mention', () => {
    expect(findMentionQuery('@research done', 15)).toBeNull()
    expect(findMentionQuery('@research @support', 18)).toEqual({ query: 'support', start: 10 })
  })

  it('returns null with no @ at all', () => {
    expect(findMentionQuery('draft a welcome email', 10)).toBeNull()
  })
})

describe('matchAgents', () => {
  it('ranks an exact match first, then a prefix, then anywhere in the name', () => {
    const ranked = matchAgents('support', AGENTS)
    expect(ranked[0]).toBe(SUPPORT)
  })

  it('matches on key or name, case- and hyphen-insensitively', () => {
    expect(matchAgents('ENGINEERING', AGENTS)).toContain(ENGINEERING)
    expect(matchAgents('engineeringmanager', AGENTS)).toContain(ENGINEERING)
  })

  it('returns every agent, alphabetically, for an empty query', () => {
    const names = matchAgents('', AGENTS).map((agent) => agent.name)
    expect(names).toEqual(['Customer Support', 'Engineering Manager', 'HR', 'Research Analyst'])
  })

  it('excludes an agent that matches nowhere', () => {
    expect(matchAgents('zzz', AGENTS)).toEqual([])
  })
})

describe('insertMention', () => {
  it('replaces the @query with the agent key and a trailing space', () => {
    const result = insertMention('Hello @res', 6, 10, RESEARCH)
    expect(result).toEqual({ text: 'Hello @research ', caret: 16 })
  })

  it('keeps text after the caret untouched', () => {
    const result = insertMention('@sup and then @eng', 0, 4, SUPPORT)
    expect(result.text).toBe('@support  and then @eng')
  })
})

describe('extractMentions', () => {
  it('finds one mentioned agent by its key', () => {
    expect(extractMentions('@support please reply to Jordan', AGENTS)).toEqual(['agent-support'])
  })

  it('finds several mentions in order, each once', () => {
    expect(extractMentions('@research find competitors then @support draft a reply, @research again', AGENTS)).toEqual([
      'agent-research',
      'agent-support',
    ])
  })

  it('matches a multi-word name written after the @', () => {
    expect(extractMentions('@Research Analyst, what did you find', AGENTS)).toEqual(['agent-research'])
  })

  it('matches a hyphenated key written as separate words', () => {
    expect(extractMentions('@engineering manager check the build', AGENTS)).toEqual(['agent-eng'])
  })

  it('leaves an unmatched @token as literal text', () => {
    expect(extractMentions('email @nobody about this', AGENTS)).toEqual([])
  })
})
