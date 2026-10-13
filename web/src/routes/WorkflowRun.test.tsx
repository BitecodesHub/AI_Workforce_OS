// @find: tests for the workflow run view, live status on the canvas, hand-off highlight, hand-off token, open a step's run, replay controls, play pause, speed, scrubber, previous and next event, reduced motion step by step, goal replay without a workflow, /workflows/run/:goalId
// @what: Tests that the run view shows each step's live status and hand-offs, opens a step's run, and replays a finished goal with working controls.
// @flow: Renders routes/WorkflowRun with a mocked fetch inside the query client, router and toast providers.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import type { GoalTimeline, TimelineTask } from '../lib/workflowQueries'
import { WorkflowRun } from './WorkflowRun'

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

const T0 = Date.parse('2026-10-12T09:00:00Z')
const at = (seconds: number) => new Date(T0 + seconds * 1000).toISOString()

const task = (id: string, position: number, extra: Partial<TimelineTask> = {}): TimelineTask => ({
  id,
  title: id.charAt(0).toUpperCase() + id.slice(1),
  agentId: `agent-${id}`,
  agentName: `Agent ${id}`,
  agentCategory: 'operations',
  position,
  dependsOn: [],
  status: 'pending',
  startedAt: null,
  completedAt: null,
  runId: null,
  ...extra,
})

const SNAPSHOT = {
  workflowId: 'wf-1',
  workflowRunId: 'wr-1',
  name: 'Reply to a customer',
  version: 1,
  graph: {
    nodes: [
      { id: 'n1', agentId: 'agent-research', instruction: 'Research', x: 0, y: 0 },
      { id: 'n2', agentId: 'agent-draft', instruction: 'Draft', x: 400, y: 0 },
      { id: 'n3', agentId: 'agent-review', instruction: 'Review', x: 800, y: 0 },
    ],
    edges: [
      { id: 'e1', from: 'n1', to: 'n2' },
      { id: 'e2', from: 'n2', to: 'n3' },
    ],
  },
  nodeIds: ['n1', 'n2', 'n3'],
}

function live(draftStatus: 'pending' | 'waiting_approval'): GoalTimeline {
  return {
    goalId: 'goal-1',
    title: 'Reply to a customer',
    status: 'running',
    createdAt: at(0),
    completedAt: null,
    now: at(100),
    tasks: [
      task('research', 0, { status: 'completed', startedAt: at(1), completedAt: at(60), runId: 'run-r' }),
      task('draft', 1, {
        dependsOn: ['research'],
        status: draftStatus,
        startedAt: draftStatus === 'pending' ? null : at(61),
        runId: draftStatus === 'pending' ? null : 'run-d',
      }),
      task('review', 2, { dependsOn: ['draft'] }),
    ],
    runs: [
      { id: 'run-r', taskId: 'research', status: 'completed', startedAt: at(1), completedAt: at(60) },
      ...(draftStatus === 'pending' ? [] : [{ id: 'run-d', taskId: 'draft', status: 'waiting_approval', startedAt: at(61), completedAt: null }]),
    ],
    waits: draftStatus === 'pending' ? [] : [{ runId: 'run-d', taskId: 'draft', kind: 'approval', startedAt: at(70), endedAt: null, outcome: 'pending' }],
    workflow: SNAPSHOT,
  }
}

function finished(): GoalTimeline {
  return {
    goalId: 'goal-1',
    title: 'A chat request in three steps',
    status: 'completed',
    createdAt: at(0),
    completedAt: at(130),
    now: at(500),
    tasks: [
      task('research', 0, { status: 'completed', startedAt: at(1), completedAt: at(60), runId: 'run-r' }),
      task('draft', 1, { status: 'completed', startedAt: at(61), completedAt: at(120), runId: 'run-d' }),
      task('review', 2, { status: 'skipped' }),
    ],
    runs: [
      { id: 'run-r', taskId: 'research', status: 'completed', startedAt: at(1), completedAt: at(60) },
      { id: 'run-d', taskId: 'draft', status: 'completed', startedAt: at(61), completedAt: at(120) },
    ],
    waits: [{ runId: 'run-r', taskId: 'research', kind: 'approval', startedAt: at(10), endedAt: at(40), outcome: 'approved' }],
    workflow: null,
  }
}

