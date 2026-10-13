// @find: tests for agent desk, take over browser, click on live frame, type key scroll navigate, hand back with note, Priya has control, permission hiding, Stop and Take over hidden, close while in control, control ended notice, focus after hand back, return control, waiting for the agent
// @what: Component tests for AgentDesk and TakeoverControls: taking over, each action sent with the right body, handing back with a note (focus and announcement), someone else holding control (announced, returnable), waiting for the agent to stop, closing or leaving while in control, control ending without a hand back, a run that cannot be read, and controls hidden from people who may not use them.
// @flow: Renders AgentDesk inside QueryClientProvider, RouterProvider and ToastProvider with a mocked fetch
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../../lib/router'
import { clearSession, saveSession } from '../../lib/session'
import { ToastProvider } from '../../lib/toast'
import type { TakeoverView } from '../../lib/agentDeskQueries'
import { AgentDesk } from './AgentDesk'

// jsdom has the <dialog> element but not its modal methods; the desk opens as one.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

type Call = { method: string; url: string; body: unknown }

const STARTED = new Date(Date.now() - 60_000).toISOString()
const STEPS = [
  { id: 's0', position: 0, kind: 'note', detail: { type: 'instruction', content: 'Download the invoice.' }, promptTokens: 0, completionTokens: 0, durationMs: 0, occurredAt: STARTED },
  {
    id: 's1',
    position: 1,
    kind: 'tool_call',
    detail: { tool: 'browser.open_page', status: 'SUCCEEDED', summary: 'Opened Example Domain.' },
    promptTokens: 0,
    completionTokens: 0,
    durationMs: 900,
    occurredAt: STARTED,
  },
]

const NONE: TakeoverView = {
  status: 'none',
  active: false,
  starting: false,
  releasedBy: null,
  takenBy: null,
  takenAt: null,
  endsAt: null,
  mine: false,
  canTakeOver: true,
  canRelease: false,
  actions: 0,
}
const MINE: TakeoverView = {
  ...NONE,
  status: 'active',
  active: true,
  takenBy: 'user-1',
  takenAt: new Date().toISOString(),
  endsAt: new Date(Date.now() + 30 * 60_000).toISOString(),
  mine: true,
  canRelease: true,
}

const STARTING_MINE: TakeoverView = { ...NONE, status: 'starting', starting: true, takenBy: 'user-1', mine: true, canRelease: true }
const OTHERS: TakeoverView = { ...NONE, status: 'active', active: true, takenBy: 'u-2', mine: false, canTakeOver: false, canRelease: true }

let calls: Call[]
let takeover: TakeoverView
let permissions: string[]
// Answers that replace the usual one for "METHOD url", for one test.
let overrides: Record<string, () => Response>

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  calls = []
  takeover = NONE
  permissions = ['agent:read', 'run:read', 'run:cancel']
  overrides = {}
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ method, url, body: typeof init.body === 'string' ? JSON.parse(init.body) : null })
      const override = overrides[`${method} ${url}`]
      if (override) return override()
      if (method === 'GET' && url === '/api/runs/r-1') {
        return json(200, {
          id: 'r-1',
          agentId: 'a-research',
          status: 'running',
          trigger: 'manual',
          stepCount: 2,
          promptTokens: 0,
          completionTokens: 0,
          cost: 0,
          startedAt: STARTED,
        })
      }
      if (method === 'GET' && url === '/api/runs/r-1/steps') return json(200, STEPS)
      if (method === 'GET' && url === '/api/runs/r-1/browser/live') {
        return json(200, { busy: false, action: '', since: null, url: 'https://example.com/', title: 'Example Domain', frame: 'AAAA' })
      }
      if (method === 'GET' && url === '/api/runs/r-1/takeover') return json(200, takeover)
      if (method === 'POST' && url === '/api/runs/r-1/takeover') {
        takeover = MINE
        return json(200, takeover)
      }
      if (method === 'POST' && url === '/api/runs/r-1/takeover/actions') {
        return json(200, { url: 'https://example.com/next', title: 'Next', frame: 'BBBB' })
      }
      if (method === 'POST' && url === '/api/runs/r-1/takeover/release') {
        takeover = { ...NONE, status: 'released', actions: 3, releasedBy: 'user-1' }
        return json(200, takeover)
      }
      if (method === 'GET' && url === '/api/users') {
        return json(200, [{ userId: 'u-2', displayName: 'Priya Patel', email: 'priya@example.test', role: 'manager', status: 'active' }])
      }
      return json(404, { code: 'not_found', detail: 'Not here.' })
    }),
  )
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

