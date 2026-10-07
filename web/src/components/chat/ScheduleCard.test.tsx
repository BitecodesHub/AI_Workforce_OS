import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessage, Schedule } from '../../lib/queries'
import { clearSession, saveSession } from '../../lib/session'
import { ScheduleCard } from './ScheduleCard'

/*
 * A schedule suggested in chat and already saved says what state the saved schedule is in, by the
 * same rule as the Schedules screen: a one-off that has already run reads Done, never Paused.
 */

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

const suggestion: ChatMessage = {
  id: 'm1',
  position: 0,
  authorKind: 'coordinator',
  authorId: null,
  agentId: null,
  kind: 'schedule_suggestion',
  content: 'A schedule for Hana: Once, on 1 October at 3:00 pm',
  detail: {
    name: 'Send the offsite agenda',
    agentId: 'agent-1',
    agentName: 'Hana',
    instruction: 'Send the offsite agenda',
    text: 'on 2026-10-01 at 3pm',
    description: 'Once, on 1 October at 3:00 pm',
    timezone: 'Australia/Melbourne',
    runAt: '2026-10-01T05:00:00Z',
    nextRuns: [],
  },
  goalId: null,
  createdAt: '2026-09-30T00:00:00Z',
}

function saved(overrides: Partial<Schedule>): Schedule {
  return {
    id: 'sched-1',
    name: 'Send the offsite agenda',
    agentId: 'agent-1',
    agentName: 'Hana',
    instruction: 'Send the offsite agenda',
    kind: 'once',
    cron: null,
    runAt: '2026-10-01T05:00:00Z',
    timezone: 'Australia/Melbourne',
    description: 'Once, on 1 October at 3:00 pm',
    enabled: true,
    overlapPolicy: 'skip',
    nextRunAt: '2026-10-01T05:00:00Z',
    lastRunAt: null,
    lastStatus: null,
    lastGoalId: null,
    consecutiveFailures: 0,
    pausedReason: null,
    createdBy: 'user-1',
    createdAt: '2026-09-30T00:00:00Z',
    ...overrides,
  }
}

function stubSchedules(rows: Schedule[]) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(rows)))
}

function renderCard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <ScheduleCard message={suggestion} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions: ['task:read', 'task:create'],
    displayName: 'Erin Employee',
    email: 'erin@example.com',
    role: 'employee',
  })
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

describe('ScheduleCard', () => {
  it('reads a saved one-off that has already run as Done', async () => {
    stubSchedules([saved({ enabled: false, nextRunAt: null, lastRunAt: '2026-10-01T05:00:00Z' })])
    renderCard()

    expect(await screen.findByText('Done')).toBeInTheDocument()
    expect(screen.queryByText('Paused')).toBeNull()
    expect(screen.getByText('Saved as a schedule.')).toBeInTheDocument()
  })

  it('reads a saved schedule still waiting for its time as Active', async () => {
    stubSchedules([saved({})])
    renderCard()

    expect(await screen.findByText('Active')).toBeInTheDocument()
  })

  it('offers to create the schedule when none matches', async () => {
    stubSchedules([])
    renderCard()

    expect(await screen.findByRole('button', { name: 'Create schedule' })).toBeInTheDocument()
  })
})