let timeline: GoalTimeline
let timelineReads: number

beforeEach(() => {
  timeline = live('pending')
  timelineReads = 0
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/goals/goal-1/timeline') {
        timelineReads += 1
        return json(timeline)
      }
      return new Response(null, { status: 404 })
    }),
  )
})

afterEach(() => {
  clearSession()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  window.history.replaceState(null, '', '/')
})

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

function renderRun() {
  window.history.replaceState(null, '', '/workflows/run/goal-1')
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <WorkflowRun goalId="goal-1" />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
  return { ...view, client }
}

const step = (pattern: RegExp) => screen.getByRole('button', { name: pattern })

/** Lets the page animate: matchMedia reports no preference for reduced motion. */
function allowMotion() {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
  }))
}

describe('Workflow run view, live', () => {
  it('shows each step’s live status on the canvas the workflow ran, with a summary', async () => {
    signIn(['task:read', 'run:read'])
    timeline = live('waiting_approval')
    renderRun()

    expect(await screen.findByRole('heading', { level: 1, name: 'Reply to a customer' })).toBeInTheDocument()
    expect(screen.getByText('Workflow run')).toBeInTheDocument()
    expect(step(/^Step 1: Research\. Agent research\. Completed\./)).toHaveAttribute('data-status', 'completed')
    expect(step(/^Step 2: Draft\. Agent draft\. Waiting for approval\./)).toHaveAttribute('data-status', 'approval')
    expect(step(/^Step 3: Review\. Agent review\. Queued\./)).toHaveAttribute('data-status', 'queued')
    expect(screen.getByRole('status')).toHaveTextContent('Live: 1 of 3 steps completed, 1 waiting for approval')
    expect(screen.getByRole('link', { name: 'Open the workflow' })).toHaveAttribute('href', '/workflows/wf-1')
    expect(screen.queryByRole('button', { name: 'Play the replay' })).not.toBeInTheDocument()
  })

  it('names a branch a rejection stopped, and never says the rejected step carried on', async () => {
    // As seen live on 13 Oct 2026: one branch rejected (its follower skipped) while another still waits.
    signIn(['task:read'])
    const ms = (value: number) => new Date(T0 + value).toISOString()
    timeline = {
      ...live('pending'),
      tasks: [
        task('research', 0, { explicitDependencies: true, status: 'cancelled', startedAt: at(1), completedAt: ms(29_002), runId: 'run-r' }),
        task('draft', 1, { explicitDependencies: true, status: 'waiting_approval', startedAt: at(1), runId: 'run-d' }),
        task('review', 2, { explicitDependencies: true, dependsOn: ['research'], status: 'skipped', completedAt: ms(29_003) }),
      ],
      runs: [
        { id: 'run-r', taskId: 'research', status: 'cancelled', startedAt: at(1), completedAt: ms(29_001) },
        { id: 'run-d', taskId: 'draft', status: 'waiting_approval', startedAt: at(1), completedAt: null },
      ],
      waits: [
        { runId: 'run-r', taskId: 'research', kind: 'approval', startedAt: at(5), endedAt: ms(29_000), outcome: 'rejected' },
        { runId: 'run-d', taskId: 'draft', kind: 'approval', startedAt: at(12), endedAt: null, outcome: 'pending' },
      ],
    }
    renderRun()
    await screen.findByRole('heading', { level: 1, name: 'Reply to a customer' })
    expect(screen.getByRole('status')).toHaveTextContent('Live: 0 of 3 steps completed, 1 waiting for approval, 1 cancelled, 1 skipped')
    const events = within(screen.getByRole('region', { name: 'What happened' })).getAllByRole('listitem').map((item) => item.textContent)
    expect(events.some((text) => /Research carried on/.test(text ?? ''))).toBe(false)
    expect(events.some((text) => /Research: cancelled/.test(text ?? ''))).toBe(true)
  })

  it('highlights the connection when a step hands over, without motion under reduced motion', async () => {
    signIn(['task:read'])
    const { client, container } = renderRun()
    await screen.findByRole('heading', { level: 1, name: 'Reply to a customer' })
    expect(container.querySelector('[data-edge-id="e1"]')).not.toHaveAttribute('data-active')

    timeline = live('waiting_approval')
    await act(async () => {
      await client.refetchQueries()
    })

    await waitFor(() => expect(container.querySelector('[data-edge-id="e1"]')).toHaveAttribute('data-active'))
    expect(container.querySelector('.wf-token')).toBeNull()
    expect(timelineReads).toBe(2)
  })

  it('sends a token along the connection when motion is allowed', async () => {
    allowMotion()
    signIn(['task:read'])
    const { client, container } = renderRun()
    await screen.findByRole('heading', { level: 1, name: 'Reply to a customer' })

    timeline = live('waiting_approval')
    await act(async () => {
      await client.refetchQueries()
    })

    // Compared as booleans: a failed interim check must not print the element, whose React props
    // carry a key getter that warns when read.
    const hasToken = () => container.querySelector('.wf-token') !== null
    await waitFor(() => expect(hasToken()).toBe(true))
    await waitFor(() => expect(hasToken()).toBe(false), { timeout: 2000 })
  })

  it('opens a step’s run beside the canvas, and explains when the role cannot read runs', async () => {
    signIn(['task:read', 'run:read'])
    timeline = live('waiting_approval')
    renderRun()
    fireEvent.keyDown(await screen.findByRole('button', { name: /^Step 2: Draft/ }), { key: 'Enter' })

    const sheet = await screen.findByRole('dialog')
    expect(within(sheet).getByRole('heading', { name: 'Draft' })).toBeInTheDocument()
    expect(within(sheet).getByRole('link', { name: 'Open the full run' })).toHaveAttribute('href', '/runs/run-d')

    fireEvent.keyDown(step(/^Step 3: Review/), { key: 'Enter' })
    expect(await screen.findByText(/This step has not started yet/)).toBeInTheDocument()
  })

  it('tells a role without run:read why the run is not shown', async () => {
    signIn(['task:read'])
    timeline = live('waiting_approval')
    renderRun()
    fireEvent.keyDown(await screen.findByRole('button', { name: /^Step 1: Research/ }), { key: 'Enter' })
    expect(await screen.findByText('Your role does not include this')).toBeInTheDocument()
  })
})

