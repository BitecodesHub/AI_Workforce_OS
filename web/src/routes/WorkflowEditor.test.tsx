// @find: tests for the workflow editor, canvas, add step, palette, connect steps, delete step, drag a step, draw a connection, drop an agent, save with version, version conflict, restored draft version, unsaved changes guard, Back guard, autosave draft, draft flushed on leaving, double click save and run, run with values, run version conflict, run refused for missing grant, palette refetch error keeps edits, focus after delete, read-only role, not the maker, /workflows/:id
// @what: Tests that the workflow editor builds, saves and runs workflows the way a person would, with pointer and keyboard, and refuses what the server would refuse.
// @flow: Renders routes/WorkflowEditor with a mocked fetch inside the query client, router and toast providers.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import type { Workflow, WorkflowPalette } from '../lib/workflowQueries'
import { AGENT_MIME } from '../components/workflows/WorkflowCanvas'
import { WorkflowEditor } from './WorkflowEditor'

// jsdom has the <dialog> element but not its modal methods.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.show = function show(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const PALETTE: WorkflowPalette = {
  agents: [
    { id: 'rhea', name: 'Rhea', category: 'operations', status: 'active', fallback: false, description: null, connectors: ['github'] },
    { id: 'mo', name: 'Mo', category: 'support', status: 'active', fallback: false, description: null, connectors: [] },
  ],
  connectors: [
    { id: 'github', name: 'GitHub', category: 'engineering', description: null },
    { id: 'gmail', name: 'Gmail', category: 'communication', description: null },
  ],
}

function workflow(overrides: Partial<Workflow> = {}): Workflow {
  return {
    id: 'wf-1',
    name: 'Reply to a customer',
    description: '',
    graph: {
      nodes: [
        { id: 'a', type: 'agent', agentId: 'rhea', title: null, instruction: 'Research the customer.', connectors: [], x: 0, y: 0 },
        { id: 'b', type: 'agent', agentId: 'mo', title: null, instruction: 'Draft the reply.', connectors: [], x: 400, y: 0 },
      ],
      edges: [{ id: 'link-1', from: 'a', to: 'b' }],
    },
    version: 0,
    createdBy: 'user-1',
    updatedBy: 'user-1',
    createdAt: '2026-10-12T08:00:00Z',
    updatedAt: '2026-10-12T08:00:00Z',
    placeholders: [],
    errors: [],
    warnings: [],
    canChange: true,
    ...overrides,
  }
}

let calls: Array<{ url: string; method: string; body: unknown }>
let stored: Workflow
let putStatus: number
let paletteFails: boolean
/** Holds the next answer to a write until released, to press a button twice while it is on its way. */
let hold: Promise<void> | null

beforeEach(() => {
  calls = []
  stored = workflow()
  putStatus = 200
  paletteFails = false
  hold = null
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      const body = init?.body ? JSON.parse(String(init.body)) : undefined
      calls.push({ url, method, body })
      if (method !== 'GET' && hold) await hold
      const conflict = () =>
        json({ code: 'version_conflict', detail: 'Someone else saved a change first. Reload and try again.', status: 409 }, 409)
      if (url === '/api/workflows/palette') return paletteFails ? json({ detail: 'Unavailable', status: 503 }, 503) : json(PALETTE)
      if (url === '/api/workflows/wf-1/runs') return json([])
      if (url === '/api/workflows/wf-1/run' && method === 'POST') {
        // As the server does: a run naming a version that is no longer stored is refused.
        const named = (body as { version?: number } | undefined)?.version
        if (named !== undefined && named !== stored.version) return conflict()
        return json({ id: 'wr-1', workflowId: 'wf-1', workflowVersion: stored.version, goalId: 'goal-9', status: 'running', startedBy: 'user-1', startedAt: '2026-10-12T09:00:00Z', completedAt: null }, 201)
      }
      if (url === '/api/workflows/wf-1' && method === 'GET') return json(stored)
      if (url === '/api/workflows/wf-1' && method === 'PUT') {
        // As the server does: a save based on any version but the stored one is refused.
        if (putStatus === 409 || (body as { version: number }).version !== stored.version) return conflict()
        stored = { ...stored, ...body, version: stored.version + 1, updatedAt: '2026-10-12T09:30:00Z' }
        return json(stored)
      }
      if (url === '/api/workflows' && method === 'POST') {
        stored = workflow({ ...body, id: 'wf-new' })
        return json(stored, 201)
      }
      return new Response(null, { status: 404 })
    }),
  )
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
  window.history.replaceState(null, '', '/')
})

