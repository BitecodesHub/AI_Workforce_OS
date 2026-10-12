// @find: tests for team room queries, presence caption per state, approval never names the requester, feed line per kind, direct runs, merge newer, merge older, keep earlier lines while reading back, newest added line, catch up after a hidden tab, drop repeats, bounded feed, feed link permissions, person namer
// @what: Unit tests for the team room's wording of every presence state and every conversation event, and for reading and merging pages of the conversation.
// @flow: Exercises lib/teamRoomQueries.ts
import { describe, expect, it } from 'vitest'
import {
  CATCH_UP_LIMIT,
  HELD_WHILE_READING_BACK,
  MAX_CATCH_UP_PAGES,
  MAX_EVENTS,
  feedLine,
  feedLink,
  feedSpeaker,
  isBusy,
  isWaiting,
  mergeNewer,
  mergeOlder,
  personNamer,
  presenceCaption,
  readNewer,
  releaseOlder,
} from './teamRoomQueries'
import type { AgentPresence, FeedEvent, FeedKind, FeedPage, FeedState } from './teamRoomQueries'

/*
 * Every state an agent can be in reads as one short line, and every event as one sentence a team
 * would say ("Sam asked HR: Hire a designer"). The viewer is "you"; an unknown person is never
 * named as "Someone" where leaving them out reads better. Pages of the conversation merge by id,
 * oldest first, and the oldest are let go beyond a fixed number so a page left open stays light.
 */

const ME = 'user-me'
const SAM = 'user-sam'
const nameOf = personNamer(ME, { [SAM]: 'Sam Lee' })

function presence(overrides: Partial<AgentPresence> = {}): AgentPresence {
  return {
    id: 'agent-1',
    name: 'HR',
    category: 'operations',
    description: 'Checks applications against the role.',
    fallback: false,
    agentStatus: 'active',
    state: 'idle',
    since: null,
    runId: null,
    goalId: null,
    goalTitle: null,
    taskId: null,
    taskTitle: null,
    server: null,
    tool: null,
    site: null,
    approvalId: null,
    questionId: null,
    waitingText: null,
    waitingOn: null,
    waitingForYou: false,
    activeRuns: 0,
    queued: 0,
    ...overrides,
  }
}

function event(kind: FeedKind, overrides: Partial<FeedEvent> = {}): FeedEvent {
  return {
    id: `${kind}-1`,
    cursor: `cursor-${kind}`,
    kind,
    at: '2026-10-12T09:00:00Z',
    agentId: 'agent-1',
    agentName: 'HR',
    agentCategory: 'operations',
    agentFallback: false,
    personId: SAM,
    nextAgentId: null,
    nextAgentName: null,
    nextAgentCategory: null,
    nextTaskTitle: null,
    goalId: 'goal-1',
    taskId: null,
    runId: null,
    approvalId: null,
    questionId: null,
    title: null,
    text: null,
    scheduleName: null,
    forYou: false,
    ...overrides,
  }
}

