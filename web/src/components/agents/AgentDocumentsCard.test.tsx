// @find: tests for AgentDocumentsCard, agent documents, agent's own documents, agent files, private documents, upload file to agent, add note, remove document, agent-only knowledge, agent page, Documents card, agent:update
// @what: Automated tests for AgentDocumentsCard.
// @flow: Run with the web test runner; covers AgentDocumentsCard.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearSession, saveSession } from '../../lib/session'
import { ToastProvider } from '../../lib/toast'
import { AgentDocumentsCard } from './AgentDocumentsCard'

const DOCUMENTS = [
  { id: 'd1', title: 'Leave guide.txt', mediaType: 'text/plain', status: 'indexed', skipReason: null, chunkCount: 2, indexedAt: '2026-10-08T00:00:00Z', removedAtSource: false },
  { id: 'd2', title: 'Rosters.pdf', mediaType: 'application/pdf', status: 'pending', skipReason: null, chunkCount: 0, indexedAt: null, removedAtSource: false },
]

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

describe('AgentDocumentsCard', () => {
  it('names each Remove button for its document, and says when one is still being read', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify(DOCUMENTS), { status: 200, headers: { 'Content-Type': 'application/json' } })),
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
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <ToastProvider>
          <AgentDocumentsCard agentId="a1" agentName="HR" />
        </ToastProvider>
      </QueryClientProvider>,
    )
    expect(await screen.findByRole('button', { name: 'Remove Leave guide.txt' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remove Rosters.pdf' })).toBeInTheDocument()
    expect(screen.getByText('Being read')).toBeInTheDocument()
  })
})