const EDITOR = ['task:read', 'task:create', 'run:read']

function signIn(permissions: string[]) {
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions,
    displayName: 'Maya Manager',
    email: 'maya@demo.test',
    role: 'manager',
  })
}

/** An in-memory localStorage the test can read, with what it holds. */
function memoryStorage() {
  const items = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    get length() {
      return items.size
    },
    key: (index: number) => [...items.keys()][index] ?? null,
    getItem: (key: string) => items.get(key) ?? null,
    setItem: (key: string, value: string) => void items.set(key, value),
    removeItem: (key: string) => void items.delete(key),
    clear: () => items.clear(),
  })
  return items
}

const DRAFT_KEY = 'aiwos:workflow-draft:user-1:workspace-1:wf-1'

let client: QueryClient

function renderEditor(id: string | null = 'wf-1') {
  window.history.replaceState(null, '', id ? `/workflows/${id}` : '/workflows/new')
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <WorkflowEditor id={id} />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
}

const step = (pattern: RegExp) => screen.getByRole('button', { name: pattern })
const edgeCount = (container: HTMLElement) => container.querySelectorAll('[data-edge-id]').length

describe('Workflow editor', () => {
  it('draws the saved steps and their hand-off, numbered in the order they run', async () => {
    signIn(EDITOR)
    const { container } = renderEditor()
    expect(await screen.findByRole('button', { name: /^Step 1: Research the customer\. Rhea/ })).toBeInTheDocument()
    expect(step(/^Step 2: Draft the reply\. Mo/)).toBeInTheDocument()
    expect(edgeCount(container)).toBe(1)
    expect(screen.getByRole('heading', { level: 1, name: 'Reply to a customer' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })

  it('adds a step from Add a step, focuses its instruction and marks the workflow unsaved', async () => {
    signIn(EDITOR)
    renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.change(screen.getByLabelText('Add a step'), { target: { value: 'mo' } })

    expect(await screen.findByRole('button', { name: /^Step 3: No instruction yet\. Mo/ })).toBeInTheDocument()
    await waitFor(() => expect(document.activeElement).toHaveAttribute('data-step-instruction', 'step-3'))
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Instruction'), { target: { value: 'Check the reply for tone.' } })
    expect(step(/^Step 3: Check the reply for tone\./)).toBeInTheDocument()
  })

  it('adds a step by pressing an agent in the palette', async () => {
    signIn(EDITOR)
    renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.click(screen.getByRole('button', { name: 'Add a step for Rhea' }))
    expect(await screen.findByRole('button', { name: /^Step 3: No instruction yet\. Rhea/ })).toBeInTheDocument()
  })

  it('connects steps from the step panel, offering only connections that make no loop', async () => {
    signIn(EDITOR)
    const { container } = renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.change(screen.getByLabelText('Add a step'), { target: { value: 'mo' } })
    await screen.findByRole('button', { name: /^Step 3:/ })

    // Step 2 (Draft) can hand over to the new step, but not back to step 1, which would be a loop.
    fireEvent.keyDown(step(/^Step 2:/), { key: 'Enter' })
    const handOver = await screen.findByLabelText('Hand over to another step')
    const options = within(handOver).getAllByRole('option').map((option) => option.textContent)
    expect(options).toEqual(['Choose a step', 'Step 3: Mo'])
    fireEvent.change(handOver, { target: { value: 'step-3' } })

    expect(edgeCount(container)).toBe(2)
    expect(screen.getByText('Step 3: Mo', { selector: '.wf-link-list span' })).toBeInTheDocument()
  })

  it('removes the selected step and its connections with Delete', async () => {
    signIn(EDITOR)
    const { container } = renderEditor()
    const first = await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.keyDown(first, { key: 'Enter' })
    expect(first).toHaveAttribute('aria-pressed', 'true')
    fireEvent.keyDown(first, { key: 'Delete' })

    expect(screen.queryByRole('button', { name: /Research the customer/ })).not.toBeInTheDocument()
    expect(edgeCount(container)).toBe(0)
  })

  it('moves a step with the arrow keys', async () => {
    signIn(EDITOR)
    const { container } = renderEditor()
    const first = await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.keyDown(first, { key: 'ArrowRight' })
    fireEvent.keyDown(first, { key: 'ArrowDown', shiftKey: true })
    const node = container.querySelector<HTMLElement>('[data-node-id="a"]')!
    expect(node.style.left).toBe('16px')
    expect(node.style.top).toBe('64px')
  })

  describe('with a pointer', () => {
    beforeEach(() => {
      // jsdom has no PointerEvent or DragEvent; MouseEvent carries the coordinates they need.
      vi.stubGlobal('PointerEvent', MouseEvent)
      vi.stubGlobal('DragEvent', MouseEvent)
    })

    it('drags a step to a new place', async () => {
      signIn(EDITOR)
      const { container } = renderEditor()
      await screen.findByRole('button', { name: /^Step 1:/ })
      const node = container.querySelector<HTMLElement>('[data-node-id="a"]')!
      const canvas = container.querySelector<HTMLElement>('.wf-canvas')!
      fireEvent.pointerDown(node, { clientX: 50, clientY: 50, button: 0 })
      fireEvent.pointerMove(canvas, { clientX: 150, clientY: 90 })
      fireEvent.pointerUp(canvas, { clientX: 150, clientY: 90 })
      expect(node.style.left).toBe('100px')
      expect(node.style.top).toBe('40px')
    })

    it('draws a connection from a step’s output to another step, and refuses one that would loop', async () => {
      signIn(EDITOR)
      const { container } = renderEditor()
      await screen.findByRole('button', { name: /^Step 1:/ })
      fireEvent.change(screen.getByLabelText('Add a step'), { target: { value: 'mo' } })
      await screen.findByRole('button', { name: /^Step 3:/ })
      const canvas = container.querySelector<HTMLElement>('.wf-canvas')!
      const port = (id: string) => container.querySelector<HTMLElement>(`[data-node-id="${id}"] .wf-port-out`)!
      const third = container.querySelector<HTMLElement>('[data-node-id="step-3"]')!
      // The viewport starts 32 pixels in; aim at the middle of the target step.
      const aim = (node: HTMLElement) => ({ clientX: 32 + parseFloat(node.style.left) + 40, clientY: 32 + parseFloat(node.style.top) + 40 })

      fireEvent.pointerDown(port('b'), { clientX: 0, clientY: 0, button: 0 })
      fireEvent.pointerMove(canvas, aim(third))
      fireEvent.pointerUp(canvas, aim(third))
      expect(edgeCount(container)).toBe(2)

      const first = container.querySelector<HTMLElement>('[data-node-id="a"]')!
      fireEvent.pointerDown(port('step-3'), { clientX: 0, clientY: 0, button: 0 })
      fireEvent.pointerUp(canvas, aim(first))
      expect(edgeCount(container)).toBe(2)
      expect(await screen.findByText('That connection would make the steps wait for each other in a loop.')).toBeInTheDocument()
    })

    it('adds a step where an agent is dropped', async () => {
      signIn(EDITOR)
      const { container } = renderEditor()
      await screen.findByRole('button', { name: /^Step 1:/ })
      const canvas = container.querySelector<HTMLElement>('.wf-canvas')!
      const dataTransfer = { types: [AGENT_MIME], getData: (type: string) => (type === AGENT_MIME ? 'rhea' : ''), dropEffect: 'none' }
      fireEvent.dragOver(canvas, { dataTransfer, clientX: 500, clientY: 400 })
      fireEvent.drop(canvas, { dataTransfer, clientX: 500, clientY: 400 })
      // Dropped left of step 2 and with nothing before it, the new step runs second.
      const added = await screen.findByRole('button', { name: /^Step 2: No instruction yet\. Rhea/ })
      expect(added.style.left).toBe(`${500 - 32 - 116}px`)
      expect(step(/^Step 3: Draft the reply/)).toBeInTheDocument()
    })

    it('keeps the first step dropped on an empty canvas where it was dropped, and fits a saved one on opening', async () => {
      // jsdom lays nothing out: give the canvas a real size, so fitting the view would move it.
      vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
        const sized = this.classList.contains('wf-canvas')
        return { x: 0, y: 0, left: 0, top: 0, right: sized ? 800 : 0, bottom: sized ? 600 : 0, width: sized ? 800 : 0, height: sized ? 600 : 0, toJSON: () => ({}) } as DOMRect
      })
      try {
        signIn(EDITOR)
        const { container, unmount } = renderEditor(null)
        await screen.findByRole('heading', { level: 1, name: 'Untitled workflow' })
        const canvas = container.querySelector<HTMLElement>('.wf-canvas')!
        const layer = container.querySelector<HTMLElement>('.wf-canvas-layer')!
        const dataTransfer = { types: [AGENT_MIME], getData: (type: string) => (type === AGENT_MIME ? 'rhea' : ''), dropEffect: 'none' }
        fireEvent.dragOver(canvas, { dataTransfer, clientX: 200, clientY: 150 })
        fireEvent.drop(canvas, { dataTransfer, clientX: 200, clientY: 150 })
        const added = await screen.findByRole('button', { name: /^Step 1: No instruction yet\. Rhea/ })
        expect(added.style.left).toBe(`${200 - 32 - 116}px`)
        // The view did not jump to centre the new step: it is still drawn under the pointer.
        expect(layer.style.transform).toBe('translate(32px, 32px) scale(1)')
        unmount()

        const saved = renderEditor()
        await screen.findByRole('button', { name: /^Step 1:/ })
        expect(saved.container.querySelector<HTMLElement>('.wf-canvas-layer')!.style.transform).not.toBe('translate(32px, 32px) scale(1)')
      } finally {
        vi.mocked(HTMLElement.prototype.getBoundingClientRect).mockRestore()
      }
    })
  })

  it('saves with the version it was loaded at, and shows the new version', async () => {
    signIn(EDITOR)
    renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.keyDown(step(/^Step 2:/), { key: 'Enter' })
    fireEvent.change(screen.getByLabelText('Instruction'), { target: { value: 'Draft a short reply.' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(calls.some((call) => call.method === 'PUT')).toBe(true))
    const put = calls.find((call) => call.method === 'PUT')!
    expect(put.body).toMatchObject({ name: 'Reply to a customer', version: 0 })
    expect((put.body as { graph: { nodes: Array<{ instruction: string }> } }).graph.nodes[1]!.instruction).toBe('Draft a short reply.')
    expect(await screen.findByText(/Version 2, saved/)).toBeInTheDocument()
    expect(screen.queryByText('Unsaved changes')).not.toBeInTheDocument()
  })

  it('explains a version conflict and offers both ways forward', async () => {
    signIn(EDITOR)
    putStatus = 409
    renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.keyDown(step(/^Step 1:/), { key: 'Enter' })
    fireEvent.change(screen.getByLabelText(/^Title/), { target: { value: 'Research' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(/Someone else saved this workflow while you were editing it/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Load the latest version' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save mine over it' })).toBeInTheDocument()
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument()
  })

  it('does not send a workflow the server would refuse, and says why', async () => {
    signIn(EDITOR)
    renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.change(screen.getByLabelText('Add a step'), { target: { value: 'mo' } })
    await screen.findByRole('button', { name: /^Step 3:/ })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(/The workflow was not saved\. The step for Mo has no instruction/)).toBeInTheDocument()
    expect(calls.some((call) => call.method === 'PUT')).toBe(false)
  })

  it('asks for each placeholder value, runs the workflow and opens its live view', async () => {
    signIn(EDITOR)
    stored = workflow({
      graph: {
        nodes: [{ id: 'a', type: 'agent', agentId: 'rhea', title: null, instruction: 'Look up {{customer}}.', connectors: [], x: 0, y: 0 }],
        edges: [],
      },
      placeholders: ['customer'],
    })
    renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.click(screen.getByRole('button', { name: 'Run' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/1 step will run as one goal/)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Run now' }))
    expect(await within(dialog).findByText('Fill in customer.')).toBeInTheDocument()
    fireEvent.change(within(dialog).getByLabelText('customer'), { target: { value: 'Acme Pty Ltd' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Run now' }))

    await waitFor(() => expect(window.location.pathname).toBe('/workflows/run/goal-9'))
    const post = calls.find((call) => call.method === 'POST' && call.url === '/api/workflows/wf-1/run')!
    // The version on screen goes with the run, so a newer one saved meanwhile is never run unseen.
    expect(post.body).toEqual({ inputs: { customer: 'Acme Pty Ltd' }, version: 0 })
  })

  it('refuses to run a workflow someone else saved since it was opened, and offers the latest version', async () => {
    signIn(EDITOR)
    renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    stored = { ...stored, version: 3 }
    fireEvent.click(screen.getByRole('button', { name: 'Run' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Run now' }))

    expect(await screen.findByText(/Someone else saved this workflow while you were editing it/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Load the latest version' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(window.location.pathname).toBe('/workflows/wf-1')
  })

  it('starts one run however often Enter is pressed in a value field while it starts', async () => {
    signIn(EDITOR)
    stored = workflow({
      graph: {
        nodes: [{ id: 'a', type: 'agent', agentId: 'rhea', title: null, instruction: 'Look up {{customer}}.', connectors: [], x: 0, y: 0 }],
        edges: [],
      },
    })
    renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.click(screen.getByRole('button', { name: 'Run' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('customer'), { target: { value: 'Acme' } })
    let release = () => {}
    hold = new Promise((resolve) => {
      release = resolve
    })
    const form = within(dialog).getByLabelText('customer').closest('form')!
    fireEvent.submit(form)
    fireEvent.submit(form)
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Run now' })).toBeDisabled())
    fireEvent.submit(form)
    release()

    await waitFor(() => expect(window.location.pathname).toBe('/workflows/run/goal-9'))
    expect(calls.filter((call) => call.method === 'POST' && call.url === '/api/workflows/wf-1/run')).toHaveLength(1)
  })

  it('saves once however quickly Save and run is pressed twice, and never reports its own save as a conflict', async () => {
    signIn(EDITOR)
    renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Reply, faster' } })
    let release = () => {}
    hold = new Promise((resolve) => {
      release = resolve
    })
    const button = screen.getByRole('button', { name: 'Save and run' })
    fireEvent.click(button)
    fireEvent.click(button)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save and run' })).toBeDisabled())
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    release()

    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(calls.filter((call) => call.method === 'PUT')).toHaveLength(1)
    expect(screen.queryByText(/Someone else saved this workflow/)).not.toBeInTheDocument()
  })

  it('creates one new workflow however quickly Save and run is pressed twice', async () => {
    signIn(EDITOR)
    renderEditor(null)
    await screen.findByRole('heading', { level: 1, name: 'Untitled workflow' })
    fireEvent.click(screen.getByRole('button', { name: 'Add a step for Rhea' }))
    fireEvent.change(await screen.findByLabelText('Instruction'), { target: { value: 'Summarise the week.' } })
    act(() => {
      fireEvent.keyDown(screen.getByRole('button', { name: /^Step 1:/ }), { key: 'Escape' })
    })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Weekly summary' } })
    const button = screen.getByRole('button', { name: 'Save and run' })
    fireEvent.click(button)
    fireEvent.click(button)

    await waitFor(() => expect(window.location.pathname).toBe('/workflows/wf-new'))
    expect(calls.filter((call) => call.method === 'POST' && call.url === '/api/workflows')).toHaveLength(1)
  })

  it('refuses to run while a step needs a connector its agent is not granted, naming both', async () => {
    signIn(EDITOR)
    stored = workflow({
      graph: {
        nodes: [{ id: 'a', type: 'agent', agentId: 'rhea', title: null, instruction: 'Send it.', connectors: ['gmail'], x: 0, y: 0 }],
        edges: [],
      },
    })
    renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.click(screen.getByRole('button', { name: 'Run' }))

    expect(
      await screen.findByText(/This workflow cannot run yet\. Rhea is not allowed to use Gmail\. Give Rhea access to Gmail/),
    ).toBeInTheDocument()
    expect(calls.some((call) => call.method === 'POST')).toBe(false)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('marks a required connector the agent lacks, and adds connectors from the step panel', async () => {
    signIn(EDITOR)
    renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.keyDown(step(/^Step 1:/), { key: 'Enter' })
    fireEvent.change(screen.getByLabelText('Add a connector'), { target: { value: 'gmail' } })

    expect(step(/^Step 1: Research the customer\. Rhea\. Needs Gmail\. Rhea is not allowed to use Gmail/)).toHaveAttribute(
      'data-problem',
      'warning',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Remove Gmail from this step' }))
    expect(step(/^Step 1:/)).not.toHaveAttribute('data-problem')
  })

  it('asks before Back takes the person away with unsaved changes, and keeps the changes on this device when they leave', async () => {
    const items = memoryStorage()
    signIn(EDITOR)
    renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Reply, kept' } })

    // The browser's Back has already moved the address when popstate arrives.
    act(() => {
      window.history.replaceState(null, '', '/workflows')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    expect(await screen.findByRole('heading', { name: 'Leave without saving?' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/workflows/wf-1')
    expect(screen.getByLabelText('Name')).toHaveValue('Reply, kept')

    fireEvent.click(screen.getByRole('button', { name: 'Leave' }))
    expect(window.location.pathname).toBe('/workflows')
    // Written on leaving, not a moment later.
    expect(items.get(DRAFT_KEY)).toContain('Reply, kept')
  })

  it('writes the waiting draft when the editor closes before its moment came', async () => {
    const items = memoryStorage()
    signIn(EDITOR)
    const view = renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Closed quickly' } })
    expect(items.has(DRAFT_KEY)).toBe(false)
    view.unmount()
    expect(items.get(DRAFT_KEY)).toContain('Closed quickly')
  })

  it('keeps nothing after signing out, and writes nothing for a person who is no longer signed in', async () => {
    const items = memoryStorage()
    signIn(EDITOR)
    const view = renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Private words' } })
    await waitFor(() => expect(items.get(DRAFT_KEY)).toContain('Private words'), { timeout: 2000 })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'More private words' } })
    clearSession()
    expect([...items.keys()].some((key) => key.startsWith('aiwos:workflow-draft:'))).toBe(false)
    view.unmount()
    expect([...items.keys()].some((key) => key.startsWith('aiwos:workflow-draft:'))).toBe(false)
  })

  it('warns that restored edits are older than the saved workflow, and saves them against their own version', async () => {
    const items = memoryStorage()
    items.set(
      DRAFT_KEY,
      JSON.stringify({
        name: 'Mine from before',
        description: '',
        graph: (workflow().graph as object),
        baseVersion: 0,
        savedAt: '2026-10-12T08:30:00Z',
      }),
    )
    stored = workflow({ name: 'Renamed by Sam', version: 5 })
    signIn(EDITOR)
    renderEditor()
    expect(await screen.findByText(/Someone has saved this workflow since then/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Restore them' }))
    expect(screen.getByLabelText('Name')).toHaveValue('Mine from before')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(calls.some((call) => call.method === 'PUT')).toBe(true))
    expect(calls.find((call) => call.method === 'PUT')!.body).toMatchObject({ name: 'Mine from before', version: 0 })
    expect(await screen.findByText(/Someone else saved this workflow while you were editing it/)).toBeInTheDocument()
    expect(stored.name).toBe('Renamed by Sam')
  })

  it('keeps new edits on this device while an earlier draft is still on offer', async () => {
    const items = memoryStorage()
    items.set(DRAFT_KEY, JSON.stringify({ name: 'Older draft', description: '', graph: workflow().graph, baseVersion: 0, savedAt: '2026-10-12T08:30:00Z' }))
    signIn(EDITOR)
    renderEditor()
    expect(await screen.findByText(/that were not saved, kept on this device/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Newer edit' } })
    await waitFor(() => expect(items.get(DRAFT_KEY)).toContain('Newer edit'), { timeout: 2000 })
    expect(screen.getByRole('button', { name: 'Restore them' })).toBeInTheDocument()
  })

  it('keeps the editor and its unsaved edits when the agents and connectors cannot be read again', async () => {
    signIn(EDITOR)
    renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Not lost' } })
    paletteFails = true
    await act(async () => {
      await client.refetchQueries({ queryKey: ['workflows', 'palette'] })
    })
    expect(screen.getByLabelText('Name')).toHaveValue('Not lost')
    expect(step(/^Step 1:/)).toBeInTheDocument()
  })

  it('moves focus to the next step after deleting one from the step panel, and to the canvas after the last', async () => {
    signIn(EDITOR)
    const { container } = renderEditor()
    fireEvent.keyDown(await screen.findByRole('button', { name: /^Step 1:/ }), { key: 'Enter' })
    fireEvent.click(screen.getByRole('button', { name: 'Delete this step' }))
    await waitFor(() => expect(document.activeElement).toBe(container.querySelector('[data-node-id="b"]')))

    fireEvent.keyDown(step(/^Step 1: Draft the reply/), { key: 'Enter' })
    fireEvent.click(screen.getByRole('button', { name: 'Delete this step' }))
    await waitFor(() => expect(document.activeElement).toBe(container.querySelector('.wf-canvas')))
  })

  it('keeps focus in the step panel after removing a connector or a connection', async () => {
    signIn(EDITOR)
    renderEditor()
    fireEvent.keyDown(await screen.findByRole('button', { name: /^Step 1:/ }), { key: 'Enter' })
    fireEvent.change(screen.getByLabelText('Add a connector'), { target: { value: 'gmail' } })
    fireEvent.change(screen.getByLabelText('Add a connector'), { target: { value: 'github' } })

    fireEvent.click(screen.getByRole('button', { name: 'Remove Gmail from this step' }))
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Remove GitHub from this step' })))
    fireEvent.click(screen.getByRole('button', { name: 'Remove GitHub from this step' }))
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Connectors this step needs' })))

    fireEvent.click(screen.getByRole('button', { name: 'Remove the connection to Step 2: Mo' }))
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Hands over to' })))
  })

  it('moves focus to the step panel when a problem shows its step', async () => {
    signIn(EDITOR)
    stored = workflow({
      graph: {
        nodes: [
          { id: 'a', type: 'agent', agentId: 'rhea', title: null, instruction: 'Research the customer.', connectors: [], x: 0, y: 0 },
          { id: 'b', type: 'agent', agentId: 'mo', title: null, instruction: '', connectors: [], x: 400, y: 0 },
        ],
        edges: [],
      },
    })
    renderEditor()
    fireEvent.click(await screen.findByRole('button', { name: 'Show the step' }))
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Step 2' })))
  })

  it('offers a way from the canvas to the selected step’s panel, for a narrow screen where it sits below', async () => {
    signIn(EDITOR)
    renderEditor()
    fireEvent.keyDown(await screen.findByRole('button', { name: /^Step 2:/ }), { key: 'Enter' })
    fireEvent.click(screen.getByRole('button', { name: 'Edit step 2' }))
    expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Step 2' }))
  })

  it('lets someone who did not make the workflow run it, but not change it', async () => {
    signIn(EDITOR)
    stored = workflow({ canChange: false, createdBy: 'user-2' })
    renderEditor()
    const first = await screen.findByRole('button', { name: /^Step 1:/ })
    expect(screen.getByText(/Only the person who made this workflow, or someone who can cancel work, can change it/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Run' })).toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: 'Agents and connectors' })).not.toBeInTheDocument()
    fireEvent.keyDown(first, { key: 'Enter' })
    expect(screen.getByLabelText('Instruction')).toBeDisabled()
  })

  it('asks before leaving with unsaved changes', async () => {
    signIn(EDITOR)
    renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Reply to a customer, quickly' } })
    fireEvent.click(screen.getByRole('link', { name: 'Workflows' }))

    expect(await screen.findByRole('heading', { name: 'Leave without saving?' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/workflows/wf-1')
    fireEvent.click(screen.getByRole('button', { name: 'Leave' }))
    await waitFor(() => expect(window.location.pathname).toBe('/workflows'))
  })

  it('keeps unsaved work on this device and offers it back', async () => {
    const items = memoryStorage()
    signIn(EDITOR)
    const first = renderEditor()
    await screen.findByRole('button', { name: /^Step 1:/ })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Draft only' } })
    await waitFor(() => expect([...items.values()].some((value) => value.includes('Draft only'))).toBe(true), { timeout: 2000 })
    first.unmount()

    renderEditor()
    expect(await screen.findByText(/that were not saved, kept on this device/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Restore them' }))
    expect(screen.getByLabelText('Name')).toHaveValue('Draft only')
  })

  it('creates a new workflow on its first save and moves to its address', async () => {
    signIn(EDITOR)
    renderEditor(null)
    expect(await screen.findByRole('heading', { level: 1, name: 'Untitled workflow' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Add a step for Rhea' }))
    fireEvent.change(await screen.findByLabelText('Instruction'), { target: { value: 'Summarise the week.' } })
    act(() => {
      fireEvent.keyDown(screen.getByRole('button', { name: /^Step 1:/ }), { key: 'Escape' })
    })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Weekly summary' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(window.location.pathname).toBe('/workflows/wf-new'))
    const post = calls.find((call) => call.method === 'POST' && call.url === '/api/workflows')!
    expect(post.body).toMatchObject({ name: 'Weekly summary' })
    expect((post.body as { graph: { nodes: unknown[] } }).graph.nodes).toHaveLength(1)
  })

  it('shows a role that cannot start work the workflow without a way to change or run it', async () => {
    signIn(['task:read'])
    renderEditor()
    const first = await screen.findByRole('button', { name: /^Step 1:/ })
    expect(screen.getByText(/Your role can see this workflow and watch its runs/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Run' })).not.toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: 'Agents and connectors' })).not.toBeInTheDocument()
    fireEvent.keyDown(first, { key: 'Enter' })
    expect(screen.getByLabelText('Instruction')).toBeDisabled()
    fireEvent.keyDown(first, { key: 'Delete' })
    expect(step(/^Step 1:/)).toBeInTheDocument()
  })
})
