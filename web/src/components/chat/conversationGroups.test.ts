// @find: tests for conversationGroups, conversation groups, sidebar grouping, pinned, needs you, today, yesterday, older, search highlight, status text, archive restore
// @what: Automated tests for conversationGroups.
// @flow: Run with the web test runner; covers conversationGroups.
import { describe, expect, it, vi } from 'vitest'
import { conversationStatusText, groupConversations, highlightParts, nextAfterRemoval, undoArchive } from './conversationGroups'
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

  it('groups a row from 8 days ago under Older, not previous 7 days', () => {
    const rows = [
      conversation({ id: 'week', updatedAt: '2026-09-22T12:00:00Z' }),
      conversation({ id: 'r1', updatedAt: '2026-09-20T12:00:00Z' }),
      conversation({ id: 'old', updatedAt: '2025-08-05T12:00:00Z' }),
    ]
    const groups = groupConversations([], [], rows, now)
    expect(groups.map((g) => g.label)).toEqual(['Previous 7 days', 'Older'])
    expect(groups.find((g) => g.key === 'older')?.conversations.map((c) => c.id)).toEqual(['r1', 'old'])
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
    expect(conversationStatusText('idle')).toBe('')
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

describe('undoArchive', () => {
  it('unarchives, and pins again a conversation that was pinned when it was archived', async () => {
    const actions = { unarchive: vi.fn(async () => undefined), pin: vi.fn(async () => undefined) }
    await undoArchive({ id: 'c1', pinned: true }, actions)
    expect(actions.unarchive).toHaveBeenCalledWith('c1')
    expect(actions.pin).toHaveBeenCalledWith('c1')
  })

  it('leaves an unpinned conversation unpinned', async () => {
    const actions = { unarchive: vi.fn(async () => undefined), pin: vi.fn(async () => undefined) }
    await undoArchive({ id: 'c2', pinned: false }, actions)
    expect(actions.unarchive).toHaveBeenCalledWith('c2')
    expect(actions.pin).not.toHaveBeenCalled()
  })
})

