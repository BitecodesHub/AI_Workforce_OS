// @find: tests for chat queue, queued message, merge conversation detail queue, poll interval, describe chat action error, in use message
// @what: Tests for queue merging, polling rhythm while work is in progress, and the chat action error sentence.
import { describe, expect, it } from 'vitest'
import { ApiError } from './api'
import { IN_USE_MESSAGE, describeChatActionError } from './chatQueueQueries'
import { conversationPollMs, mergeConversationDetail, type ConversationDetail, type QueuedMessage } from './queries'

const queuedItem = (id: string, over: Partial<QueuedMessage> = {}): QueuedMessage => ({
  id,
  order: 1,
  authorId: 'user-1',
  text: `Message ${id}`,
  agentIds: [],
  attachmentCount: 0,
  createdAt: '2026-10-08T00:00:00Z',
  updatedAt: '2026-10-08T00:00:00Z',
  status: 'queued',
  canManage: true,
  ...over,
})

const detail = (over: Partial<ConversationDetail> = {}): ConversationDetail => ({
  conversation: {
    id: 'c1',
    title: 'Quotes',
    createdBy: 'user-1',
    createdAt: '2026-10-08T00:00:00Z',
    updatedAt: '2026-10-08T00:00:00Z',
    lastMessagePreview: '',
    messageCount: 0,
    pinned: false,
    archived: false,
    activity: 'idle',
    canManage: true,
    unread: false,
    match: null,
  },
  messages: [],
  goals: [],
  questions: [],
  hasEarlier: false,
  generatedAt: '2026-10-08T00:00:00Z',
  queued: [],
  busy: 'idle',
  ...over,
})

describe('mergeConversationDetail: the queue', () => {
  it('replaces the held queue with the received one, on delta reads too', () => {
    const held = detail({ queued: [queuedItem('a'), queuedItem('b', { order: 2 })], busy: 'working' })
    const merged = mergeConversationDetail(held, detail({ queued: [queuedItem('b')], busy: 'working' }))
    expect(merged.queued?.map((item) => item.id)).toEqual(['b'])
  })

  it('empties the queue when the server reports none, and takes the new busy state', () => {
    const held = detail({ queued: [queuedItem('a')], busy: 'waiting_decision' })
    const merged = mergeConversationDetail(held, detail({ queued: [], busy: 'idle' }))
    expect(merged.queued).toEqual([])
    expect(merged.busy).toBe('idle')
  })
})

describe('conversationPollMs: the queue', () => {
  it('keeps polling while the server reports work in progress, even with no goal yet', () => {
    expect(conversationPollMs(detail({ busy: 'working' }))).toBe(3_000)
  })

  it('polls slowly while the work waits on a decision with messages queued', () => {
    expect(conversationPollMs(detail({ busy: 'waiting_decision', queued: [queuedItem('a')] }))).toBe(10_000)
  })

  it('polls quickly while a queued message is starting', () => {
    expect(conversationPollMs(detail({ busy: 'idle', queued: [queuedItem('a', { status: 'starting' })] }))).toBe(3_000)
  })

  it('stops when only expired messages are left', () => {
    expect(conversationPollMs(detail({ queued: [queuedItem('a', { status: 'expired' })] }))).toBe(false)
  })
})

describe('describeChatActionError', () => {
  it('shows the server sentence for work in progress', () => {
    const error = new ApiError(409, 'RESOURCE_IN_USE', 'An answer is still being worked on in this conversation. Wait for it to finish or stop it first.', false, {})
    expect(describeChatActionError(error)).toBe(IN_USE_MESSAGE)
  })
})