describe('presenceCaption', () => {
  it('says what each state is doing in a few words', () => {
    expect(presenceCaption(presence(), nameOf)).toBe('Idle')
    expect(presenceCaption(presence({ state: 'thinking' }), nameOf)).toBe('Thinking')
    expect(presenceCaption(presence({ state: 'browsing', site: 'youtube.com' }), nameOf)).toBe('Browsing youtube.com')
    expect(presenceCaption(presence({ state: 'browsing' }), nameOf)).toBe('Browsing the web')
    expect(presenceCaption(presence({ state: 'using_tool', server: 'slack' }), nameOf)).toBe('Using Slack')
    expect(presenceCaption(presence({ state: 'using_tool', server: 'knowledge' }), nameOf)).toBe(
      'Searching your documents',
    )
    expect(presenceCaption(presence({ state: 'paused' }), nameOf)).toBe('Paused')
    expect(presenceCaption(presence({ state: 'private' }), nameOf)).toBe('Busy with private work')
  })

  it('says who a waiting agent waits for: you, a named person for a question, or nobody in particular', () => {
    expect(presenceCaption(presence({ state: 'waiting_approval', waitingForYou: true }), nameOf)).toBe(
      'Waiting for you to approve',
    )
    // An approval never names the person who asked for the work: under a four-eyes rule that is
    // exactly the one person who may not decide it.
    expect(presenceCaption(presence({ state: 'waiting_approval', waitingOn: SAM }), nameOf)).toBe('Waiting for approval')
    expect(presenceCaption(presence({ state: 'waiting_approval', waitingOn: ME }), nameOf)).toBe('Waiting for approval')
    expect(presenceCaption(presence({ state: 'waiting_approval' }), nameOf)).toBe('Waiting for approval')
    expect(presenceCaption(presence({ state: 'waiting_input', waitingForYou: true }), nameOf)).toBe(
      'Waiting for your answer',
    )
    expect(presenceCaption(presence({ state: 'waiting_input', waitingOn: SAM }), nameOf)).toBe(
      'Waiting for Sam Lee to answer',
    )
    expect(presenceCaption(presence({ state: 'waiting_input' }), nameOf)).toBe('Waiting for an answer')
  })

  it('counts thinking, browsing, using and private work as busy, and the two waits as waiting', () => {
    expect(['thinking', 'browsing', 'using_tool', 'private'].every((state) => isBusy(state as never))).toBe(true)
    expect(isBusy('idle')).toBe(false)
    expect(isBusy('waiting_approval')).toBe(false)
    expect(isWaiting('waiting_input')).toBe(true)
    expect(isWaiting('paused')).toBe(false)
  })
})

