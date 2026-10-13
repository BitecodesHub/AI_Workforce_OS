// @find: team room queries, presence polling, feed polling, after cursor, catch up after a hidden tab, show earlier, keep earlier lines while reading back, merge feed pages, newest added line, presence caption, feed line, hand-off wording, useTeamRoom, useTeamRoomFeed, GET /api/orchestrator/team-room, GET /api/orchestrator/team-room/feed, Team room page
// @what: Reads the team room (every agent's live presence and the hand-off conversation) and puts each state and event into plain words.
// @flow: Used by routes/TeamRoom.tsx and components/team-room; calls api() against /api/orchestrator/team-room.
import { useCallback, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import { readableRequestSentence } from './approvals'
import { serverLabel } from './labels'

/*
 * The team room: the agents as colleagues at work. Two reads, both polled only while the tab is
 * visible (refetchIntervalInBackground stays false, so a hidden tab costs nothing):
 *
 *  - the presence, every 2 seconds: what each agent is doing now;
 *  - the conversation, every 3 seconds: who asked whom, who handed what to whom, who is waiting on
 *    a decision. The first read takes the newest page; every later one asks only for what happened
 *    after the cursor the server handed back, and the answer is merged in by event id, so a repeat
 *    (the server re-reads the last few seconds on purpose) never shows twice. When more happened
 *    than one page holds (the tab was hidden a while), the poll reads on until it has caught up,
 *    or starts again from the newest page when it is too far behind.
 *
 * Everything that turns a state or an event into words is a pure function here, tested on its own.
 */

/* ---- Wire types (TeamRoomViews.java) ----------------------------------------------------------- */

/** What an agent is doing, as the server names it. */
export type PresenceState =
  | 'idle'
  | 'thinking'
  | 'browsing'
  | 'using_tool'
  | 'waiting_approval'
  | 'waiting_input'
  | 'paused'
  | 'private'

/** One agent and what it is doing now (GET /api/orchestrator/team-room). */
export type AgentPresence = {
  id: string
  name: string
  category: string | null
  description: string | null
  fallback: boolean
  agentStatus: string
  state: PresenceState
  since: string | null
  runId: string | null
  goalId: string | null
  goalTitle: string | null
  taskId: string | null
  taskTitle: string | null
  server: string | null
  tool: string | null
  site: string | null
  approvalId: string | null
  questionId: string | null
  waitingText: string | null
  waitingOn: string | null
  waitingForYou: boolean
  activeRuns: number
  queued: number
}

/** Every agent that is not retired, in name order. */
export type TeamRoom = { generatedAt: string; agents: AgentPresence[] }

/** The kinds of line the conversation holds. */
export type FeedKind =
  | 'goal_requested'
  | 'schedule_started'
  | 'task_started'
  | 'task_handed_off'
  | 'task_finished'
  | 'task_failed'
  | 'run_requested'
  | 'run_finished'
  | 'run_failed'
  | 'approval_requested'
  | 'approval_approved'
  | 'approval_rejected'
  | 'approval_sent_back'
  | 'approval_expired'
  | 'question_asked'
  | 'question_answered'

/** One line of the conversation. */
export type FeedEvent = {
  id: string
  cursor: string
  kind: FeedKind
  at: string
  agentId: string | null
  agentName: string | null
  agentCategory: string | null
  agentFallback: boolean
  personId: string | null
  nextAgentId: string | null
  nextAgentName: string | null
  nextAgentCategory: string | null
  nextTaskTitle: string | null
  goalId: string | null
  taskId: string | null
  runId: string | null
  approvalId: string | null
  questionId: string | null
  title: string | null
  text: string | null
  scheduleName: string | null
  /** approval_requested only: it still waits and this viewer may decide it now. */
  forYou: boolean
}

/** One page of the conversation, oldest first. */
export type FeedPage = {
  generatedAt: string
  events: FeedEvent[]
  newestCursor: string | null
  olderCursor: string | null
  hasOlder: boolean
  hasNewer: boolean
}

/** What the page holds of the conversation: the events so far, oldest first, and where to go next. */
export type FeedState = {
  events: FeedEvent[]
  /** Where the next poll starts. */
  newestCursor: string | null
  /** Where "Show earlier" starts; null when nothing older exists. */
  olderCursor: string | null
  hasOlder: boolean
  /** How many events the latest poll added, for the screen-reader announcement. */
  added: number
  /** The newest of the events the latest poll added, which the announcement reads; null when none. */
  latest: FeedEvent | null
  /**
   * The reader loaded earlier lines and has not come back to the newest yet: polls then keep what
   * they loaded (up to HELD_WHILE_READING_BACK) instead of letting the oldest go at MAX_EVENTS.
   */
  keepOlder: boolean
}

/* ---- Polling ------------------------------------------------------------------------------------- */

/** How often the presence is read while the tab is visible. */
export const PRESENCE_POLL_MS = 2_000
/** How often the conversation is read while the tab is visible. */
export const FEED_POLL_MS = 3_000
/** The most events kept in memory; the oldest are let go (and can be loaded again) beyond it. */
export const MAX_EVENTS = 600
/** The most events kept while the reader is reading back through earlier lines they loaded. */
export const HELD_WHILE_READING_BACK = 3_000
/** Pages one poll reads at most to catch up; further behind, it starts again from the newest page. */
export const MAX_CATCH_UP_PAGES = 6
/** Events per page while catching up: the most the server returns in one page. */
export const CATCH_UP_LIMIT = 100

const PRESENCE_KEY = ['team-room', 'presence'] as const
const FEED_KEY = ['team-room', 'feed'] as const
const BASE = '/api/orchestrator/team-room'

// @find: useTeamRoom, team room presence query, poll presence every two seconds
/**
 * Every agent's presence (GET /api/orchestrator/team-room, key ['team-room', 'presence']), read
 * every two seconds while the tab is visible. `live: false` reads it once (the Command Map card).
 */
export function useTeamRoom(options: { enabled?: boolean; live?: boolean } = {}) {
  const live = options.live ?? true
  return useQuery({
    queryKey: PRESENCE_KEY,
    queryFn: ({ signal }) => api<TeamRoom>(BASE, { signal }),
    enabled: options.enabled ?? true,
    refetchInterval: live ? PRESENCE_POLL_MS : false,
    refetchIntervalInBackground: false,
    staleTime: live ? 0 : 30_000,
  })
}

// @find: mergeNewer, merge feed poll, drop repeats by id, keep newest, bounded feed, newest added line
/**
 * Adds a page read after the newest cursor to what the page already holds: repeats (by id) are
 * dropped, the result stays oldest first, and `latest` is the newest of the events that were new
 * (which may sit before lines already shown, when its moment was earlier but it arrived late).
 * Only a poll that added something lets the oldest go: beyond MAX_EVENTS, or beyond
 * HELD_WHILE_READING_BACK while the reader keeps earlier lines they loaded; "Show earlier" can bring
 * them back. Pure.
 */
export function mergeNewer(previous: FeedState | undefined, page: FeedPage): FeedState {
  if (!previous) {
    return {
      events: page.events.slice(-MAX_EVENTS),
      newestCursor: page.newestCursor,
      olderCursor: page.olderCursor,
      hasOlder: page.hasOlder,
      added: 0,
      latest: null,
      keepOlder: false,
    }
  }
  const seen = new Set(previous.events.map((event) => event.id))
  const fresh: FeedEvent[] = []
  for (const event of page.events) {
    if (seen.has(event.id)) continue
    seen.add(event.id)
    fresh.push(event)
  }
  const newestCursor = page.newestCursor ?? previous.newestCursor
  if (fresh.length === 0) {
    return { ...previous, newestCursor, added: 0, latest: null }
  }
  let events = sortEvents([...previous.events, ...fresh])
  let { olderCursor, hasOlder } = previous
  const cap = previous.keepOlder ? HELD_WHILE_READING_BACK : MAX_EVENTS
  if (events.length > cap) {
    events = events.slice(events.length - cap)
    olderCursor = events[0]!.cursor
    hasOlder = true
  }
  return {
    events,
    newestCursor,
    olderCursor,
    hasOlder,
    added: fresh.length,
    latest: sortEvents(fresh)[fresh.length - 1]!,
    keepOlder: previous.keepOlder,
  }
}

// @find: mergeOlder, show earlier, load older feed page
/**
 * Adds an earlier page in front of what the page holds, dropping repeats, and keeps what it added
 * until the reader is back at the newest line (releaseOlder). Pure.
 */
export function mergeOlder(previous: FeedState | undefined, page: FeedPage): FeedState {
  const base: FeedState = previous ?? {
    events: [],
    newestCursor: null,
    olderCursor: null,
    hasOlder: false,
    added: 0,
    latest: null,
    keepOlder: false,
  }
  const seen = new Set(base.events.map((event) => event.id))
  const earlier = page.events.filter((event) => !seen.has(event.id))
  return {
    ...base,
    events: sortEvents([...earlier, ...base.events]),
    olderCursor: page.olderCursor,
    hasOlder: page.hasOlder,
    added: 0,
    latest: null,
    keepOlder: true,
  }
}

// @find: releaseOlder, reader back at the newest line, let earlier lines go again
/** The reader is back at the newest line: later polls may let the oldest lines go again. Pure. */
export function releaseOlder(state: FeedState): FeedState {
  return state.keepOlder ? { ...state, keepOlder: false } : state
}

/** Oldest first by moment, then by id, the order the server pages in. */
function sortEvents(events: FeedEvent[]): FeedEvent[] {
  return [...events].sort((a, b) => {
    const at = Date.parse(a.at) - Date.parse(b.at)
    if (at !== 0) return at
    return a.id < b.id ? -1 : a.id > b.id ? 1 : 0
  })
}

// @find: readNewer, catch up after a hidden tab, read every page since the cursor
/**
 * Everything after `cursor`, as one page: the first read is an ordinary poll, and while the server
 * says more is waiting (hasNewer) the next pages follow at once, CATCH_UP_LIMIT events each. Null
 * when MAX_CATCH_UP_PAGES were not enough, so the caller starts again from the newest page rather
 * than showing old lines as new for minutes.
 */
export async function readNewer(cursor: string, read: (url: string) => Promise<FeedPage>): Promise<FeedPage | null> {
  const events: FeedEvent[] = []
  let next = cursor
  for (let round = 0; round < MAX_CATCH_UP_PAGES; round++) {
    const size = round === 0 ? '' : `&limit=${CATCH_UP_LIMIT}`
    const page = await read(`${BASE}/feed?after=${encodeURIComponent(next)}${size}`)
    events.push(...page.events)
    next = page.newestCursor ?? next
    if (!page.hasNewer) return { ...page, events, newestCursor: next, hasNewer: false }
  }
  return null
}

// @find: useTeamRoomFeed, team room conversation query, poll feed every three seconds, show earlier
/**
 * The conversation (GET /api/orchestrator/team-room/feed, key ['team-room', 'feed']). The first
 * read takes the newest page; each later one, every three seconds while the tab is visible, reads
 * what happened after the cursor the server handed back (readNewer, which catches up page by page
 * after the tab was hidden) and merges it in (mergeNewer). Too far behind, it starts again from the
 * newest page. `loadOlder` fetches the page before the oldest event held and puts it in front
 * (mergeOlder); `releaseOlder` says the reader is back at the newest line.
 */
export function useTeamRoomFeed(options: { enabled?: boolean } = {}) {
  const client = useQueryClient()
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [olderError, setOlderError] = useState<unknown>(null)
  const query = useQuery({
    queryKey: FEED_KEY,
    queryFn: async ({ signal }) => {
      const read = (url: string) => api<FeedPage>(url, { signal })
      const cursor = client.getQueryData<FeedState>(FEED_KEY)?.newestCursor
      if (!cursor) return mergeNewer(client.getQueryData<FeedState>(FEED_KEY), await read(`${BASE}/feed`))
      const page = await readNewer(cursor, read)
      // Too far behind to show line by line: start again from the newest page. What was missed
      // stays one "Show earlier" away, and nothing old is announced as new.
      if (!page) return mergeNewer(undefined, await read(`${BASE}/feed`))
      // Merged into what is held now, not what was held when the read began, so a page of older
      // events loaded meanwhile is kept.
      return mergeNewer(client.getQueryData<FeedState>(FEED_KEY), page)
    },
    enabled: options.enabled ?? true,
    refetchInterval: FEED_POLL_MS,
    refetchIntervalInBackground: false,
    staleTime: 0,
  })

  const loadOlder = useCallback(async () => {
    const held = client.getQueryData<FeedState>(FEED_KEY)
    if (!held?.olderCursor || loadingOlder) return
    setLoadingOlder(true)
    setOlderError(null)
    try {
      const page = await api<FeedPage>(`${BASE}/feed?before=${encodeURIComponent(held.olderCursor)}`)
      client.setQueryData<FeedState>(FEED_KEY, (current) => mergeOlder(current, page))
    } catch (error) {
      setOlderError(error)
    } finally {
      setLoadingOlder(false)
    }
  }, [client, loadingOlder])

  const backAtNewest = useCallback(() => {
    client.setQueryData<FeedState>(FEED_KEY, (current) => (current ? releaseOlder(current) : current))
  }, [client])

  return { ...query, loadOlder, loadingOlder, olderError, releaseOlder: backAtNewest }
}

/* ---- Words ---------------------------------------------------------------------------------------- */

/** How the viewer's own id and the member directory name a person. */
export type PersonNamer = (personId: string | null | undefined) => string

/** Builds a PersonNamer: "You" for the viewer, a member's name when the list has it, else "Someone". */
export function personNamer(me: string | null, names: Record<string, string>): PersonNamer {
  return (personId) => {
    if (!personId) return 'Someone'
    if (me && personId === me) return 'You'
    return names[personId] ?? 'Someone'
  }
}

/** The short label of each state, as a status pill reads it. */
export const STATE_LABEL: Record<PresenceState, string> = {
  idle: 'Idle',
  thinking: 'Thinking',
  browsing: 'Browsing',
  using_tool: 'Using a tool',
  waiting_approval: 'Waiting for approval',
  waiting_input: 'Waiting for an answer',
  paused: 'Paused',
  private: 'Private work',
}

/** Built-in servers that are not connectors a person set up, each with its own wording. */
const BUILT_IN_ACTIVITY: Record<string, string> = {
  knowledge: 'Searching your documents',
  memory: 'Checking its notes',
  person: 'Writing a question',
  schedule: 'Setting up a schedule',
}

// @find: presenceCaption, presence caption, what the agent is doing in words, Browsing site, Using connector, Waiting for you
/**
 * One short line saying what an agent is doing, for its card and the screen reader: "Idle",
 * "Thinking", "Browsing youtube.com", "Using Slack", "Waiting for you to approve", "Waiting for
 * Sam Lee to answer", "Paused", "Busy with private work". An approval never names who must decide
 * it: anyone holding the permission may, and the person who asked for the work may be exactly the
 * one a four-eyes rule stops. Pure.
 */
export function presenceCaption(presence: AgentPresence, nameOf: PersonNamer): string {
  switch (presence.state) {
    case 'thinking':
      return 'Thinking'
    case 'browsing':
      return presence.site ? `Browsing ${presence.site}` : 'Browsing the web'
    case 'using_tool': {
      const server = presence.server ?? ''
      return BUILT_IN_ACTIVITY[server] ?? `Using ${serverLabel(server)}`
    }
    case 'waiting_approval':
      return presence.waitingForYou ? 'Waiting for you to approve' : 'Waiting for approval'
    case 'waiting_input': {
      if (presence.waitingForYou) return 'Waiting for your answer'
      const who = namedOther(presence.waitingOn, nameOf)
      return who ? `Waiting for ${who} to answer` : 'Waiting for an answer'
    }
    case 'paused':
      return 'Paused'
    case 'private':
      return 'Busy with private work'
    default:
      return 'Idle'
  }
}

/**
 * A known person other than the viewer, by name; null when unknown, or when it is the viewer but
 * they cannot act on it (the work is theirs, yet their role leaves the answer to someone else, so
 * "Waiting for You" would mislead).
 */
function namedOther(personId: string | null, nameOf: PersonNamer): string | null {
  if (!personId) return null
  const name = nameOf(personId)
  return name === 'Someone' || name === 'You' ? null : name
}

/** Whether a state shows the agent at work (for the busy count and the animation). */
export function isBusy(state: PresenceState): boolean {
  return state === 'thinking' || state === 'browsing' || state === 'using_tool' || state === 'private'
}

/** Whether a state waits on a person. */
export function isWaiting(state: PresenceState): boolean {
  return state === 'waiting_approval' || state === 'waiting_input'
}

/** The words of one conversation line: the main sentence and, for some kinds, a quieter detail line. */
export type FeedLine = { text: string; detail: string | null }

/** An agent's name, or "An agent" when it was deleted. */
function agentOf(name: string | null | undefined): string {
  return name && name.trim() ? name : 'An agent'
}

// @find: withoutAgentName, workflow task title, agent name said twice, General Employee started on General Employee
/**
 * A task title without the agent's own name in front. A workflow step with no title of its own is
 * named "<agent>: <first words of the instruction>" (WorkflowService.taskTitle), and a line that
 * already opens with the agent would otherwise say it twice: "General Employee started on General
 * Employee: List three tips". Any other title comes back unchanged. Pure.
 */
export function withoutAgentName(title: string, agentName: string | null | undefined): string {
  const name = agentName?.trim()
  if (!name) return title
  const prefix = `${name}: `
  return title.startsWith(prefix) && title.length > prefix.length ? title.slice(prefix.length) : title
}

// @find: feedLine, feed line, hand-off conversation wording, asked, started on, handed it to, waiting for to approve, finished
/**
 * One event as a line of the team's conversation, written as a person would say it:
 * "Sam Lee asked HR: Hire a designer", "HR started on Screen the applications", "HR finished Screen
 * the applications and handed it to Writer", "HR is waiting for approval: Send the offer" ("for you
 * to approve" only when the server says this viewer may decide it), "You approved: Send the offer",
 * "Writer asked: Which tone?", "You answered Writer's question: Which tone?", "Writer finished:
 * Sent the note". Pure.
 */
export function feedLine(event: FeedEvent, nameOf: PersonNamer): FeedLine {
  const agent = agentOf(event.agentName)
  const person = nameOf(event.personId)
  const title = withoutAgentName(event.title ?? 'a task', event.agentName)
  // An approval's text is the gateway's sentence, which names the raw tool ("using
  // gmail.send_message"); every other screen reads it with the tool's label, and so does this one.
  const text = event.kind.startsWith('approval_') ? readableRequestSentence(event.text ?? '') : (event.text ?? '')
  switch (event.kind) {
    case 'goal_requested':
      return {
        text: event.agentName ? `${person} asked ${event.agentName}: ${title}` : `${person} asked the team: ${title}`,
        detail: null,
      }
    case 'schedule_started':
      return {
        text: event.scheduleName ? `The ${event.scheduleName} schedule started ${title}` : `A schedule started ${title}`,
        detail: null,
      }
    case 'task_started':
      return { text: `${agent} started on ${title}`, detail: null }
    case 'task_handed_off':
      return {
        text: `${agent} finished ${title} and handed it to ${agentOf(event.nextAgentName)}`,
        detail: event.nextTaskTitle ? `Next: ${withoutAgentName(event.nextTaskTitle, event.nextAgentName)}` : null,
      }
    case 'task_finished':
      return { text: text ? `${agent} finished: ${text}` : `${agent} finished ${title}`, detail: null }
    case 'task_failed':
      return { text: `${agent} could not finish ${title}`, detail: text || null }
    case 'run_requested':
      return {
        text: event.personId ? `${person} asked ${agent}: ${title}` : `${agent} started on ${title}`,
        detail: null,
      }
    case 'run_finished':
      return { text: text ? `${agent} finished: ${text}` : `${agent} finished ${title}`, detail: null }
    case 'run_failed':
      return { text: `${agent} could not finish ${title}`, detail: text || null }
    case 'approval_requested':
      // Never "waiting for <the requester>": under a four-eyes rule the requester is the one person
      // who may not decide. The viewer is named only when the server says they may decide it now.
      return {
        text: event.forYou ? `${agent} is waiting for you to approve: ${text}` : `${agent} is waiting for approval: ${text}`,
        detail: null,
      }
    case 'approval_approved':
      return { text: `${person} approved: ${text}`, detail: null }
    case 'approval_rejected':
      return { text: `${person} rejected: ${text}`, detail: null }
    case 'approval_sent_back':
      return { text: `${person} sent it back with feedback: ${text}`, detail: null }
    case 'approval_expired':
      return { text: `No one decided in time, so ${agent} did not go ahead: ${text}`, detail: null }
    case 'question_asked':
      return { text: `${agent} asked: ${text}`, detail: null }
    case 'question_answered':
      return { text: `${person} answered ${agent}'s question: ${text}`, detail: null }
    default:
      return { text: text || title, detail: null }
  }
}

/** Who speaks a line: a person (asked, decided, answered), a schedule, or an agent. */
export function feedSpeaker(event: FeedEvent): 'person' | 'schedule' | 'agent' {
  switch (event.kind) {
    case 'run_requested':
      return event.personId ? 'person' : 'agent'
    case 'goal_requested':
    case 'approval_approved':
    case 'approval_rejected':
    case 'approval_sent_back':
    case 'question_answered':
      return 'person'
    case 'schedule_started':
      return 'schedule'
    default:
      return 'agent'
  }
}

/** Where a line leads, when somewhere: the approval queue, the run, or the goal's tasks. */
export function feedLink(
  event: FeedEvent,
  can: (permission: string) => boolean,
): { href: string; label: string } | null {
  if (event.kind === 'approval_requested' && event.approvalId && can('approval:read')) {
    return { href: `/approvals#approval-${event.approvalId}`, label: 'Review' }
  }
  if (event.runId && can('run:read')) return { href: `/runs/${event.runId}`, label: 'Open run' }
  if (event.goalId && can('task:read')) return { href: `/tasks?goal=${event.goalId}`, label: 'Open goal' }
  return null
}
