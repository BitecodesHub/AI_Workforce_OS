// @find: tests for AgentMemoryCard, agent memory, what the agent remembers, notes, add note, edit note, pin note, unpin note, forget everything, memory kind, secret refused, agent page, Memory card, PUT/DELETE memory
// @what: Automated tests for AgentMemoryCard.
// @flow: Run with the web test runner; covers AgentMemoryCard.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { AgentMemory } from '../../lib/agentMemoryQueries'
import { clearSession, saveSession } from '../../lib/session'
import { ToastProvider } from '../../lib/toast'
import { AgentMemoryCard, NoteRow } from './AgentMemoryCard'

if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const note = {
  id: 'n1',
  content: 'Our main competitor is CareCo.',
  kind: 'fact',
  source: 'agent',
  updatedAt: '2026-10-08T00:00:00Z',
  recallCount: 2,
} as unknown as AgentMemory

describe('NoteRow', () => {
  it('names the note each Edit and Remove button acts on, since every note has both', () => {
    render(
      <ul>
        <NoteRow note={note} canEdit onEdit={vi.fn()} onRemove={vi.fn()} />
      </ul>,
    )
    expect(screen.getByRole('button', { name: 'Edit note: Our main competitor is CareCo.' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remove note: Our main competitor is CareCo.' })).toBeInTheDocument()
  })
})

describe('AgentMemoryCard', () => {
  it('shows a refused secret at the note field, in the server’s words, not as a generic notice', async () => {
    const reason = 'That looks like a password, key or card number. Say what it is for, without the value.'
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string, init: RequestInit = {}) =>
        (init.method ?? 'GET') === 'POST'
          ? new Response(
              JSON.stringify({ code: 'validation_failed', detail: 'Some of the values supplied are not valid.', errors: { content: reason } }),
              { status: 422, headers: { 'Content-Type': 'application/json' } },
            )
          : new Response(JSON.stringify({ memories: [], total: 0, limit: 200 }), {
              status: 200,
              headers: { 'Content-Type': 'application/json' },
            }),
      ),
    )
    saveSession('token', {
      userId: 'u1',
      workspaceId: 'ws-1',
      permissions: ['agent:read', 'agent:update'],
      displayName: 'Olivia Owner',
      email: 'olivia@example.test',
      role: 'owner',
    })
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
        <ToastProvider>
          <AgentMemoryCard agentId="a1" agentName="HR" />
        </ToastProvider>
      </QueryClientProvider>,
    )
    const field = await screen.findByLabelText('Add a note')
    fireEvent.change(field, { target: { value: 'The key is sk-live-123456789012345678901234' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Remember this' }))
    })
    expect(await screen.findByText(reason)).toBeInTheDocument()
    expect(field).toHaveAccessibleDescription(expect.stringContaining(reason))
    expect(screen.queryByText(/Check this field/)).not.toBeInTheDocument()
    clearSession()
    vi.unstubAllGlobals()
  })

  function renderWith(notes: Array<Record<string, unknown>>, calls: Array<{ url: string; method: string }>) {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init: RequestInit = {}) => {
        const method = init.method ?? 'GET'
        calls.push({ url, method })
        const body =
          method === 'DELETE' && url === '/api/memory/agents/a1/memories'
            ? { removed: notes.length }
            : method === 'GET'
              ? { memories: notes, total: notes.length, limit: 200 }
              : { ...notes[0], pinned: method === 'PUT' }
        return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }),
    )
    saveSession('token', {
      userId: 'u1',
      workspaceId: 'ws-1',
      permissions: ['agent:read', 'agent:update'],
      displayName: 'Olivia Owner',
      email: 'olivia@example.test',
      role: 'owner',
    })
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
        <ToastProvider>
          <AgentMemoryCard agentId="a1" agentName="HR" />
        </ToastProvider>
      </QueryClientProvider>,
    )
  }

  it('pins a note with PUT and unpins a pinned one with DELETE, saying which note each button acts on', async () => {
    const calls: Array<{ url: string; method: string }> = []
    renderWith([{ ...note, pinned: false }, { ...note, id: 'n2', content: 'Sign off as the HR team.', pinned: true }], calls)

    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Pin note: Our main competitor is CareCo.' }))
    })
    expect(screen.getByText('Pinned')).toBeInTheDocument()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Unpin note: Sign off as the HR team.' }))
    })
    expect(calls.filter((call) => call.method !== 'GET')).toEqual([
      { url: '/api/memory/agents/a1/memories/n1/pin', method: 'PUT' },
      { url: '/api/memory/agents/a1/memories/n2/pin', method: 'DELETE' },
    ])
    clearSession()
    vi.unstubAllGlobals()
  })

  it('asks before forgetting everything, and forgets nothing when kept', async () => {
    const calls: Array<{ url: string; method: string }> = []
    renderWith([note], calls)

    fireEvent.click(await screen.findByRole('button', { name: 'Forget everything' }))
    expect(screen.getByRole('dialog', { name: 'Forget everything HR remembers?' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Keep the notes' }))
    expect(calls.some((call) => call.method === 'DELETE')).toBe(false)

    fireEvent.click(screen.getByRole('button', { name: 'Forget everything' }))
    await act(async () => {
      const dialog = screen.getByRole('dialog', { name: 'Forget everything HR remembers?' })
      fireEvent.click(within(dialog).getByRole('button', { name: 'Forget everything' }))
    })
    expect(calls.filter((call) => call.method === 'DELETE')).toEqual([{ url: '/api/memory/agents/a1/memories', method: 'DELETE' }])
    clearSession()
    vi.unstubAllGlobals()
  })
})
