// @find: tests for the goal sheet replay link, replay any goal with task chains, watch a running goal on a canvas, GoalSheet
// @what: Tests that a goal's sheet on the Orchestrator offers its replay (or its live canvas while it runs) for a goal of several steps, and not for a single task.
// @flow: Renders components/orchestrator/GoalSheet with a minimal board, mocked fetch and a saved session
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../../lib/router'
import { clearSession, saveSession } from '../../lib/session'
import { ToastProvider } from '../../lib/toast'
import type { Board, BoardGoal, BoardTask } from '../../lib/queries'
import { GoalSheet } from './GoalSheet'

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

const task = (id: string, position: number, status: string): BoardTask =>
  ({
    id,
    goalId: 'goal-1',
    agentId: null,
    title: `Step ${id}`,
    instruction: '',
    status,
    position,
    dependsOn: [],
    attempt: 1,
    maxAttempts: 2,
    result: null,
    failureReason: null,
    startedAt: null,
    completedAt: null,
    runId: null,
    runStatus: null,
    stepCount: null,
    cost: null,
  }) as unknown as BoardTask

const goal = (status: string, tasks: BoardTask[]): BoardGoal =>
  ({
    id: 'goal-1',
    title: 'Answer the supplier',
    description: '',
    status,
    createdAt: '2026-10-12T09:00:00Z',
    completedAt: status === 'running' ? null : '2026-10-12T09:10:00Z',
    source: 'manual',
    requestedBy: 'user-1',
    conversationId: null,
    scheduleId: null,
    tasks,
  }) as unknown as BoardGoal

const board = (goals: BoardGoal[]): Board =>
  ({
    generatedAt: '2026-10-12T09:10:00Z',
    timezone: 'Australia/Sydney',
    window: 'H2',
    windowMinutes: 120,
    stats: {},
    agents: [],
    goals,
    queue: [],
    timeline: [],
    questions: [],
    approvals: [],
    failedToday: [],
  }) as unknown as Board

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))),
  )
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions: ['task:read'],
    displayName: 'Maya Manager',
    email: 'maya@demo.test',
    role: 'manager',
  })
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

function renderSheet(subject: BoardGoal) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <GoalSheet goalId="goal-1" board={board([subject])} orderedGoalIds={['goal-1']} onClose={() => {}} onNavigate={() => {}} />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
}

describe('GoalSheet replay link', () => {
  it('offers the replay of a finished goal of several steps', () => {
    renderSheet(goal('completed', [task('a', 0, 'completed'), task('b', 1, 'completed')]))
    expect(screen.getByRole('link', { name: 'Replay' })).toHaveAttribute('href', '/workflows/run/goal-1')
  })

  it('offers the live canvas while a goal of several steps runs', () => {
    renderSheet(goal('running', [task('a', 0, 'completed'), task('b', 1, 'running')]))
    expect(screen.getByRole('link', { name: 'Watch it on a canvas' })).toHaveAttribute('href', '/workflows/run/goal-1')
  })

  it('offers no canvas for a single task', () => {
    renderSheet(goal('completed', [task('a', 0, 'completed')]))
    expect(screen.queryByRole('link', { name: 'Replay' })).not.toBeInTheDocument()
  })
})
