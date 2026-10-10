// @find: tests for the approvals page, approve, reject, queue, history, accessibility axe, vitest, Approvals component tests, Approvals page
// @what: Automated tests that check the the approvals page screen (/approvals) behaves as users expect.
// @flow: Renders Approvals from Approvals.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import axe from 'axe-core'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Approvals } from './Approvals'
import type { ApprovalItem } from '../lib/approvalQueries'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'

/*
 * The decision screen as a manager meets it: the email shown as an email with the goal and the
 * person behind it, similar requests decided together without sweeping up a deletion, a history
 * the server keeps, and a request the requester cannot decide for themselves.
 */

// jsdom has the <dialog> element but not its modal methods; every dialog here opens with one.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const ME = 'user-1'

function item(id: string, overrides: Partial<ApprovalItem> = {}): ApprovalItem {
  return {
    id,
    runId: `run-${id}`,
    agentId: 'agent-1',
    tool: 'gmail.send_message',
    actionClass: 'OUTBOUND',
    summary: 'Send something outside the workspace using gmail.send_message',
    payload: JSON.stringify({ to: 'jane@customer.example', subject: 'Your welcome pack', body: 'Hello Jane,\nWelcome aboard.' }),
    status: 'pending',
    requestedAt: '2026-10-04T09:00:00Z',
    expiresAt: '2026-10-05T09:00:00Z',
    decidedBy: null,
    decidedAt: null,
    decisionNote: null,
    goalId: 'goal-1',
    goalTitle: 'Welcome the new starter',
    requestedBy: 'user-2',
    taskInstruction: 'Email the new starter their welcome pack.',
    canDecide: true,
    ...overrides,
  }
}

type Reply = { status?: number; body: unknown }
let routes: Array<{ match: (url: string, method: string) => boolean; reply: (url: string, init?: RequestInit) => Reply }>
let requests: Array<{ url: string; method: string; body: unknown }>

function on(match: (url: string, method: string) => boolean, reply: (url: string, init?: RequestInit) => Reply | unknown) {
  routes.unshift({
    match,
    reply: (url, init) => {
      const answer = reply(url, init)
      return typeof answer === 'object' && answer !== null && 'body' in answer && Object.keys(answer).every((key) => key === 'body' || key === 'status')
        ? (answer as Reply)
        : { body: answer }
    },
  })
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

function renderPage(url = '/approvals') {
  window.history.replaceState({}, '', url)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <Approvals />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  requests = []
  routes = [{ match: () => true, reply: () => ({ body: [] }) }]
  on((url) => url.startsWith('/api/agents'), () => [
    { id: 'agent-1', key: 'hr', name: 'HR Agent', category: 'operations', status: 'active', revision: 1 },
    { id: 'agent-2', key: 'fin', name: 'Finance Agent', category: 'growth', status: 'active', revision: 1 },
  ])
  on((url) => url.startsWith('/api/users'), () => [
    { userId: ME, displayName: 'Maya Manager', email: 'maya@demo.test', role: 'manager', status: 'active' },
    { userId: 'user-2', displayName: 'Priya Shah', email: 'priya@demo.test', role: 'employee', status: 'active' },
  ])
  on((url) => url.startsWith('/api/approvals/count'), () => ({ pending: 0, canDecide: 0 }))
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      requests.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
      const route = routes.find((candidate) => candidate.match(url, method))!
      const reply = route.reply(url, init)
      return Promise.resolve(
        new Response(JSON.stringify(reply.body), { status: reply.status ?? 200, headers: { 'Content-Type': 'application/json' } }),
      )
    }),
  )
  signIn(['approval:read', 'approval:decide', 'member:read'])
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
  window.history.replaceState({}, '', '/')
})

const pendingUrl = (url: string) => url.startsWith('/api/approvals?status=pending')
const decidedUrl = (url: string) => url.startsWith('/api/approvals?status=') && !pendingUrl(url)