describe('Workflow run view, replay', () => {
  it('replays a finished goal that no workflow started, ending on how it finished', async () => {
    signIn(['task:read'])
    timeline = finished()
    renderRun()

    expect(await screen.findByText('Goal replay')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Play the replay' })).toBeInTheDocument()
    expect(step(/^Step 1: Research/)).toHaveAttribute('data-status', 'completed')
    expect(step(/^Step 3: Review/)).toHaveAttribute('data-status', 'skipped')
    expect(screen.getByRole('button', { name: '10x' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText(/Your device asks for less motion/)).toBeInTheDocument()
  })

  it('says why a skipped step of a finished goal never ran, instead of promising it will start', async () => {
    signIn(['task:read', 'run:read'])
    timeline = finished()
    renderRun()
    fireEvent.keyDown(await screen.findByRole('button', { name: /^Step 3: Review/ }), { key: 'Enter' })
    const sheet = await screen.findByRole('dialog')
    expect(within(sheet).getByText('This step was skipped because a step it depends on did not complete.')).toBeInTheDocument()
    expect(within(sheet).queryByText(/has not started yet/)).not.toBeInTheDocument()
  })

  it('marks the current event in the log for assistive technology, not only by colour', async () => {
    signIn(['task:read'])
    timeline = finished()
    renderRun()
    const slider = await screen.findByLabelText('Replay position')
    fireEvent.change(slider, { target: { value: '10000' } })
    const current = screen.getByRole('button', { name: /Research is waiting for approval/ }).closest('li')!
    expect(current).toHaveAttribute('aria-current', 'step')
    expect(screen.getByRole('button', { name: /Research started/ }).closest('li')).not.toHaveAttribute('aria-current')
  })

  it('lets the scrubber reach the very end when the replay is not a whole number of seconds long', async () => {
    signIn(['task:read'])
    // Draft finishes at 200.45 s and the goal ends at 200.46 s.
    timeline = {
      ...finished(),
      completedAt: new Date(T0 + 200_460).toISOString(),
      tasks: [
        task('research', 0, { status: 'completed', startedAt: at(1), completedAt: at(60), runId: 'run-r' }),
        task('draft', 1, { status: 'completed', startedAt: at(61), completedAt: new Date(T0 + 200_450).toISOString(), runId: 'run-d' }),
        task('review', 2, { status: 'skipped' }),
      ],
      runs: [
        { id: 'run-r', taskId: 'research', status: 'completed', startedAt: at(1), completedAt: at(60) },
        { id: 'run-d', taskId: 'draft', status: 'completed', startedAt: at(61), completedAt: new Date(T0 + 200_450).toISOString() },
      ],
    }
    renderRun()
    const slider = await screen.findByLabelText<HTMLInputElement>('Replay position')
    expect(slider).toHaveAttribute('max', '200460')
    // The scrubber counts milliseconds: its last value is the replay's end, not the last whole second.
    expect(slider.step).toBe('1')
    fireEvent.change(slider, { target: { value: '0' } })
    fireEvent.keyDown(slider, { key: 'End' })
    expect(slider.value).toBe('200460')
    expect(step(/^Step 2: Draft/)).toHaveAttribute('data-status', 'completed')
    // The arrow keys move a second (or a hundredth of the replay) at a time, not a millisecond.
    fireEvent.keyDown(slider, { key: 'ArrowLeft' })
    expect(Number(slider.value)).toBe(Math.round(200460 - 2004.6))
    expect(step(/^Step 2: Draft/)).toHaveAttribute('data-status', 'running')
    fireEvent.keyDown(slider, { key: 'Home' })
    expect(slider.value).toBe('0')
  })

  it('moves through the replay with the scrubber and the event buttons, showing the state at each moment', async () => {
    signIn(['task:read'])
    timeline = finished()
    renderRun()
    const slider = await screen.findByLabelText('Replay position')

    fireEvent.change(slider, { target: { value: '0' } })
    expect(step(/^Step 1: Research/)).toHaveAttribute('data-status', 'queued')
    expect(step(/^Step 2: Draft/)).toHaveAttribute('data-status', 'queued')

    fireEvent.click(screen.getByRole('button', { name: 'Next event' }))
    expect(step(/^Step 1: Research/)).toHaveAttribute('data-status', 'running')
    fireEvent.click(screen.getByRole('button', { name: 'Next event' }))
    expect(step(/^Step 1: Research/)).toHaveAttribute('data-status', 'approval')
    expect(slider).toHaveAttribute('aria-valuetext', expect.stringContaining('10 s in'))

    fireEvent.change(slider, { target: { value: '70000' } })
    expect(step(/^Step 1: Research/)).toHaveAttribute('data-status', 'completed')
    expect(step(/^Step 2: Draft/)).toHaveAttribute('data-status', 'running')
    expect(screen.getByRole('button', { name: /Research handed over to Draft/ })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Previous event' }))
    expect(step(/^Step 2: Draft/)).toHaveAttribute('data-status', 'running')
    fireEvent.click(screen.getByRole('button', { name: /Research is waiting for approval/ }))
    expect(step(/^Step 1: Research/)).toHaveAttribute('data-status', 'approval')
    expect(step(/^Step 2: Draft/)).toHaveAttribute('data-status', 'queued')
  })

  it('chooses a speed and toggles play and pause', async () => {
    allowMotion()
    signIn(['task:read'])
    timeline = finished()
    renderRun()
    // Toggle buttons in a labelled group, as the console's other single-choice switches are.
    expect(await screen.findByRole('group', { name: 'Replay speed' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '60x' }))
    expect(screen.getByRole('button', { name: '60x' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '10x' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Play the replay' }))
    expect(screen.getByRole('button', { name: 'Pause the replay' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Pause the replay' }))
    expect(screen.getByRole('button', { name: 'Play the replay' })).toBeInTheDocument()
  })

  it('under reduced motion, playing steps one event a second from the start', async () => {
    signIn(['task:read'])
    timeline = finished()
    renderRun()
    await screen.findByRole('button', { name: 'Play the replay' })
    vi.useFakeTimers()

    fireEvent.click(screen.getByRole('button', { name: 'Play the replay' }))
    expect(step(/^Step 1: Research/)).toHaveAttribute('data-status', 'queued')
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(step(/^Step 1: Research/)).toHaveAttribute('data-status', 'running')
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(step(/^Step 1: Research/)).toHaveAttribute('data-status', 'approval')
  })
})
