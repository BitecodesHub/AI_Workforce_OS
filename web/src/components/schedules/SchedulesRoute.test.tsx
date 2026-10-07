import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Member, Schedule } from '../../lib/queries'
import { RouterProvider } from '../../lib/router'
import { Schedules } from '../../routes/Schedules'
import { clearSession, saveSession } from '../../lib/session'
import { ToastProvider } from '../../lib/toast'

/*
 * The Schedules screen offers each row only the changes its viewer may make: the owner, or anyone
 * who can cancel work. Kept beside the schedule components rather than under routes/, where every
 * file is read as a screen that must open with an eyebrow.
 */

// jsdom has the <dialog> element but not its modal methods.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const ME = 'user-me'
const PRIYA = 'user-priya'

function schedule(overrides: Partial<Schedule> & Pick<Schedule, 'id' | 'name'>): Schedule {
  return {
    agentId: 'agent-1',
    agentName: 'Hana',
    instruction: 'Summarise the week',
    kind: 'recurring',
    cron: '0 0 9 * * *',
    runAt: null,
    timezone: 'Australia/Melbourne',
    description: 'Every day at 9:00 am',
    enabled: true,
    overlapPolicy: 'skip',
    nextRunAt: '2026-10-05T23:00:00Z',
    lastRunAt: null,
    lastStatus: null,
    lastGoalId: null,
    consecutiveFailures: 0,
    pausedReason: null,
    createdBy: ME,
    createdAt: '2026-10-01T00:00:00Z',
    ...overrides,
  }
}

const MEMBERS: Member[] = [
  { userId: ME, displayName: 'Erin Employee', email: 'erin@example.com', role: 'employee', status: 'active' },
  { userId: PRIYA, displayName: 'Priya Shah', email: 'priya@example.com', role: 'manager', status: 'active' },
  { userId: 'user-sam', displayName: 'Sam Lee', email: 'sam@example.com', role: 'employee', status: 'active' },
]

const MINE = schedule({ id: 'sched-mine', name: 'My digest' })
const THEIRS = schedule({ id: 'sched-theirs', name: 'Board pack', createdBy: PRIYA })
const DONE = schedule({
  id: 'sched-done',
  name: 'Send the offsite agenda',
  kind: 'once',
  cron: null,
  runAt: '2026-10-01T05:00:00Z',
  description: 'Once, on 1 October at 3:00 pm',
  enabled: false,
  nextRunAt: null,
  lastRunAt: '2026-10-01T05:00:00Z',
  lastStatus: 'completed',
})

let calls: Array<{ url: string; method: string; body: unknown }>

beforeEach(() => {
  calls = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
      if (url === '/api/schedules' && method === 'GET') return json([MINE, THEIRS, { ...DONE, completed: true, state: 'done' }])
      if (url === '/api/users') return json(MEMBERS)
      if (url.endsWith('/owner') && method === 'PUT') return json({ ...THEIRS, createdBy: 'user-sam' })
      return new Response(null, { status: 404 })
    }),
  )
  vi.stubGlobal('scrollTo', () => {})
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

function signIn(permissions: string[]) {
  saveSession('test-token', {
    userId: ME,
    workspaceId: 'workspace-1',
    permissions,
    displayName: 'Erin Employee',
    email: 'erin@example.com',
    role: 'employee',
  })
}

function renderSchedules() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <Schedules />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
}

/** The table row holding a schedule, found by its name. */
async function rowOf(name: string): Promise<HTMLElement> {
  const link = await screen.findByRole('button', { name })
  const row = link.closest('tr')
  if (!row) throw new Error(`No row for ${name}`)
  return row
}

const EMPLOYEE = ['task:read', 'task:create', 'member:read']
const MANAGER = ['task:read', 'task:create', 'task:cancel', 'member:read']

describe('Schedules row actions', () => {
  it('shows an employee no Edit or Delete on another person’s schedule, and both on their own', async () => {
    signIn(EMPLOYEE)
    renderSchedules()

    const theirs = await rowOf('Board pack')
    expect(within(theirs).queryByRole('button', { name: /Edit Board pack/ })).toBeNull()
    expect(within(theirs).queryByRole('button', { name: /Delete Board pack/ })).toBeNull()
    expect(within(theirs).queryByRole('button', { name: /Run Board pack now/ })).toBeNull()
    expect(within(theirs).queryByRole('button', { name: /Pause Board pack/ })).toBeNull()
    expect(within(theirs).getByText('Only its owner can change it')).toBeInTheDocument()

    const mine = await rowOf('My digest')
    expect(within(mine).getByRole('button', { name: 'Edit My digest' })).toBeInTheDocument()
    expect(within(mine).getByRole('button', { name: 'Delete My digest' })).toBeInTheDocument()
    expect(within(mine).queryByRole('button', { name: /Transfer/ })).toBeNull()
  })

  it('names each schedule’s owner', async () => {
    signIn(EMPLOYEE)
    renderSchedules()

    const theirs = await rowOf('Board pack')
    await waitFor(() => expect(within(theirs).getByText('Priya Shah')).toBeInTheDocument())
    expect(within(await rowOf('My digest')).getByText('You')).toBeInTheDocument()
  })

  it('lets someone who can cancel work change any schedule and hand it to someone else', async () => {
    signIn(MANAGER)
    renderSchedules()

    const theirs = await rowOf('Board pack')
    expect(within(theirs).getByRole('button', { name: 'Edit Board pack' })).toBeInTheDocument()
    expect(within(theirs).getByRole('button', { name: 'Delete Board pack' })).toBeInTheDocument()

    fireEvent.click(within(theirs).getByRole('button', { name: 'Transfer Board pack to another owner' }))
    const select = await screen.findByLabelText(/New owner/)
    await waitFor(() => expect(within(select).getByRole('option', { name: 'Sam Lee' })).toBeInTheDocument())
    // The current owner is not offered as the new one.
    expect(within(select).queryByRole('option', { name: 'Priya Shah' })).toBeNull()
    fireEvent.change(select, { target: { value: 'user-sam' } })
    fireEvent.click(screen.getByRole('button', { name: 'Transfer' }))

    await waitFor(() =>
      expect(calls).toContainEqual({
        url: '/api/schedules/sched-theirs/owner',
        method: 'PUT',
        body: { userId: 'user-sam' },
      }),
    )
  })

  it('reads a one-off that already ran as Done, with Run now and Edit but no Pause or Resume', async () => {
    signIn(EMPLOYEE)
    renderSchedules()

    const done = await rowOf('Send the offsite agenda')
    expect(within(done).getAllByText('Done').length).toBeGreaterThanOrEqual(2)
    expect(within(done).queryByText('Paused')).toBeNull()
    expect(within(done).queryByRole('button', { name: /Resume/ })).toBeNull()
    expect(within(done).queryByRole('button', { name: /Pause/ })).toBeNull()
    expect(within(done).getByRole('button', { name: 'Run Send the offsite agenda now' })).toBeInTheDocument()
    expect(within(done).getByRole('button', { name: 'Edit Send the offsite agenda' })).toBeInTheDocument()
  })

  it('gives a viewer who cannot create work no actions column at all', async () => {
    signIn(['task:read', 'member:read'])
    renderSchedules()

    await rowOf('Board pack')
    expect(screen.queryByRole('columnheader', { name: 'Actions' })).toBeNull()
  })
})
