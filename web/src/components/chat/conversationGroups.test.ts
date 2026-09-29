import { describe, expect, it } from 'vitest'
import { conversationStatusText, groupConversations, highlightParts, nextAfterRemoval } from './conversationGroups'
import type { Conversation } from '../../lib/queries'

function conversation(overrides: Partial<Conversation> & Pick<Conversation, 'id'>): Conversation {
  return {
    title: 'A conversation',
    createdBy: 'user-1',
    createdAt: '2026-09-28T00:00:00Z',
    updatedAt: '2026-09-28T00:00:00Z',
    lastMessagePreview: '',
    messageCount: 1,
    pinned: false,
    archived: false,
    activity: 'idle',
    canManage: true,
    unread: false,
    match: null,
    ...overrides,
  }
}

describe('groupConversations', () => {
  const now = new Date('2026-09-28T12:00:00Z')

  it('puts pinned first, then needs you, each row once', () => {
    const pinned = [conversation({ id: 'p1' })]
    const needsYou = [conversation({ id: 'n1' }), conversation({ id: 'p1' })]
    const rows = [conversation({ id: 'p1' }), conversation({ id: 'n1' }), conversation({ id: 'r1' })]
    const groups = groupConversations(pinned, needsYou, rows, now)
    expect(groups[0]).toMatchObject({ key: 'pinned', conversations: [{ id: 'p1' }] })
    expect(groups[1]).toMatchObject({ key: 'needs-you', conversations: [{ id: 'n1' }] })
    // p1 and n1 do not reappear in a date bucket.
    const allIds = groups.flatMap((g) => g.conversations.map((c) => c.id))
    expect(allIds).toEqual(['p1', 'n1', 'r1'])
  })

  it('groups today, yesterday and a boundary around midnight', () => {
    const rows = [
      conversation({ id: 'today', updatedAt: '2026-09-28T12:00:00Z' }),
      conversation({ id: 'yesterday', updatedAt: '2026-09-27T00:00:00Z' }),
    ]
    const groups = groupConversations([], [], rows, now)
    expect(groups.find((g) => g.key === 'today')?.conversations.map((c) => c.id)).toEqual(['today'])
    expect(groups.find((g) => g.key === 'yesterday')?.conversations.map((c) => c.id)).toEqual(['yesterday'])
  })

  it('groups a row from 8 days ago under previous 30 days, not previous 7', () => {
    const rows = [conversation({ id: 'r1', updatedAt: '2026-09-20T12:00:00Z' })]
    const groups = groupConversations([], [], rows, now)
    expect(groups.find((g) => g.key === 'month')?.conversations.map((c) => c.id)).toEqual(['r1'])
    expect(groups.find((g) => g.key === 'week')).toBeUndefined()
  })

  it('groups older rows by month, newest month first, crossing a month end', () => {
    const rows = [
      conversation({ id: 'aug', updatedAt: '2026-08-05T12:00:00Z' }),
      conversation({ id: 'jul', updatedAt: '2026-07-05T12:00:00Z' }),
    ]
    const groups = groupConversations([], [], rows, now)
    const monthGroups = groups.filter((g) => g.key.startsWith('month:'))
    expect(monthGroups.map((g) => g.label)).toEqual(['August', 'July'])
  })

  it('names a month from another year with the year', () => {
    const rows = [conversation({ id: 'old', updatedAt: '2025-08-05T12:00:00Z' })]
    const groups = groupConversations([], [], rows, now)
    expect(groups.find((g) => g.key.startsWith('month:'))?.label).toBe('August 2025')
  })

  it('hides empty groups entirely', () => {
    const groups = groupConversations([], [], [], now)
    expect(groups).toEqual([])
  })
})

describe('conversationStatusText', () => {
  it('reads every activity in plain words', () => {
    expect(conversationStatusText('needs_answer')).toBe('Needs your answer')
    expect(conversationStatusText('waiting_answer')).toBe('Waiting for an answer')
    expect(conversationStatusText('needs_approval')).toBe('Needs your approval')
    expect(conversationStatusText('waiting_approval')).toBe('Waiting for approval')
    expect(conversationStatusText('working')).toBe('Working')
    expect(conversationStatusText('idle')).toBe('Idle')
  })
})

describe('highlightParts', () => {
  it('splits text around the match, case-insensitively', () => {
    expect(highlightParts('Research note', 'research')).toEqual([
      { text: 'Research', match: true },
      { text: ' note', match: false },
    ])
  })

  it('returns the whole text unmarked with no match', () => {
    expect(highlightParts('Research note', 'zzz')).toEqual([{ text: 'Research note', match: false }])
  })

  it('returns the whole text unmarked for a blank query', () => {
    expect(highlightParts('Research note', '')).toEqual([{ text: 'Research note', match: false }])
  })
})

describe('nextAfterRemoval', () => {
  it('picks the row after the removed one', () => {
    expect(nextAfterRemoval(['a', 'b', 'c'], 'b')).toBe('c')
  })

  it('picks the row before it when it was last', () => {
    expect(nextAfterRemoval(['a', 'b', 'c'], 'c')).toBe('b')
  })

  it('returns null when it was the only row', () => {
    expect(nextAfterRemoval(['a'], 'a')).toBeNull()
  })

  it('returns null when the id is not in the order', () => {
    expect(nextAfterRemoval(['a', 'b'], 'z')).toBeNull()
  })
})
