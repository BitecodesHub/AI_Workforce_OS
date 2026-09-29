import axe from 'axe-core'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { createRef } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Conversation, ConversationPage } from '../../lib/queries'
import { ChatSidebar } from './ChatSidebar'

/*
 * The conversation sidebar (B1.3): axe on both the full panel and the collapsed rail, the rail's
 * own aria-expanded state, the conversation list as one Tab stop (roving tabindex), and Shift+F10
 * opening a row's actions menu from the keyboard, per C6's acceptance list for this file.
 */

function conversation(overrides: Partial<Conversation> & { id: string; title: string }): Conversation {
  return {
    createdBy: 'user-1',
    createdAt: '2026-09-28T00:00:00Z',
    updatedAt: '2026-09-28T00:00:00Z',
    lastMessagePreview: 'Hello there',
    messageCount: 2,
    pinned: false,
    archived: false,
    activity: 'idle',
    canManage: true,
    unread: false,
    match: null,
    ...overrides,
  }
}

const PAGE: ConversationPage = {
  pinned: [],
  needsYou: [],
  conversations: [
    conversation({ id: 'c1', title: 'First conversation' }),
    conversation({ id: 'c2', title: 'Second conversation' }),
  ],
  hasMore: false,
}

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function stubFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString()
      if (url.startsWith('/api/conversations')) return Promise.resolve(jsonResponse(PAGE))
      return Promise.reject(new Error(`Unexpected fetch in this test: ${url}`))
    }),
  )
}

function renderSidebar(variant: 'panel' | 'rail' = 'panel') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ChatSidebar
        variant={variant}
        onExpand={() => {}}
        onCollapse={() => {}}
        selectedId={null}
        onSelect={() => {}}
        onNew={() => {}}
        searchRef={createRef()}
        focusSection={null}
      />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  stubFetch()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ChatSidebar', () => {
  it('has no axe violations in the panel', async () => {
    const { container } = renderSidebar('panel')
    await screen.findByRole('link', { name: /First conversation/ })
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((v) => v.id)).toEqual([])
  })

  it('has no axe violations in the rail', async () => {
    const { container } = renderSidebar('rail')
    await screen.findByRole('button', { name: 'Needs you' })
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((v) => v.id)).toEqual([])
  })

  it('reports the rail as collapsed', async () => {
    renderSidebar('rail')
    expect(screen.getByRole('button', { name: 'Show conversations' })).toHaveAttribute('aria-expanded', 'false')
    await screen.findByRole('button', { name: 'Needs you' })
  })

  it('keeps the conversation list one Tab stop, with roving tabindex on the rows', async () => {
    renderSidebar('panel')
    const first = await screen.findByRole('link', { name: /First conversation/ })
    const second = await screen.findByRole('link', { name: /Second conversation/ })
    expect(first).toHaveAttribute('tabindex', '0')
    expect(second).toHaveAttribute('tabindex', '-1')
  })

  it('opens a row actions menu with Shift+F10', async () => {
    renderSidebar('panel')
    const first = await screen.findByRole('link', { name: /First conversation/ })
    fireEvent.keyDown(first, { key: 'F10', shiftKey: true })
    expect(await screen.findByRole('menu')).toBeInTheDocument()
  })
})