function openDesk(onClose: () => void = () => {}) {
  saveSession('token', {
    userId: 'user-1',
    workspaceId: 'ws-1',
    permissions,
    displayName: 'Olivia Owner',
    email: 'olivia@example.test',
    role: 'manager',
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <AgentDesk open onClose={onClose} runId="r-1" agentName="Research" />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
}

/** Whether a hand back has been posted. */
const handedBack = () => calls.some((call) => call.method === 'POST' && call.url === '/api/runs/r-1/takeover/release')

/** The desk's own sheet (the confirm dialogs are dialogs too). */
const sheet = () => document.querySelector('dialog.sheet') as HTMLDialogElement

/** The body of every action posted so far. */
const actions = () => calls.filter((call) => call.method === 'POST' && call.url === '/api/runs/r-1/takeover/actions').map((call) => call.body)

describe('AgentDesk', () => {
  it('shows the live page beside the steps, with Stop, Take over and Open full trace', async () => {
    openDesk()

    expect(await screen.findByRole('img', { name: /Live view of Example Domain/ })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Steps as they happen' })).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Take over' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Stop' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open full trace' })).toHaveAttribute('href', '/runs/r-1')
  })

  it('Take over makes the page yours: clicks map to the page and every control sends its action', async () => {
    openDesk()

    fireEvent.click(await screen.findByRole('button', { name: 'Take over' }))

    expect(await screen.findByText('You have control. The agent is waiting.')).toBeInTheDocument()
    expect(calls.some((call) => call.method === 'POST' && call.url === '/api/runs/r-1/takeover')).toBe(true)
    expect(screen.queryByRole('button', { name: 'Take over' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Hand back to Research' })).toBeInTheDocument()

    // A click on the picture drawn at 640 by 400 lands on the 1280 by 800 page at twice the point.
    const page = await screen.findByRole('img', { name: /The page you control/ })
    vi.spyOn(page, 'getBoundingClientRect').mockReturnValue({
      left: 0,
      top: 0,
      width: 640,
      height: 400,
      right: 640,
      bottom: 400,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    } as DOMRect)
    fireEvent.click(page, { clientX: 320, clientY: 100 })
    await waitFor(() => expect(actions()).toEqual([{ type: 'click', x: 640, y: 200, width: 1280, height: 800 }]))
    // The frame the action answered with replaces the picture at once.
    await waitFor(() => expect(screen.getByRole('img', { name: /The page you control/ })).toHaveAttribute('src', 'data:image/jpeg;base64,BBBB'))

    fireEvent.change(screen.getByLabelText('Text to type'), { target: { value: 'hello there' } })
    fireEvent.click(screen.getByRole('button', { name: 'Type' }))
    await waitFor(() => expect(actions()).toHaveLength(2))
    expect(actions()[1]).toEqual({ type: 'type', text: 'hello there' })
    await waitFor(() => expect(screen.getByLabelText('Text to type')).toHaveValue(''))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Press Enter' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: 'Press Enter' }))
    await waitFor(() => expect(actions()).toHaveLength(3))
    expect(actions()[2]).toEqual({ type: 'key', key: 'Enter' })

    await waitFor(() => expect(screen.getByRole('button', { name: 'Scroll down' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: 'Scroll down' }))
    await waitFor(() => expect(actions()).toHaveLength(4))
    expect(actions()[3]).toEqual({ type: 'scroll', dy: 600 })

    await waitFor(() => expect(screen.getByLabelText('Address')).toBeEnabled())
    fireEvent.change(screen.getByLabelText('Address'), { target: { value: 'example.org/prices' } })
    fireEvent.click(screen.getByRole('button', { name: 'Go' }))
    await waitFor(() => expect(actions()).toHaveLength(5))
    expect(actions()[4]).toEqual({ type: 'navigate', url: 'https://example.org/prices' })
  })

  it('keeps keyboard focus where it was while an action is in flight, and sends one action at a time', async () => {
    // Seen in Chrome on 13 Oct 2026: Enter in the address bar or the text box disabled that field
    // while the action ran, which dropped keyboard focus to the page body.
    takeover = MINE
    let finish: (response: Response) => void = () => {}
    // Each action waits until the test answers it.
    const fetchMock = vi.mocked(fetch)
    const answer = fetchMock.getMockImplementation()!
    fetchMock.mockImplementation(async (url, init) => {
      if (init?.method === 'POST' && url === '/api/runs/r-1/takeover/actions') {
        calls.push({ method: 'POST', url: String(url), body: JSON.parse(String(init.body)) })
        return new Promise<Response>((resolve) => {
          finish = resolve
        })
      }
      return answer(url, init)
    })
    openDesk()

    const box = await screen.findByLabelText('Text to type')
    box.focus()
    fireEvent.change(box, { target: { value: 'hello there' } })
    fireEvent.submit(box.closest('form')!)
    await waitFor(() => expect(actions()).toHaveLength(1))

    // In flight: the box stays focused and enabled but cannot be edited; the keys are marked
    // unavailable, and pressing one sends nothing.
    expect(box).toBeEnabled()
    expect(box).toHaveAttribute('readonly')
    expect(document.activeElement).toBe(box)
    const enter = screen.getByRole('button', { name: 'Press Enter' })
    expect(enter).toHaveAttribute('aria-disabled', 'true')
    fireEvent.click(enter)
    fireEvent.submit(screen.getByLabelText('Address').closest('form')!)
    expect(actions()).toHaveLength(1)

    finish(json(200, { url: 'https://example.com/next', title: 'Next', frame: 'BBBB' }))
    await waitFor(() => expect(box).toHaveValue(''))
    expect(box).not.toHaveAttribute('readonly')
    expect(document.activeElement).toBe(box)
    expect(enter).not.toHaveAttribute('aria-disabled')

    // Typing with the Type button brings focus back to the box, since the emptied box leaves Type unavailable.
    fireEvent.change(box, { target: { value: 'more' } })
    const type = screen.getByRole('button', { name: 'Type' })
    type.focus()
    fireEvent.click(type)
    await waitFor(() => expect(actions()).toHaveLength(2))
    finish(json(200, { url: 'https://example.com/next', title: 'Next', frame: 'CCCC' }))
    await waitFor(() => expect(box).toHaveValue(''))
    await waitFor(() => expect(document.activeElement).toBe(box))
  })

  it('hands back to the agent with a note', async () => {
    takeover = MINE
    openDesk()

    fireEvent.click(await screen.findByRole('button', { name: 'Hand back to Research' }))
    fireEvent.change(screen.getByLabelText(/Note for Research/), { target: { value: 'Signed in, carry on.' } })
    fireEvent.click(screen.getByRole('button', { name: 'Hand back' }))

    await waitFor(() =>
      expect(calls.find((call) => call.method === 'POST' && call.url === '/api/runs/r-1/takeover/release')?.body).toEqual({
        note: 'Signed in, carry on.',
      }),
    )
    await waitFor(() => expect(screen.queryByText('You have control. The agent is waiting.')).not.toBeInTheDocument())
  })

  it('says who has control when it is someone else, and offers no Take over', async () => {
    takeover = OTHERS
    permissions = ['agent:read', 'run:read']
    openDesk()

    expect(await screen.findByText('Priya Patel has control of the web browser. Research is waiting.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Take over' })).not.toBeInTheDocument()
    expect(screen.queryByText('You have control. The agent is waiting.')).not.toBeInTheDocument()
    // The live view under the notice agrees: the agent waits, it is not deciding anything (13 Oct 2026).
    expect(await screen.findByText('Waiting for a person to hand back the web browser.')).toBeInTheDocument()
    expect(screen.queryByText('Deciding what to do next…')).not.toBeInTheDocument()
  })

  it('lets someone who can stop runs return another person\'s control to the agent, after asking', async () => {
    takeover = OTHERS
    openDesk()

    fireEvent.click(await screen.findByRole('button', { name: 'Return control to Research' }))
    expect(await screen.findByRole('dialog', { name: 'Return control to Research?' })).toBeInTheDocument()
    expect(handedBack()).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Return control' }))

    await waitFor(() => expect(handedBack()).toBe(true))
    expect(await screen.findByText('Control returned to Research.')).toBeInTheDocument()
  })

  it('announces another person taking control while you watch', async () => {
    overrides['POST /api/runs/r-1/takeover'] = () => {
      takeover = OTHERS
      return json(409, { code: 'conflict', detail: 'Someone else has control of this web browser.' })
    }
    openDesk()

    fireEvent.click(await screen.findByRole('button', { name: 'Take over' }))

    const notice = await screen.findByText('Priya Patel has control of the web browser. Research is waiting.')
    expect(notice.closest('[role="alert"]')).not.toBeNull()
  })

  it('while your request waits for the agent to stop, says so and lets you cancel it', async () => {
    takeover = STARTING_MINE
    openDesk()

    expect(await screen.findByText(/Waiting for Research to finish the step it is on/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Take over' })).not.toBeInTheDocument()
    expect(screen.queryByText('You have control. The agent is waiting.')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    await waitFor(() => expect(handedBack()).toBe(true))
    await waitFor(() => expect(screen.queryByText(/Waiting for Research to finish/)).not.toBeInTheDocument())
  })

  it('closing the desk while you have control asks first; Keep control keeps it, Hand back and close hands back', async () => {
    takeover = MINE
    const onClose = vi.fn()
    openDesk(onClose)
    await screen.findByText('You have control. The agent is waiting.')

    fireEvent.click(within(sheet()).getAllByRole('button', { name: 'Close' })[0]!)
    expect(await screen.findByRole('dialog', { name: 'Hand control back to Research?' })).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Keep control' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Hand control back to Research?' })).not.toBeInTheDocument())
    expect(onClose).not.toHaveBeenCalled()

    // Escape is the browser's cancel on the modal sheet: it is held back the same way.
    const escape = new Event('cancel', { cancelable: true })
    sheet().dispatchEvent(escape)
    expect(escape.defaultPrevented).toBe(true)
    expect(await screen.findByRole('dialog', { name: 'Hand control back to Research?' })).toBeInTheDocument()
    // Escape on the controls inside the desk is refused too, so a second Escape cannot close it.
    fireEvent.click(screen.getByRole('button', { name: 'Keep control' }))
    const key = fireEvent.keyDown(screen.getByLabelText('Text to type'), { key: 'Escape' })
    expect(key).toBe(false)
    expect(await screen.findByRole('dialog', { name: 'Hand control back to Research?' })).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
    expect(handedBack()).toBe(false)

    fireEvent.click(screen.getByRole('button', { name: 'Hand back and close' }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(handedBack()).toBe(true)
  })

  it('opening the full trace while you have control hands back first', async () => {
    takeover = MINE
    openDesk()
    await screen.findByText('You have control. The agent is waiting.')

    fireEvent.click(screen.getByRole('link', { name: 'Open full trace' }))
    expect(window.location.pathname).not.toBe('/runs/r-1')
    fireEvent.click(await screen.findByRole('button', { name: 'Hand back and open the trace' }))

    await waitFor(() => expect(window.location.pathname).toBe('/runs/r-1'))
    expect(handedBack()).toBe(true)
    window.history.pushState({}, '', '/')
  })

  it('when control returns without your hand back, says why in a live notice and moves focus to it', async () => {
    takeover = MINE
    overrides['POST /api/runs/r-1/takeover/actions'] = () => {
      takeover = { ...NONE, status: 'expired', actions: 2 }
      return json(409, { code: 'conflict', detail: 'Control has already returned to the agent, so nothing was done.' })
    }
    openDesk()
    await screen.findByText('You have control. The agent is waiting.')

    fireEvent.click(screen.getByRole('button', { name: 'Press Enter' }))

    const notice = await screen.findByText('Control returned to Research after 30 minutes.')
    expect(notice.closest('[role="alert"]')).not.toBeNull()
    await waitFor(() => expect(document.activeElement?.contains(notice)).toBe(true))
    expect(screen.queryByText('You have control. The agent is waiting.')).not.toBeInTheDocument()
  })

  it('handing back moves focus into the note, then says it was done and keeps focus inside the desk', async () => {
    takeover = MINE
    openDesk()

    fireEvent.click(await screen.findByRole('button', { name: 'Hand back to Research' }))
    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText(/Note for Research/)))
    fireEvent.click(screen.getByRole('button', { name: 'Keep control' }))
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Hand back to Research' })))

    fireEvent.click(screen.getByRole('button', { name: 'Hand back to Research' }))
    fireEvent.click(screen.getByRole('button', { name: 'Hand back' }))

    expect(await screen.findByText('Control handed back to Research.')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('You have control. The agent is waiting.')).not.toBeInTheDocument())
    expect(sheet().contains(document.activeElement)).toBe(true)
    expect(document.activeElement).not.toBe(document.body)
    // Your own hand back is not reported as control ending.
    expect(screen.queryByText(/handed control back to Research\.$/)).not.toBeInTheDocument()
  })

  it('keeps the countdown out of a live region, so it is not read out every minute', async () => {
    takeover = MINE
    openDesk()

    const title = await screen.findByText('You have control. The agent is waiting.')
    expect(title.closest('[role="status"], [role="alert"], [aria-live]')).toBeNull()
  })

  it('says the agent is getting started until its first step lands, instead of an empty desk', async () => {
    overrides['GET /api/runs/r-1/steps'] = () => json(200, STEPS.slice(0, 1))
    openDesk()

    expect(await screen.findByText('Research is getting started. Its steps appear here as it works.')).toBeInTheDocument()

    delete overrides['GET /api/runs/r-1/steps']
    expect(await screen.findByText('Opened Example Domain.', {}, { timeout: 4000 })).toBeInTheDocument()
    expect(screen.queryByText(/is getting started/)).not.toBeInTheDocument()
  })

  it('says so when the run cannot be read, instead of loading for ever', async () => {
    overrides['GET /api/runs/r-1'] = () => json(403, { code: 'permission_denied', detail: 'You cannot see this run.' })
    openDesk()

    expect(await screen.findByText(/This run could not be loaded\./)).toBeInTheDocument()
    expect(screen.queryByText('Loading the run…')).not.toBeInTheDocument()
  })

  it('says why a run that stopped stopped, and keeps "finished" for a run that completed', async () => {
    const run = (status: string, failureReason: string | null) => () =>
      json(200, {
        id: 'r-1',
        agentId: 'a-research',
        status,
        trigger: 'manual',
        stepCount: 2,
        promptTokens: 0,
        completionTokens: 0,
        cost: 0,
        startedAt: STARTED,
        completedAt: STARTED,
        failureReason,
      })
    overrides['GET /api/runs/r-1'] = run('cancelled', 'An approver rejected the action this run needed: Not this week.')
    openDesk()

    expect(
      await screen.findByText(
        'This run stopped. An approver rejected the action this run needed: Not this week. Its steps stay below.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByText(/This run has finished/)).not.toBeInTheDocument()
  })

  it('hides Stop and Take over from someone who may not use them', async () => {
    permissions = ['agent:read', 'run:read']
    takeover = { ...NONE, canTakeOver: false }
    openDesk()

    expect(await screen.findByRole('img', { name: /Live view of Example Domain/ })).toBeInTheDocument()
    await waitFor(() => expect(calls.some((call) => call.url === '/api/runs/r-1/takeover')).toBe(true))
    const footer = screen.getByRole('link', { name: 'Open full trace' }).parentElement!
    expect(within(footer).queryByRole('button', { name: 'Take over' })).not.toBeInTheDocument()
    expect(within(footer).queryByRole('button', { name: 'Stop' })).not.toBeInTheDocument()
  })
})
