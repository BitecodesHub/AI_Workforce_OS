// @find: tests for the orchestrator board, live updates off, paused board, vitest, Orchestrator component tests, Orchestrator page
// @what: Automated tests that check the the orchestrator board screen (/orchestrator) behaves as users expect.
// @flow: Renders Orchestrator from Orchestrator.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Board, BoardApproval } from '../lib/queries'
import { useBoard } from '../lib/queries'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { Orchestrator } from './Orchestrator'

/*
 * The orchestrator with Live updates paused. The board keeps polling while paused, and every poll
 * is a new object (generatedAt moves on), so the page has to take a stream of fresh boards without
 * looping, whether the pause was saved from an earlier visit or pressed during this one. New
 * approvals are still announced while the board itself stays frozen.
 */

vi.mock('../lib/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/queries')>()
  return { ...actual, useBoard: vi.fn() }
})

const boardMock = vi.mocked(useBoard)

function makeBoard(generatedAt: string, approvals: BoardApproval[] = []): Board {
  return {
    generatedAt,
    timezone: 'UTC',
    window: 'PT2H',
    windowMinutes: 120,
    stats: {
      running: 0,
      waitingApproval: approvals.length,
      waitingInput: 0,
      queued: 0,
      held: 0,
      completedToday: 0,
      failedToday: 0,
      spendToday: 0,
      goalsCompletedToday: 0,
      goalsFailedToday: 0,
      directRuns: 0,
    },
    agents: [],
    goals: [],
    queue: [],
    timeline: [],
    questions: [],
    approvals,
    failedToday: [],
  }
}

function approval(id: string, summary: string): BoardApproval {
  return {
    id,
    runId: `run-${id}`,
    taskId: null,
    goalId: null,
    agentId: 'agent-1',
    tool: 'gmail.send_message',
    actionClass: 'OUTBOUND',
    summary,
    requestedAt: '2026-10-03T08:55:00Z',
    expiresAt: '2026-10-04T08:55:00Z',
    requestedBy: null,
    canDecide: true,
  }
}

/** Hands the page `board` as the poll's latest result, the way useBoard would after a fetch. */
function deliver(board: Board) {
  boardMock.mockReturnValue({
    data: board,
    error: null,
    isLoading: false,
    refetch: vi.fn(),
    dataUpdatedAt: Date.parse(board.generatedAt),
  } as unknown as ReturnType<typeof useBoard>)
}

const client = () => new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })

function renderPage() {
  const queryClient = client()
  const tree = () => (
    <QueryClientProvider client={queryClient}>
      <RouterProvider>
        <Orchestrator />
      </RouterProvider>
    </QueryClientProvider>
  )
  const view = render(tree())
  return { ...view, rerenderPage: () => view.rerender(tree()) }
}

/** The page header (eyebrow="Live coordination" over the Orchestrator title) is still on screen. */
function expectPageShown() {
  expect(screen.getByRole('heading', { level: 1, name: 'Orchestrator' })).toBeInTheDocument()
  expect(screen.getByText('Live coordination')).toBeInTheDocument()
}

/** Live updates now sits in the header's More actions menu, as a checkbox item. */
const liveButton = () => {
  if (!screen.queryByRole('menuitemcheckbox', { name: /Live updates/ })) {
    fireEvent.click(screen.getByRole('button', { name: 'More actions' }))
  }
  return screen.getByRole('menuitemcheckbox', { name: /Live updates/ })
}

const announced = (text: string) => screen.getAllByRole('status').some((region) => region.textContent === text)

/*
 * An in-memory localStorage. Recent Node versions define their own global localStorage, which
 * shadows jsdom's and reads as undefined without a --localstorage-file flag (see persist.test.ts).
 */
function memoryStorage(): Storage {
  const items = new Map<string, string>()
  return {
    get length() {
      return items.size
    },
    clear: () => items.clear(),
    getItem: (key) => items.get(key) ?? null,
    key: (index) => [...items.keys()][index] ?? null,
    removeItem: (key) => void items.delete(key),
    setItem: (key, value) => void items.set(key, String(value)),
  }
}

beforeEach(() => {
  window.history.replaceState(null, '', '/orchestrator')
  vi.stubGlobal('localStorage', memoryStorage())
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions: ['run:read'],
    displayName: 'Maya Manager',
    email: 'maya@demo.test',
    role: 'manager',
  })
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))),
  )
})

afterEach(() => {
  clearSession()
  boardMock.mockReset()
  vi.unstubAllGlobals()
})

describe('Orchestrator with Live updates off', () => {
  it('renders a board saved as paused from an earlier visit, and keeps rendering as new polls arrive', () => {
    localStorage.setItem('orc.live', 'false')
    deliver(makeBoard('2026-10-03T09:00:00Z'))
    const { rerenderPage } = renderPage()
    expectPageShown()
    expect(liveButton()).toHaveAttribute('aria-checked', 'false')
    expect(boardMock).toHaveBeenLastCalledWith({ window: 'PT2H', paused: true })

    deliver(makeBoard('2026-10-03T09:00:15Z'))
    expect(() => rerenderPage()).not.toThrow()
    expectPageShown()

    deliver(makeBoard('2026-10-03T09:00:30Z'))
    expect(() => rerenderPage()).not.toThrow()
    expectPageShown()
  })

  it('keeps rendering when Live updates is switched off during the visit and a new board then arrives', () => {
    deliver(makeBoard('2026-10-03T09:00:00Z'))
    const { rerenderPage } = renderPage()
    expect(liveButton()).toHaveAttribute('aria-checked', 'true')

    fireEvent.click(liveButton())
    expect(liveButton()).toHaveAttribute('aria-checked', 'false')
    expect(localStorage.getItem('orc.live')).toBe('false')

    deliver(makeBoard('2026-10-03T09:00:15Z'))
    expect(() => rerenderPage()).not.toThrow()
    expectPageShown()

    deliver(makeBoard('2026-10-03T09:00:30Z'))
    expect(() => rerenderPage()).not.toThrow()
    expectPageShown()
  })

  it('still announces a new approval while the board is paused', () => {
    localStorage.setItem('orc.live', 'false')
    deliver(makeBoard('2026-10-03T09:00:00Z', [approval('a1', 'Send the weekly report')]))
    const { rerenderPage } = renderPage()

    deliver(makeBoard('2026-10-03T09:00:15Z', [approval('a1', 'Send the weekly report'), approval('a2', 'Send the invoice')]))
    rerenderPage()
    expectPageShown()
    expect(announced('New approval waiting: Send the invoice.')).toBe(true)
  })
})