describe('Approvals, the queue', () => {
  it('shows an email as an email, with the goal and who asked, and when it expires', async () => {
    on(pendingUrl, () => [item('a-1')])
    on((url) => url.startsWith('/api/approvals/count'), () => ({ pending: 1, canDecide: 1 }))

    renderPage()

    expect(await screen.findByText('For: Welcome the new starter · asked by Priya Shah')).toBeInTheDocument()
    expect(screen.getByText('Exactly what will be sent')).toBeInTheDocument()
    // Rows, not JSON: the recipient, the subject and the body, with its line break.
    expect(screen.getByText('To')).toBeInTheDocument()
    expect(screen.getByText('jane@customer.example')).toBeInTheDocument()
    expect(screen.getByText('Your welcome pack')).toBeInTheDocument()
    expect(screen.getByText((_, element) => element?.tagName === 'DD' && element.textContent === 'Hello Jane,\nWelcome aboard.')).toBeInTheDocument()
    expect(screen.getByText(/Expires/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Send something outside the workspace using Gmail · send message' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument()
    expect(document.getElementById('approval-a-1')).not.toBeNull()
  })

  it('has no axe violations, in the queue or in the history', async () => {
    const standup = (id: string) =>
      item(id, { tool: 'slack.post_message', summary: 'Post the standup', payload: JSON.stringify({ channel: '#team', text: id }) })
    on(pendingUrl, () => [item('a-1'), standup('s-1'), standup('s-2')])
    on((url) => url.startsWith('/api/approvals?status=decided'), () => [
      item('x-1', { status: 'rejected', decidedBy: 'user-2', decidedAt: '2026-10-04T10:42:00Z', decisionNote: 'No.' }),
    ])
    const { container, unmount } = renderPage()
    await screen.findByRole('button', { name: 'Approve all 2' })
    expect((await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })).violations).toEqual([])
    unmount()

    const history = renderPage('/approvals?tab=decided')
    await screen.findByText(/Rejected by Priya Shah/)
    expect((await axe.run(history.container, { rules: { 'color-contrast': { enabled: false } } })).violations).toEqual([])
  })

  it('hides Approve from the person who asked when the workspace wants a second pair of eyes', async () => {
    on(pendingUrl, () => [item('a-1', { requestedBy: ME, canDecide: false })])

    renderPage()

    expect(await screen.findByText('You asked for this work, so someone else needs to decide it.')).toBeInTheDocument()
    expect(screen.getByText(/asked by You/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Reject and stop' })).toBeNull()
  })

  it('rejects with a note that says who will see it', async () => {
    on(pendingUrl, () => [item('a-1')])
    on((url, method) => method === 'POST' && url === '/api/approvals/a-1/decision', () => ({
      approvalId: 'a-1',
      status: 'rejected',
      runStatus: 'cancelled',
    }))

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Reject and stop' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Shown to the person who asked and recorded in the audit log. Up to 1,000 characters.')).toBeInTheDocument()
    fireEvent.change(within(dialog).getByLabelText(/Note/), { target: { value: 'Wrong recipient.' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Reject and stop' }))

    await waitFor(() =>
      expect(requests.find((request) => request.url === '/api/approvals/a-1/decision')?.body).toEqual({
        approved: false,
        mode: 'reject',
        note: 'Wrong recipient.',
      }),
    )
    // It leaves the queue at once.
    await waitFor(() => expect(document.getElementById('approval-a-1')).toBeNull())
  })

  it('says at the field when Send back has no feedback, and sends nothing', async () => {
    on(pendingUrl, () => [item('a-1')])

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Send back with feedback' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Send back' }))

    const field = within(dialog).getByLabelText('What should change')
    expect(field).toHaveAccessibleDescription(expect.stringContaining('Say what should change'))
    expect(requests.some((request) => request.url === '/api/approvals/a-1/decision')).toBe(false)
    fireEvent.change(field, { target: { value: 'Softer tone.' } })
    expect(field).not.toHaveAccessibleDescription(expect.stringContaining('Say what should change'))
  })

  it('moves focus to the next request once one is decided, and to the tabs when none is left', async () => {
    on(pendingUrl, () => [item('a-1'), item('a-2', { tool: null, summary: 'Send the quarterly report' })])
    on((url, method) => method === 'POST' && url.endsWith('/decision'), (url) => ({
      approvalId: url.split('/')[3],
      status: 'approved',
      runStatus: 'running',
    }))

    renderPage()
    fireEvent.click((await screen.findAllByRole('button', { name: 'Approve' }))[0]!)

    await waitFor(() => expect(document.getElementById('approval-a-1')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(document.getElementById('approval-a-2')))

    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(document.getElementById('approval-a-2')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: /^Waiting/ })))
  })

  it('pages the queue: Load more asks for the next page', async () => {
    const firstPage = Array.from({ length: 50 }, (_, index) => item(`p-${index}`, { summary: `Send report ${index}`, tool: null }))
    on(pendingUrl, (url) => (url.includes('page=0') ? firstPage : [item('p-last', { summary: 'The last one', tool: null })]))
    on((url) => url.startsWith('/api/approvals/count'), () => ({ pending: 51, canDecide: 51 }))

    renderPage()

    expect(await screen.findByText('Showing 50 of 51 waiting')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }))

    expect(await screen.findByRole('heading', { name: 'The last one' })).toBeInTheDocument()
    expect(requests.some((request) => request.url.includes('page=1'))).toBe(true)
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull())
  })
})

