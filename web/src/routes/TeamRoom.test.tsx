// @find: tests for the team room page, presence per state, feed per event kind, empty loading error permission states, agent sheet, private work, reduced motion, 375 px layout, touch targets, caption contrast, polite announcement, announcer mounted while empty, feed survives a failed poll, TeamRoom page
// @what: Component tests for the Team room page (/team-room): every presence state and event kind on screen, its four data states, the agent sheet, announcements, and the motion, touch, contrast and narrow-screen rules of its styles.
// @flow: Renders TeamRoom inside QueryClientProvider and RouterProvider with fetch stubbed by address; renders TeamFeed directly for announcements.
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FeedAnnouncer, TeamFeed, personInitials } from '../components/team-room/TeamFeed'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { personNamer } from '../lib/teamRoomQueries'
import type { AgentPresence, FeedEvent, FeedKind, FeedPage, FeedState, TeamRoom as Room } from '../lib/teamRoomQueries'
import { TeamRoom } from './TeamRoom'

/*
 * The team room as a person sees it: each agent's card says what it is doing (and only the waiting
 * ones that wait on the viewer offer a way to act), the conversation reads top to bottom with the
 * newest last, and each part has its own loading, empty, error and permission states. Opening an
 * agent shows its current run; private work opens nothing. Motion is CSS and stops when the
 * viewer asks for less, and the layout is one column until the screen is wide.
 */

// jsdom has the <dialog> element but not its methods; the agent sheet opens with one of them.
if (typeof HTMLDialogElement !== 'undefined') {
  const proto = HTMLDialogElement.prototype as HTMLDialogElement & Record<string, unknown>
  if (!proto.showModal) {
    proto.showModal = function showModal(this: HTMLDialogElement) {
      this.setAttribute('open', '')
    }
  }
  if (!proto.show) {
    proto.show = function show(this: HTMLDialogElement) {
      this.setAttribute('open', '')
    }
  }
  if (!proto.close) {
    proto.close = function close(this: HTMLDialogElement) {
      this.removeAttribute('open')
      this.dispatchEvent(new Event('close'))
    }
  }
}

const ME = 'user-me'