describe('feedLine', () => {
  it('writes each kind of event as the team would say it', () => {
    const line = (kind: FeedKind, overrides: Partial<FeedEvent> = {}) => feedLine(event(kind, overrides), nameOf)
    expect(line('goal_requested', { title: 'Hire a designer' }).text).toBe('Sam Lee asked HR: Hire a designer')
    expect(line('goal_requested', { title: 'Hire a designer', agentName: null }).text).toBe(
      'Sam Lee asked the team: Hire a designer',
    )
    expect(line('schedule_started', { title: 'Digest', scheduleName: 'Monday digest' }).text).toBe(
      'The Monday digest schedule started Digest',
    )
    expect(line('task_started', { title: 'Screen the applications' }).text).toBe('HR started on Screen the applications')
    expect(
      line('task_handed_off', { title: 'Screen', nextAgentName: 'Writer', nextTaskTitle: 'Write the note' }),
    ).toEqual({ text: 'HR finished Screen and handed it to Writer', detail: 'Next: Write the note' })
    expect(line('task_finished', { title: 'Write', text: 'Sent the note.' }).text).toBe('HR finished: Sent the note.')
    expect(line('task_finished', { title: 'Write' }).text).toBe('HR finished Write')
    expect(line('task_failed', { title: 'Book', text: 'The calendar refused.' })).toEqual({
      text: 'HR could not finish Book',
      detail: 'The calendar refused.',
    })
    // The person who asked for the work is never named as the one to approve it.
    expect(line('approval_requested', { text: 'Send the offer' }).text).toBe('HR is waiting for approval: Send the offer')
    expect(line('approval_requested', { text: 'Send the offer', personId: ME }).text).toBe(
      'HR is waiting for approval: Send the offer',
    )
    // Only the server's word that this viewer may decide it now makes it "you".
    expect(line('approval_requested', { text: 'Send the offer', forYou: true }).text).toBe(
      'HR is waiting for you to approve: Send the offer',
    )
    expect(line('approval_approved', { text: 'Send the offer', personId: ME }).text).toBe('You approved: Send the offer')
    expect(line('approval_rejected', { text: 'Post it' }).text).toBe('Sam Lee rejected: Post it')
    expect(line('approval_sent_back', { text: 'Refund' }).text).toBe('Sam Lee sent it back with feedback: Refund')
    expect(line('approval_expired', { text: 'Delete the page' }).text).toBe(
      'No one decided in time, so HR did not go ahead: Delete the page',
    )
    expect(line('question_asked', { text: 'Which tone?' }).text).toBe('HR asked: Which tone?')
    // The text is the question, so the line says so rather than reading as the answer.
    expect(line('question_answered', { text: 'Which tone?', personId: ME }).text).toBe(
      "You answered HR's question: Which tone?",
    )
  })

  it('writes work started straight on an agent: who asked, and how it ended', () => {
    const line = (kind: FeedKind, overrides: Partial<FeedEvent> = {}) => feedLine(event(kind, overrides), nameOf)
    expect(line('run_requested', { title: 'Find the newest applications' }).text).toBe(
      'Sam Lee asked HR: Find the newest applications',
    )
    expect(line('run_requested', { title: 'Find the newest applications', personId: null }).text).toBe(
      'HR started on Find the newest applications',
    )
    expect(line('run_finished', { title: 'Find them', text: 'Three found.' }).text).toBe('HR finished: Three found.')
    expect(line('run_finished', { title: 'Find them' }).text).toBe('HR finished Find them')
    expect(line('run_failed', { title: 'Find them', text: 'The model refused.' })).toEqual({
      text: 'HR could not finish Find them',
      detail: 'The model refused.',
    })
    expect(feedSpeaker(event('run_requested'))).toBe('person')
    expect(feedSpeaker(event('run_requested', { personId: null }))).toBe('agent')
    expect(feedSpeaker(event('run_finished'))).toBe('agent')
    expect(feedLink(event('run_finished', { runId: 'r9', goalId: null }), () => true)).toEqual({
      href: '/runs/r9',
      label: 'Open run',
    })
  })

  it('names a deleted agent as an agent, and puts asking, deciding and answering in a person’s mouth', () => {
    expect(feedLine(event('task_started', { agentName: null, title: 'X' }), nameOf).text).toBe('An agent started on X')
    expect(feedSpeaker(event('goal_requested'))).toBe('person')
    expect(feedSpeaker(event('approval_approved'))).toBe('person')
    expect(feedSpeaker(event('question_answered'))).toBe('person')
    expect(feedSpeaker(event('schedule_started'))).toBe('schedule')
    expect(feedSpeaker(event('task_handed_off'))).toBe('agent')
  })
})

describe('feedLink', () => {
  const all = () => true
  const runsOnly = (permission: string) => permission === 'run:read'

  it('leads to the approval, the run or the goal, only as far as the role reaches', () => {
    expect(feedLink(event('approval_requested', { approvalId: 'a1', runId: 'r1' }), all)).toEqual({
      href: '/approvals#approval-a1',
      label: 'Review',
    })
    expect(feedLink(event('approval_requested', { approvalId: 'a1', runId: 'r1' }), runsOnly)).toEqual({
      href: '/runs/r1',
      label: 'Open run',
    })
    expect(feedLink(event('task_started', { goalId: 'g1' }), all)).toEqual({ href: '/tasks?goal=g1', label: 'Open goal' })
    expect(feedLink(event('task_started', { goalId: 'g1' }), runsOnly)).toBeNull()
  })
})

function page(events: FeedEvent[], overrides: Partial<FeedPage> = {}): FeedPage {
  return {
    generatedAt: '2026-10-12T10:00:00Z',
    events,
    newestCursor: 'newest',
    olderCursor: events[0]?.cursor ?? null,
    hasOlder: false,
    hasNewer: false,
    ...overrides,
  }
}

function numbered(index: number): FeedEvent {
  return event('task_started', {
    id: `e${String(index).padStart(5, '0')}`,
    cursor: `c${index}`,
    at: new Date(Date.UTC(2026, 9, 12, 9, 0, 0) + index * 1000).toISOString(),
  })
}