describe('Approvals, similar requests', () => {
  const standup = (id: string, extra: Partial<ApprovalItem> = {}) =>
    item(id, {
      tool: 'slack.post_message',
      summary: 'Send something outside the workspace using slack.post_message',
      payload: JSON.stringify({ channel: '#team', text: `Standup summary ${id}` }),
      ...extra,
    })

  it('offers Approve all N for identical requests, and lists each one in the confirmation', async () => {
    on(pendingUrl, () => [standup('s-1'), standup('s-2'), item('other-1')])
    on((url, method) => method === 'POST' && url === '/api/approvals/decisions', () => ({
      results: [
        { id: 's-1', result: 'decided', status: 'approved', message: null },
        { id: 's-2', result: 'already_decided', status: 'rejected', message: 'x' },
      ],
    }))

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Approve all 2' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Approve 2 of 2 requests?')).toBeInTheDocument()
    expect(within(dialog).getByText(/Message: Standup summary s-1/)).toBeInTheDocument()
    expect(within(dialog).getByText(/Message: Standup summary s-2/)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Approve 2' }))

    await waitFor(() =>
      expect(requests.find((request) => request.url === '/api/approvals/decisions')?.body).toEqual({
        ids: ['s-1', 's-2'],
        approved: true,
        note: null,
      }),
    )
    expect(await screen.findByText(/Approved 1\. 1 request was already decided by someone else\./)).toBeInTheDocument()
    // Both have left the queue; the one that was different stays.
    await waitFor(() => expect(document.getElementById('approval-s-1')).toBeNull())
    expect(document.getElementById('approval-other-1')).not.toBeNull()
  })

  it('leaves a deletion out of Approve all until it is ticked, and lists it explicitly', async () => {
    const remove = (id: string, extra: Partial<ApprovalItem> = {}) =>
      item(id, {
        tool: 'crm.delete_record',
        actionClass: 'DESTRUCTIVE',
        summary: 'Permanently remove something using crm.delete_record',
        payload: JSON.stringify({ record: `Contact ${id}` }),
        ...extra,
      })
    on(pendingUrl, () => [remove('d-1'), remove('d-2')])
    on((url, method) => method === 'POST' && url === '/api/approvals/decisions', () => ({
      results: [{ id: 'd-2', result: 'decided', status: 'approved', message: null }],
    }))

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Approve all 2' }))

    const dialog = await screen.findByRole('dialog')
    const boxes = within(dialog).getAllByRole('checkbox')
    expect(boxes).toHaveLength(2)
    // Nothing that removes something starts ticked, and each is named.
    expect(boxes.every((box) => !(box as HTMLInputElement).checked)).toBe(true)
    expect(within(dialog).getByText('Approve 0 of 2 requests?')).toBeInTheDocument()
    expect(within(dialog).getByText(/Record: Contact d-1/)).toBeInTheDocument()

    // With nothing ticked it will not send anything.
    fireEvent.click(within(dialog).getByRole('button', { name: 'Approve 0' }))
    expect(await within(dialog).findByText('Tick at least one request to decide.')).toBeInTheDocument()
    expect(requests.some((request) => request.url === '/api/approvals/decisions')).toBe(false)

    fireEvent.click(boxes[1]!)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Approve 1' }))
    await waitFor(() =>
      expect(requests.find((request) => request.url === '/api/approvals/decisions')?.body).toEqual({
        ids: ['d-2'],
        approved: true,
        note: null,
      }),
    )
  })

  it('rejects a group with one note, every request ticked', async () => {
    on(pendingUrl, () => [standup('s-1'), standup('s-2')])
    on((url, method) => method === 'POST' && url === '/api/approvals/decisions', () => ({
      results: [
        { id: 's-1', result: 'decided', status: 'rejected', message: null },
        { id: 's-2', result: 'decided', status: 'rejected', message: null },
      ],
    }))

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Reject all 2' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getAllByRole('checkbox').every((box) => (box as HTMLInputElement).checked)).toBe(true)
    fireEvent.change(within(dialog).getByLabelText(/Note/), { target: { value: 'Not today.' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Reject 2' }))

    await waitFor(() =>
      expect(requests.find((request) => request.url === '/api/approvals/decisions')?.body).toEqual({
        ids: ['s-1', 's-2'],
        approved: false,
        note: 'Not today.',
      }),
    )
  })

  it('does not offer a group a person could not decide', async () => {
    on(pendingUrl, () => [standup('s-1', { canDecide: false }), standup('s-2', { canDecide: false })])

    renderPage()

    await screen.findAllByRole('article')
    expect(screen.queryByRole('button', { name: /Approve all/ })).toBeNull()
  })
})

describe('Approvals, the history', () => {
  it('lists what was decided from the server, with who decided, when and why, and the request', async () => {
    on(decidedUrl, () => [
      item('x-1', {
        status: 'rejected',
        decidedBy: 'user-2',
        decidedAt: '2026-10-04T10:42:00Z',
        decisionNote: 'Wrong recipient.',
      }),
      item('x-2', { status: 'expired', decidedAt: '2026-10-04T11:00:00Z' }),
      item('x-3', { status: 'approved', decidedBy: ME, decidedAt: '2026-10-04T12:00:00Z' }),
    ])

    renderPage('/approvals?tab=decided')

    expect(await screen.findByText(/Rejected by Priya Shah/)).toBeInTheDocument()
    expect(screen.getByText('Wrong recipient.')).toBeInTheDocument()
    expect(screen.getByText(/Expired without a decision/)).toBeInTheDocument()
    expect(screen.getByText(/Approved by You/)).toBeInTheDocument()
    // The request itself is one click away, as the email it was.
    fireEvent.click(screen.getAllByRole('button', { name: 'Show the request' })[0]!)
    expect(screen.getAllByText('jane@customer.example').length).toBeGreaterThan(0)
    expect(requests.find((request) => decidedUrl(request.url))?.url).toBe('/api/approvals?status=decided&page=0&size=25')
  })

  it('still lists the decision after a reload, because it is the server that keeps it', async () => {
    on(decidedUrl, () => [item('x-1', { status: 'rejected', decidedBy: 'user-2', decidedAt: '2026-10-04T10:42:00Z' })])

    const first = renderPage('/approvals?tab=decided')
    expect(await screen.findByText(/Rejected by Priya Shah/)).toBeInTheDocument()
    first.unmount()

    renderPage('/approvals?tab=decided')
    expect(await screen.findByText(/Rejected by Priya Shah/)).toBeInTheDocument()
  })

  it('asks the server to narrow by agent and by outcome', async () => {
    on(decidedUrl, () => [])

    renderPage('/approvals?tab=decided&agent=agent-2&outcome=rejected')

    await waitFor(() =>
      expect(requests.some((request) => request.url === '/api/approvals?status=rejected&agentId=agent-2&page=0&size=25')).toBe(true),
    )
    // Nothing matched, and the way back is offered rather than an empty page.
    expect((await screen.findAllByRole('button', { name: /Clear filters/ })).length).toBeGreaterThan(0)
  })

  it('moves between the two with the Decided button, which keeps the tab in the address', async () => {
    on(pendingUrl, () => [])
    on(decidedUrl, () => [item('x-1', { status: 'approved', decidedBy: ME, decidedAt: '2026-10-04T12:00:00Z' })])

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Decided' }))

    expect(await screen.findByText(/Approved by You/)).toBeInTheDocument()
    expect(window.location.search).toBe('?tab=decided')
    expect(screen.getByRole('button', { name: 'Decided' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('says when nothing has been decided yet', async () => {
    on(decidedUrl, () => [])

    renderPage('/approvals?tab=decided')

    expect(await screen.findByText('Nothing decided yet')).toBeInTheDocument()
  })
})

describe('Approvals, a link to one request', () => {
  it('points to the Decided tab when the request was decided in the meantime', async () => {
    on(pendingUrl, () => [])
    on((url) => url === '/api/approvals/gone-1', () =>
      item('gone-1', { status: 'approved', decidedBy: ME, decidedAt: '2026-10-04T12:00:00Z' }),
    )

    renderPage('/approvals#approval-gone-1')

    expect(await screen.findByText(/That request is no longer waiting. It was approved\./)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'See it under Decided' })).toHaveAttribute('href', '/approvals?tab=decided#approval-gone-1')
  })
})