function agent(id: string, name: string, overrides: Partial<AgentPresence> = {}): AgentPresence {
  return {
    id,
    name,
    category: 'operations',
    description: `${name} does useful work.`,
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

const SINCE = '2026-10-12T09:58:00Z'

const EVERY_STATE: AgentPresence[] = [
  agent('a-ada', 'Ada', { queued: 2 }),
  agent('a-ben', 'Ben', { state: 'thinking', runId: 'run-ben', taskTitle: 'Draft the weekly report', since: SINCE, activeRuns: 1 }),
  agent('a-cy', 'Cy', { state: 'browsing', runId: 'run-cy', server: 'browser', tool: 'open_page', site: 'youtube.com', since: SINCE, activeRuns: 1 }),
  agent('a-dee', 'Dee', { state: 'using_tool', runId: 'run-dee', server: 'slack', tool: 'post_message', since: SINCE, activeRuns: 2 }),
  agent('a-eve', 'Eve', {
    state: 'waiting_approval',
    runId: 'run-eve',
    approvalId: 'appr-1',
    waitingText: 'Send the offer letter',
    waitingForYou: true,
    since: SINCE,
    activeRuns: 1,
  }),
  agent('a-fay', 'Fay', { state: 'waiting_input', runId: 'run-fay', questionId: 'q-1', waitingText: 'Which day?', waitingOn: 'user-other', since: SINCE, activeRuns: 1 }),
  agent('a-gus', 'Gus', { state: 'paused', agentStatus: 'paused' }),
  agent('a-hal', 'Hal', { state: 'private', since: SINCE, activeRuns: 1 }),
]

function feedEvent(index: number, kind: FeedKind, overrides: Partial<FeedEvent> = {}): FeedEvent {
  return {
    id: `ev-${index}`,
    cursor: `cursor-${index}`,
    kind,
    at: new Date(Date.UTC(2026, 9, 12, 9, 0, index)).toISOString(),
    agentId: 'a-ben',
    agentName: 'Ben',
    agentCategory: 'operations',
    agentFallback: false,
    personId: ME,
    nextAgentId: null,
    nextAgentName: null,
    nextAgentCategory: null,
    nextTaskTitle: null,
    goalId: 'goal-1',
    taskId: 'task-1',
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

const CONVERSATION: FeedEvent[] = [
  feedEvent(1, 'goal_requested', { title: 'Hire a designer' }),
  feedEvent(2, 'schedule_started', { title: 'Monday digest', scheduleName: 'Morning digest', personId: null }),
  feedEvent(3, 'task_started', { title: 'Screen the applications' }),
  feedEvent(4, 'task_handed_off', {
    title: 'Screen the applications',
    nextAgentId: 'a-cy',
    nextAgentName: 'Cy',
    nextTaskTitle: 'Write the shortlist note',
  }),
  feedEvent(5, 'approval_requested', {
    agentName: 'Cy',
    text: 'Send the shortlist',
    approvalId: 'appr-9',
    runId: 'run-cy',
    forYou: true,
  }),
  feedEvent(6, 'approval_approved', { agentName: 'Cy', text: 'Send the shortlist' }),
  feedEvent(7, 'question_asked', { agentName: 'Cy', text: 'Which tone should it take?', runId: 'run-cy' }),
  feedEvent(8, 'question_answered', { agentName: 'Cy', text: 'Which tone should it take?' }),
  feedEvent(9, 'task_failed', { agentName: 'Cy', title: 'Book the interviews', text: 'The calendar refused.' }),
  feedEvent(10, 'task_finished', { agentName: 'Cy', title: 'Write the note', text: 'Sent the shortlist to the manager.' }),
  feedEvent(11, 'run_requested', { agentName: 'Dee', title: 'Find the newest applications', goalId: null, runId: 'run-dee' }),
  feedEvent(12, 'run_finished', { agentName: 'Dee', title: 'Find them', text: 'Three found.', goalId: null, runId: 'run-dee' }),
  feedEvent(13, 'run_failed', {
    agentName: 'Dee',
    title: 'Book a room',
    text: 'The calendar refused.',
    goalId: null,
    runId: 'run-dee-2',
  }),
  feedEvent(14, 'approval_requested', { agentName: 'Eve', text: 'Refund the order', approvalId: 'appr-10', personId: ME }),
]

function feedPage(events: FeedEvent[], overrides: Partial<FeedPage> = {}): FeedPage {
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

type Reply = { status: number; body: unknown } | 'pending'

let presenceReply: Reply
let feedReply: Reply
let requests: string[]

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function problem(status: number, code: string): Response {
  return json(status, { status, code, title: 'Refused', detail: 'Refused.', retryable: status >= 500 })
}

function answer(reply: Reply): Promise<Response> {
  if (reply === 'pending') return new Promise(() => {})
  if (reply.status >= 400) return Promise.resolve(problem(reply.status, reply.status === 403 ? 'permission_denied' : 'internal'))
  return Promise.resolve(json(reply.status, reply.body))
}

function signIn(permissions: string[]) {
  saveSession('test-token', {
    userId: ME,
    workspaceId: 'workspace-1',
    permissions,
    displayName: 'Maya Manager',
    email: 'maya@demo.test',
    role: 'manager',
  })
}

beforeEach(() => {
  requests = []
  presenceReply = { status: 200, body: { generatedAt: '2026-10-12T10:00:00Z', agents: EVERY_STATE } satisfies Room }
  feedReply = { status: 200, body: feedPage(CONVERSATION) }
  signIn(['run:read', 'approval:read', 'task:read', 'agent:read'])
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
      requests.push(url)
      if (url.startsWith('/api/orchestrator/team-room/feed')) return answer(feedReply)
      if (url === '/api/orchestrator/team-room') return answer(presenceReply)
      if (url.startsWith('/api/runs/run-ben/steps')) return answer({ status: 200, body: [] })
      if (url.startsWith('/api/runs/run-ben')) {
        return answer({
          status: 200,
          body: {
            id: 'run-ben',
            agentId: 'a-ben',
            status: 'running',
            trigger: 'task',
            stepCount: 0,
            promptTokens: 0,
            completionTokens: 0,
            cost: 0,
            startedAt: SINCE,
          },
        })
      }
      return answer({ status: 200, body: [] })
    }),
  )
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

function renderPage(client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })) {
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <RouterProvider>
          <TeamRoom />
        </RouterProvider>
      </QueryClientProvider>,
    ),
  }
}