describe('merging pages', () => {
  it('takes the first page as it is, announcing nothing', () => {
    const state = mergeNewer(undefined, page([numbered(1), numbered(2)], { hasOlder: true, olderCursor: 'c1' }))
    expect(state.events.map((e) => e.id)).toEqual(['e00001', 'e00002'])
    expect(state).toMatchObject({
      newestCursor: 'newest',
      olderCursor: 'c1',
      hasOlder: true,
      added: 0,
      latest: null,
      keepOlder: false,
    })
  })

  it('names the newest of the added events, even when one arrived late with an earlier moment', () => {
    const held = mergeNewer(undefined, page([numbered(10), numbered(12)]))
    const late = mergeNewer(held, page([numbered(11)]))
    expect(late.events.map((e) => e.id)).toEqual(['e00010', 'e00011', 'e00012'])
    expect(late.added).toBe(1)
    expect(late.latest?.id).toBe('e00011')
    const both = mergeNewer(late, page([numbered(13), numbered(9)]))
    expect(both.latest?.id).toBe('e00013')
    expect(mergeNewer(both, page([numbered(13)])).latest).toBeNull()
  })

  it('adds only what is new, keeps the order, and counts what it added', () => {
    const first = mergeNewer(undefined, page([numbered(1), numbered(2)]))
    const second = mergeNewer(first, page([numbered(2), numbered(4), numbered(3)], { newestCursor: 'next' }))
    expect(second.events.map((e) => e.id)).toEqual(['e00001', 'e00002', 'e00003', 'e00004'])
    expect(second.added).toBe(2)
    expect(second.newestCursor).toBe('next')
    const third = mergeNewer(second, page([numbered(4)]))
    expect(third.added).toBe(0)
    expect(third.events).toBe(second.events)
  })

  it('lets the oldest go beyond the limit, and leaves a way back to them', () => {
    let state = mergeNewer(undefined, page([numbered(0)]))
    const many = Array.from({ length: MAX_EVENTS + 10 }, (_, index) => numbered(index + 1))
    state = mergeNewer(state, page(many))
    expect(state.events).toHaveLength(MAX_EVENTS)
    expect(state.events[0]!.id).toBe(numbered(11).id)
    expect(state.hasOlder).toBe(true)
    expect(state.olderCursor).toBe('c11')
  })

  it('keeps the earlier lines a reader loaded through polls, until they are back at the newest line', () => {
    let state = mergeNewer(undefined, page(Array.from({ length: MAX_EVENTS }, (_, index) => numbered(index + 1000))))
    const older = Array.from({ length: 50 }, (_, index) => numbered(index + 900))
    state = mergeOlder(state, page(older, { olderCursor: 'c900', hasOlder: true }))
    expect(state.events).toHaveLength(MAX_EVENTS + 50)
    expect(state.keepOlder).toBe(true)

    // A poll with nothing new lets nothing go.
    const quiet = mergeNewer(state, page([]))
    expect(quiet.events).toBe(state.events)
    // A poll with something new keeps what the reader loaded too.
    const busy = mergeNewer(quiet, page([numbered(5000)]))
    expect(busy.events).toHaveLength(MAX_EVENTS + 51)
    expect(busy.events[0]!.id).toBe(numbered(900).id)
    expect(busy.olderCursor).toBe('c900')

    // Back at the newest line: the next poll that adds something lets the oldest go again.
    const released = releaseOlder(busy)
    expect(released.keepOlder).toBe(false)
    expect(releaseOlder(released)).toBe(released)
    const trimmed = mergeNewer(released, page([numbered(5001)]))
    expect(trimmed.events).toHaveLength(MAX_EVENTS)
    expect(trimmed.events[trimmed.events.length - 1]!.id).toBe(numbered(5001).id)
    expect(trimmed.hasOlder).toBe(true)
  })

  it('still bounds what it keeps while the reader reads back', () => {
    let state = mergeOlder(
      undefined,
      page(Array.from({ length: HELD_WHILE_READING_BACK }, (_, index) => numbered(index)), { hasOlder: true }),
    )
    state = mergeNewer(state, page([numbered(HELD_WHILE_READING_BACK + 1)]))
    expect(state.events).toHaveLength(HELD_WHILE_READING_BACK)
    expect(state.events[0]!.id).toBe(numbered(1).id)
  })

  it('puts an earlier page in front, without repeats, and follows its way further back', () => {
    const held: FeedState = mergeNewer(undefined, page([numbered(5), numbered(6)], { hasOlder: true }))
    const merged = mergeOlder(held, page([numbered(3), numbered(4), numbered(5)], { olderCursor: 'c3', hasOlder: false }))
    expect(merged.events.map((e) => e.id)).toEqual(['e00003', 'e00004', 'e00005', 'e00006'])
    expect(merged.hasOlder).toBe(false)
    expect(merged.olderCursor).toBe('c3')
    expect(merged.added).toBe(0)
    expect(merged.newestCursor).toBe(held.newestCursor)
  })
})

describe('readNewer', () => {
  function pages(...replies: FeedPage[]) {
    const urls: string[] = []
    const read = (url: string) => {
      urls.push(url)
      const reply = replies.shift()
      if (!reply) throw new Error(`unexpected read ${url}`)
      return Promise.resolve(reply)
    }
    return { urls, read }
  }

  it('reads one page when nothing more is waiting', async () => {
    const { urls, read } = pages(page([numbered(1)], { newestCursor: 'n1' }))
    const result = await readNewer('n0', read)
    expect(urls).toEqual(['/api/orchestrator/team-room/feed?after=n0'])
    expect(result?.events.map((e) => e.id)).toEqual(['e00001'])
    expect(result?.newestCursor).toBe('n1')
  })

  it('reads on at once while more is waiting, so a hidden tab catches up in one poll', async () => {
    const { urls, read } = pages(
      page([numbered(1), numbered(2)], { newestCursor: 'n2', hasNewer: true }),
      page([numbered(3)], { newestCursor: 'n3', hasNewer: true }),
      page([numbered(4)], { newestCursor: 'n4' }),
    )
    const result = await readNewer('n0', read)
    expect(urls).toEqual([
      '/api/orchestrator/team-room/feed?after=n0',
      `/api/orchestrator/team-room/feed?after=n2&limit=${CATCH_UP_LIMIT}`,
      `/api/orchestrator/team-room/feed?after=n3&limit=${CATCH_UP_LIMIT}`,
    ])
    expect(result?.events.map((e) => e.id)).toEqual(['e00001', 'e00002', 'e00003', 'e00004'])
    expect(result?.hasNewer).toBe(false)
    expect(result?.newestCursor).toBe('n4')

    // All of it lands in one merge, announced once with the newest line.
    const held = mergeNewer(undefined, page([numbered(0)]))
    const merged = mergeNewer(held, result!)
    expect(merged.added).toBe(4)
    expect(merged.latest?.id).toBe('e00004')
  })

  it('gives up when too far behind, so the caller starts again from the newest page', async () => {
    const replies = Array.from({ length: MAX_CATCH_UP_PAGES }, (_, index) =>
      page([numbered(index)], { newestCursor: `n${index + 1}`, hasNewer: true }),
    )
    const { urls, read } = pages(...replies)
    expect(await readNewer('n0', read)).toBeNull()
    expect(urls).toHaveLength(MAX_CATCH_UP_PAGES)
  })
})

describe('personNamer', () => {
  it('is You for the viewer, the member name when known, and Someone otherwise', () => {
    expect(nameOf(ME)).toBe('You')
    expect(nameOf(SAM)).toBe('Sam Lee')
    expect(nameOf('user-unknown')).toBe('Someone')
    expect(nameOf(null)).toBe('Someone')
  })
})