const card = (name: string) => screen.getByRole('button', { name: new RegExp(`^${name}\\b`) }).closest('li')!

describe('Team room page', () => {
  it('opens with its eyebrow and shows loading states while the reads are under way', () => {
    presenceReply = 'pending'
    feedReply = 'pending'
    renderPage()
    expect(screen.getByText('Your AI team, live')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Team room' })).toBeInTheDocument()
    expect(screen.getByText('Loading the team')).toBeInTheDocument()
    expect(screen.getByText('Loading the team conversation')).toBeInTheDocument()
  })

  it('shows every agent with what it is doing, and counts who is at work and who waits', async () => {
    renderPage()
    await screen.findByRole('list', { name: 'Agents and what they are doing' })

    const expected: Array<[string, string, string]> = [
      ['Ada', 'idle', 'Idle'],
      ['Ben', 'thinking', 'Thinking'],
      ['Cy', 'browsing', 'Browsing youtube.com'],
      ['Dee', 'using_tool', 'Using Slack'],
      ['Eve', 'waiting_approval', 'Waiting for you to approve'],
      ['Fay', 'waiting_input', 'Waiting for an answer'],
      ['Gus', 'paused', 'Paused'],
      ['Hal', 'private', 'Busy with private work'],
    ]
    for (const [name, state, caption] of expected) {
      const item = card(name)
      expect(item).toHaveAttribute('data-state', state)
      expect(within(item).getByText(caption)).toBeInTheDocument()
    }
    expect(within(card('Ada')).getByText('2 tasks waiting to start')).toBeInTheDocument()
    expect(within(card('Ben')).getByText('On: Draft the weekly report')).toBeInTheDocument()
    expect(within(card('Dee')).getByText(/1 more piece of work/)).toBeInTheDocument()
    expect(within(card('Fay')).getByText('Which day?')).toBeInTheDocument()

    // Only the agent waiting on this viewer offers a way to act, and it goes to the approval.
    const act = within(card('Eve')).getByRole('link', { name: 'Review and decide for Eve' })
    expect(act).toHaveAttribute('href', '/approvals#approval-appr-1')
    expect(within(card('Fay')).queryByRole('link')).not.toBeInTheDocument()
    expect(within(card('Hal')).queryByText(/On:/)).not.toBeInTheDocument()

    expect(screen.getByText('4 at work')).toBeInTheDocument()
    expect(screen.getByText('2 waiting on a person')).toBeInTheDocument()
    expect(screen.getByText('1 waiting for you')).toBeInTheDocument()
  })

  it('writes the conversation as a team chat, oldest first and newest at the bottom', async () => {
    renderPage()
    const region = await screen.findByRole('region', { name: 'Team conversation, newest at the bottom' })
    const lines = within(region)
      .getAllByRole('listitem')
      .map((item) => item.querySelector('.tr-line-text')?.textContent)
    expect(lines).toEqual([
      'You asked Ben: Hire a designer',
      'The Morning digest schedule started Monday digest',
      'Ben started on Screen the applications',
      'Ben finished Screen the applications and handed it to Cy',
      'Cy is waiting for you to approve: Send the shortlist',
      'You approved: Send the shortlist',
      'Cy asked: Which tone should it take?',
      "You answered Cy's question: Which tone should it take?",
      'Cy could not finish Book the interviews',
      'Cy finished: Sent the shortlist to the manager.',
      'You asked Dee: Find the newest applications',
      'Dee finished: Three found.',
      'Dee could not finish Book a room',
      // The viewer asked for this work but the server does not say they may decide it: never "you".
      'Eve is waiting for approval: Refund the order',
    ])
    expect(within(region).getByText('Next: Write the shortlist note')).toBeInTheDocument()
    expect(within(region).getAllByText('The calendar refused.')).toHaveLength(2)
    expect(within(region).getAllByRole('link', { name: 'Review' })[0]).toHaveAttribute('href', '/approvals#approval-appr-9')
    expect(within(region).getAllByRole('link', { name: 'Open run' }).map((link) => link.getAttribute('href'))).toContain(
      '/runs/run-dee',
    )
    expect(within(region).getByText("This is where the team's conversation begins.")).toBeInTheDocument()
    // The first page is shown, not announced.
    const status = document.querySelector('.tr-feed-announcer')!
    expect(status).toHaveAttribute('aria-live', 'polite')
    expect(status.textContent).toBe('')
  })

  it('keeps its announcer mounted while the conversation is empty, and announces the first lines that arrive', async () => {
    feedReply = { status: 200, body: feedPage([]) }
    const { client } = renderPage()
    expect(await screen.findByText('Nothing has happened yet')).toBeInTheDocument()
    const status = document.querySelector('.tr-feed-announcer')!
    expect(status).toHaveAttribute('role', 'status')
    expect(status.textContent).toBe('')

    feedReply = { status: 200, body: feedPage(CONVERSATION.slice(0, 2), { newestCursor: 'next' }) }
    await act(() => client.refetchQueries({ queryKey: ['team-room', 'feed'] }))

    await waitFor(() =>
      expect(status.textContent).toBe('2 new updates. Latest: The Morning digest schedule started Monday digest'),
    )
    // The same element, already known to the screen reader, carries the announcement.
    expect(document.querySelector('.tr-feed-announcer')).toBe(status)
    expect(requests).toContain('/api/orchestrator/team-room/feed?after=newest')
  })

  it('keeps the conversation mounted through a failed poll and its recovery, so the reader keeps their place', async () => {
    const { client } = renderPage()
    const region = await screen.findByRole('region', { name: 'Team conversation, newest at the bottom' })
    const firstLine = within(region).getAllByRole('listitem')[0]

    feedReply = { status: 500, body: null }
    await act(() => client.refetchQueries({ queryKey: ['team-room', 'feed'] }).catch(() => {}))
    expect(await screen.findByText(/Lost connection/)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Team conversation, newest at the bottom' })).toBe(region)

    feedReply = { status: 200, body: feedPage([]) }
    await act(() => client.refetchQueries({ queryKey: ['team-room', 'feed'] }))
    await waitFor(() => expect(screen.queryByText(/Lost connection/)).not.toBeInTheDocument())
    expect(screen.getByRole('region', { name: 'Team conversation, newest at the bottom' })).toBe(region)
    expect(within(region).getAllByRole('listitem')[0]).toBe(firstLine)
  })

  it('says so when there are no agents and nothing has happened yet', async () => {
    presenceReply = { status: 200, body: { generatedAt: '2026-10-12T10:00:00Z', agents: [] } }
    feedReply = { status: 200, body: feedPage([]) }
    renderPage()
    expect(await screen.findByText('No agents yet')).toBeInTheDocument()
    expect(await screen.findByText('Nothing has happened yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to Agents' })).toHaveAttribute('href', '/agents')
  })

  it('shows an error for a failed read and the role notice for a refused one', async () => {
    presenceReply = { status: 500, body: null }
    feedReply = { status: 403, body: null }
    renderPage()
    expect(await screen.findByText('Your role does not include this')).toBeInTheDocument()
    expect(await screen.findByText('This could not be loaded')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('opens what an agent is doing: its current run, with the way to the full trace', async () => {
    renderPage()
    await screen.findByRole('list', { name: 'Agents and what they are doing' })
    fireEvent.click(screen.getByRole('button', { name: /^Ben\b/ }))

    const sheet = await screen.findByRole('dialog')
    expect(within(sheet).getByRole('heading', { name: 'Ben' })).toBeInTheDocument()
    expect(await within(sheet).findByRole('link', { name: 'Open full trace' })).toHaveAttribute('href', '/runs/run-ben')
    expect(within(sheet).getByRole('link', { name: 'Open run' })).toHaveAttribute('href', '/runs/run-ben')
    expect(requests).toContain('/api/runs/run-ben')
  })

  it('opens nothing of private work: the sheet only says the agent is busy', async () => {
    renderPage()
    await screen.findByRole('list', { name: 'Agents and what they are doing' })
    fireEvent.click(screen.getByRole('button', { name: /^Hal\b/ }))

    const sheet = await screen.findByRole('dialog')
    expect(within(sheet).getByText(/working on something from a private conversation/)).toBeInTheDocument()
    expect(within(sheet).queryByRole('link', { name: 'Open run' })).not.toBeInTheDocument()
    expect(requests.some((url) => url.startsWith('/api/runs/'))).toBe(false)
  })

  it('reports reduced motion to its styles when the viewer asks for less motion', async () => {
    vi.stubGlobal(
      'matchMedia',
      vi.fn((query: string) => ({
        matches: query.includes('prefers-reduced-motion'),
        media: query,
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
        onchange: null,
        dispatchEvent: () => false,
      })),
    )
    const { container } = renderPage()
    await screen.findByRole('list', { name: 'Agents and what they are doing' })
    expect(container.querySelector('.tr-layout')).toHaveAttribute('data-motion', 'reduced')
  })

  it('animates when the viewer has not asked for less motion', async () => {
    vi.stubGlobal(
      'matchMedia',
      vi.fn((query: string) => ({
        matches: false,
        media: query,
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
        onchange: null,
        dispatchEvent: () => false,
      })),
    )
    const { container } = renderPage()
    await screen.findByRole('list', { name: 'Agents and what they are doing' })
    expect(container.querySelector('.tr-layout')).toHaveAttribute('data-motion', 'full')
  })
})

/* ---- A small reader of the stylesheets, for rules the browser would apply ---------------------- */

type CssRule = { selectors: string[]; body: string; media: string[] }

/** Every style rule of a stylesheet with the @media blocks it sits in; keyframes are left out. */
function cssRules(css: string): CssRule[] {
  const text = css.replace(/\/\*[\s\S]*?\*\//g, '')
  const out: CssRule[] = []
  const walk = (source: string, media: string[]) => {
    let index = 0
    while (index < source.length) {
      const open = source.indexOf('{', index)
      if (open < 0) break
      const prelude = source.slice(index, open).trim()
      let depth = 1
      let end = open + 1
      while (end < source.length && depth > 0) {
        if (source[end] === '{') depth++
        else if (source[end] === '}') depth--
        end++
      }
      const inner = source.slice(open + 1, end - 1)
      if (prelude.startsWith('@media')) walk(inner, [...media, prelude])
      else if (!prelude.startsWith('@')) out.push({ selectors: prelude.split(',').map((part) => part.trim()), body: inner, media })
      index = end
    }
  }
  walk(text, [])
  return out
}

/** The value of one declaration in a rule body, or null. */
function declaration(body: string, property: string): string | null {
  const match = new RegExp(`(?:^|;|\\s)${property}\\s*:\\s*([^;]+);`).exec(body)
  return match ? match[1]!.trim() : null
}

type Rgb = [number, number, number]

/** The light and dark values of the colour tokens, from tokens.css. */
function themeTokens(): Record<'light' | 'dark', Record<string, string>> {
  const tokens = readFileSync(join(__dirname, '../styles/tokens.css'), 'utf8')
  const block = (start: string) => {
    const from = tokens.indexOf(start)
    const body = tokens.slice(tokens.indexOf('{', from) + 1, tokens.indexOf('\n}', from))
    return Object.fromEntries([...body.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map((match) => [match[1]!, match[2]!.trim()]))
  }
  const light = block(':root {')
  return { light, dark: { ...light, ...block(":root[data-theme='dark'] {") } }
}

/** A colour as the browser computes it: hex, a token, or color-mix in srgb of two of them. */
function resolveColor(value: string, tokens: Record<string, string>): Rgb {
  const text = value.trim()
  const variable = /^var\((--[\w-]+)\)$/.exec(text)
  if (variable) return resolveColor(tokens[variable[1]!]!, tokens)
  const hex = /^#([0-9a-f]{6})$/i.exec(text)
  if (hex) return [0, 2, 4].map((at) => parseInt(hex[1]!.slice(at, at + 2), 16)) as Rgb
  const mix = /^color-mix\(in srgb, (.+?) (\d+)%, (.+)\)$/.exec(text)
  if (mix) {
    const first = resolveColor(mix[1]!, tokens)
    const second = resolveColor(mix[3]!, tokens)
    const share = Number(mix[2]) / 100
    return first.map((channel, at) => channel * share + second[at]! * (1 - share)) as Rgb
  }
  throw new Error(`Cannot resolve colour ${text}`)
}

/** WCAG contrast ratio of two colours. */
function contrast(a: Rgb, b: Rgb): number {
  const luminance = (rgb: Rgb) => {
    const [r, g, bl] = rgb.map((channel) => {
      const c = channel / 255
      return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
    })
    return 0.2126 * r! + 0.7152 * g! + 0.0722 * bl!
  }
  const [high, low] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (high! + 0.05) / (low! + 0.05)
}

describe('Team room styles', () => {
  const css = readFileSync(join(__dirname, '../styles/polish/team-room.css'), 'utf8')
  const rules = cssRules(css)
  const ruleFor = (selector: string, media: string[] = []) =>
    rules.find((rule) => rule.selectors.includes(selector) && media.every((query) => rule.media.includes(query)))

  it('lays the page out in one column until the screen is wide, so 375 px never scrolls sideways', async () => {
    const { container } = renderPage()
    await screen.findByRole('list', { name: 'Agents and what they are doing' })
    expect(container.querySelector('.tr-layout .tr-presence .tr-agents')).toBeInTheDocument()
    expect(container.querySelector('.tr-layout .tr-feed .tr-feed-scroll')).toBeInTheDocument()

    const base = /\.tr-layout \{[^}]*\}/.exec(css)?.[0] ?? ''
    expect(base).toContain('grid-template-columns: minmax(0, 1fr);')
    expect(css).toMatch(/@media \(min-width: 1024px\) \{\s*\.tr-layout \{\s*grid-template-columns:/)
    // A card is never wider than the screen: the column minimum gives way below 240 px.
    expect(css).toContain('grid-template-columns: repeat(auto-fill, minmax(min(100%, 240px), 1fr));')
  })

  it('lets a caption with no break points wrap, so a long site name never widens the page', () => {
    for (const selector of ['.tr-agent-caption', '.tr-sheet-caption']) {
      const rule = rules.find((candidate) => candidate.selectors.includes(selector) && /overflow-wrap/.test(candidate.body))
      expect(rule, selector).toBeDefined()
      expect(declaration(rule!.body, 'overflow-wrap')).toBe('anywhere')
      expect(declaration(rule!.body, 'min-width')).toBe('0')
    }
    expect(declaration(ruleFor('.tr-agent-state')!.body, 'flex-wrap')).toBe('wrap')
  })

  it('declares motion only for viewers who have not asked for less, and never inside a reduced-motion page', () => {
    const NO_PREFERENCE = '@media (prefers-reduced-motion: no-preference)'
    const moving = rules.filter((rule) => /(?:^|;|\s)(animation|transition)(-[a-z-]+)?\s*:/.test(rule.body))
    expect(moving.length).toBeGreaterThan(5)
    for (const rule of moving) {
      expect(rule.media, rule.selectors.join(', ')).toContain(NO_PREFERENCE)
      for (const selector of rule.selectors) {
        expect(selector).toContain(":not([data-motion='reduced'] *)")
      }
    }
    // Every state that moves still has its motion, for viewers who want it.
    const animated = [...css.matchAll(/animation: (tr-[a-z-]+)/g)].map((match) => match[1])
    expect(new Set(animated)).toEqual(new Set(['tr-breathe', 'tr-orbit', 'tr-pulse', 'tr-glow', 'tr-dot', 'tr-line-in']))
    for (const [selector, name] of [
      [".tr-agent[data-state='thinking'] .tr-agent-halo:not([data-motion='reduced'] *)", 'tr-breathe'],
      [".tr-agent[data-state='using_tool'] .tr-agent-halo:not([data-motion='reduced'] *)", 'tr-pulse'],
      [".tr-agent[data-state='waiting_approval'] .tr-agent-badge:not([data-motion='reduced'] *)", 'tr-glow'],
      [".tr-sheet-status[data-state='thinking'] .tr-agent-halo:not([data-motion='reduced'] *)", 'tr-breathe'],
    ] as const) {
      expect(declaration(ruleFor(selector, [NO_PREFERENCE])!.body, 'animation')).toContain(name)
    }
  })

  it('gives the ways to act a 44 px target on a touch screen', () => {
    for (const selector of ['.tr-agent-action', '.tr-line-meta .link']) {
      const rule = ruleFor(selector, ['@media (pointer: coarse)'])
      expect(rule, selector).toBeDefined()
      expect(declaration(rule!.body, 'min-height')).toBe('44px')
    }
  })

  it('keeps caption and link text inside every conversation bubble at 4.5:1 or more, in both themes', () => {
    const bubbles = [
      '.tr-line-bubble',
      ".tr-line[data-speaker='person'] .tr-line-bubble",
      ".tr-line[data-kind='approval_requested'] .tr-line-bubble",
      '.tr-agent[data-for-you]',
    ]
    const themes = themeTokens()
    for (const [theme, tokens] of Object.entries(themes)) {
      for (const selector of bubbles) {
        const background = declaration(ruleFor(selector)!.body, 'background')!
        const fill = resolveColor(background, tokens)
        for (const text of ['var(--muted)', 'var(--blue)']) {
          const ratio = contrast(resolveColor(text, tokens), fill)
          expect(ratio, `${theme} ${text} on ${selector}`).toBeGreaterThanOrEqual(4.5)
        }
      }
    }
  })
})

describe('Team conversation announcements', () => {
  const nameOf = personNamer(ME, {})
  const base: FeedState = {
    events: CONVERSATION.slice(0, 3),
    newestCursor: 'n',
    olderCursor: null,
    hasOlder: false,
    added: 0,
    latest: null,
    keepOlder: false,
  }

  function renderFeed(state: FeedState, onReachBottom?: () => void) {
    return render(
      <RouterProvider>
        <TeamFeed
          state={state}
          nameOf={nameOf}
          onLoadOlder={() => {}}
          loadingOlder={false}
          olderError={null}
          onReachBottom={onReachBottom}
        />
      </RouterProvider>,
    )
  }

  it('announces the newest line a poll added, politely, and nothing otherwise', () => {
    const { rerender } = render(<FeedAnnouncer state={undefined} nameOf={nameOf} />)
    const status = screen.getByRole('status')
    expect(status).toHaveAttribute('aria-live', 'polite')
    expect(status.textContent).toBe('')

    rerender(<FeedAnnouncer state={base} nameOf={nameOf} />)
    expect(status.textContent).toBe('')

    rerender(
      <FeedAnnouncer state={{ ...base, events: CONVERSATION.slice(0, 4), added: 1, latest: CONVERSATION[3]! }} nameOf={nameOf} />,
    )
    expect(status.textContent).toBe('Ben finished Screen the applications and handed it to Cy')

    rerender(
      <FeedAnnouncer state={{ ...base, events: CONVERSATION.slice(0, 6), added: 2, latest: CONVERSATION[5]! }} nameOf={nameOf} />,
    )
    expect(status.textContent).toBe('2 new updates. Latest: You approved: Send the shortlist')

    // A line that arrived late, with an earlier moment, is the one announced, not the last on screen.
    rerender(
      <FeedAnnouncer state={{ ...base, events: CONVERSATION.slice(0, 6), added: 1, latest: CONVERSATION[1]! }} nameOf={nameOf} />,
    )
    expect(status.textContent).toBe('The Morning digest schedule started Monday digest')
  })

  it('says when the reader comes back down to the newest line, once each time', () => {
    const onReachBottom = vi.fn()
    const geometry = { scrollHeight: 1000, clientHeight: 300, scrollTop: 700 }
    const height = vi.spyOn(Element.prototype, 'scrollHeight', 'get').mockImplementation(() => geometry.scrollHeight)
    const client = vi.spyOn(Element.prototype, 'clientHeight', 'get').mockImplementation(() => geometry.clientHeight)
    const top = vi.spyOn(Element.prototype, 'scrollTop', 'get').mockImplementation(() => geometry.scrollTop)
    const setTop = vi.spyOn(Element.prototype, 'scrollTop', 'set').mockImplementation(() => {})
    try {
      renderFeed({ ...base, keepOlder: true }, onReachBottom)
      const box = screen.getByRole('region', { name: 'Team conversation, newest at the bottom' })

      geometry.scrollTop = 0
      fireEvent.scroll(box)
      expect(onReachBottom).not.toHaveBeenCalled()

      geometry.scrollTop = 700
      fireEvent.scroll(box)
      fireEvent.scroll(box)
      expect(onReachBottom).toHaveBeenCalledTimes(1)
    } finally {
      height.mockRestore()
      client.mockRestore()
      top.mockRestore()
      setTop.mockRestore()
    }
  })

  it('opens at the newest line, and follows new lines while the reader is at the bottom', () => {
    const set: number[] = []
    const height = vi.spyOn(Element.prototype, 'scrollHeight', 'get').mockReturnValue(800)
    const top = vi.spyOn(Element.prototype, 'scrollTop', 'set').mockImplementation((value: number) => {
      set.push(value)
    })
    try {
      const { rerender } = renderFeed(base)
      expect(set).toEqual([800])

      height.mockReturnValue(900)
      rerender(
        <RouterProvider>
          <TeamFeed
            state={{ ...base, events: CONVERSATION.slice(0, 4), added: 1 }}
            nameOf={nameOf}
            onLoadOlder={() => {}}
            loadingOlder={false}
            olderError={null}
          />
        </RouterProvider>,
      )
      expect(set).toEqual([800, 900])
    } finally {
      height.mockRestore()
      top.mockRestore()
    }
  })

  it('offers earlier lines when there are some, and loads them on request', async () => {
    const onLoadOlder = vi.fn()
    render(
      <RouterProvider>
        <TeamFeed
          state={{ ...base, hasOlder: true, olderCursor: 'c0' }}
          nameOf={nameOf}
          onLoadOlder={onLoadOlder}
          loadingOlder={false}
          olderError={null}
        />
      </RouterProvider>,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Show earlier' }))
    await waitFor(() => expect(onLoadOlder).toHaveBeenCalledTimes(1))
  })

  // Seen live: every line the viewer spoke showed "YO", the first letters of "You".
  it("puts the viewer's own initials beside their lines, a member's beside theirs, and none for someone unknown", () => {
    const named = personNamer(ME, { 'user-sam': 'Sam Lee' })
    const events = [
      feedEvent(1, 'goal_requested', { title: 'Hire a designer' }),
      feedEvent(2, 'approval_approved', { personId: 'user-sam', text: 'Send the shortlist' }),
      feedEvent(3, 'question_answered', { personId: 'user-gone', text: 'Which tone?' }),
    ]
    render(
      <RouterProvider>
        <TeamFeed
          state={{ ...base, events }}
          nameOf={named}
          onLoadOlder={() => {}}
          loadingOlder={false}
          olderError={null}
        />
      </RouterProvider>,
    )
    const circles = [...document.querySelectorAll('.tr-line-person')].map((circle) => circle.textContent)
    expect(circles).toEqual(['MM', 'SL', ''])
    expect(personInitials('You')).toBe('MM')
    clearSession()
    expect(personInitials('You')).toBe('')
  })
})
