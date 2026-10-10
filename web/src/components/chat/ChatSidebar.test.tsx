// @find: tests for ChatSidebar, conversation list, chat sidebar, search conversations, new conversation, Mine Everyone scope, pinned, archive conversation, rename conversation, delete conversation, needs you, Chat page
// @what: Automated tests for ChatSidebar.
// @flow: Run with the web test runner; covers ChatSidebar.
import axe from 'axe-core'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { createRef, useState } from 'react'
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

let PAGE_OVERRIDE: ConversationPage | null = null

const PAGE: ConversationPage = {
  pinned: [],
  needsYou: [],
  conversations: [
    conversation({ id: 'c1', title: 'First conversation' }),
    conversation({ id: 'c2', title: 'Second conversation', visibility: 'private', lastMessagePreview: 'Which applicants are shortlisted' }),
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
      if (url.startsWith('/api/conversations')) return Promise.resolve(jsonResponse(PAGE_OVERRIDE ?? PAGE))
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
  PAGE_OVERRIDE = null
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

  it('shows only the title and a short time, with no preview, lock or status line', async () => {
    renderSidebar('panel')
    const second = await screen.findByRole('link', { name: /Second conversation/ })
    expect(second).not.toHaveTextContent('Which applicants are shortlisted')
    expect(second.querySelector('.chat-row-lock')).toBeNull()
    expect(second.querySelector('.chat-row-title')).toHaveTextContent('Second conversation')
    const time = second.querySelector('time')
    expect(time).toHaveAttribute('datetime', '2026-09-28T00:00:00Z')
    expect(time?.textContent).toMatch(/^(Now|\d+m|\d+h|Yesterday|\d{1,2} [A-Z][a-z]{2}( \d{4})?)$/)
    expect(second.children).toHaveLength(2)
  })

  it('marks a conversation that needs the viewer with a labelled dot, under Needs you', async () => {
    PAGE_OVERRIDE = {
      ...PAGE,
      needsYou: [conversation({ id: 'n1', title: 'Approve the quote', activity: 'needs_answer' })],
    }
    renderSidebar('panel')
    const row = await screen.findByRole('link', { name: /Approve the quote/ })
    expect(screen.getByRole('button', { name: 'Needs you' })).toHaveAttribute('aria-expanded', 'true')
    expect(within(row).getByRole('img', { name: 'Needs your answer' })).toBeInTheDocument()
    expect(row).not.toHaveTextContent('Needs your answer')
    const plain = screen.getByRole('link', { name: /First conversation/ })
    expect(within(plain).queryByRole('img')).toBeNull()
  })

  it('floats the row menu on top of the list, outside it, with Delete set apart', async () => {
    const { container } = renderSidebar('panel')
    const first = await screen.findByRole('link', { name: /First conversation/ })
    fireEvent.click(screen.getByRole('button', { name: 'Actions for First conversation' }))
    const menu = screen.getByRole('menu')
    // Portalled to the body: no scrolling or clipped ancestor in the sidebar can cover it.
    expect(container.contains(menu)).toBe(false)
    expect(menu.parentElement).toBe(document.body)
    expect(menu).toHaveClass('menu-panel-floating')
    expect(menu.style.position).toBe('fixed')
    expect(screen.getByRole('menuitem', { name: 'Rename' })).toHaveFocus()
    const items = Array.from(menu.children)
    expect(items.at(-2)).toHaveAttribute('role', 'separator')
    expect(items.at(-1)).toHaveClass('menu-item-danger')
    fireEvent.keyDown(screen.getByRole('menuitem', { name: 'Rename' }), { key: 'Escape' })
    expect(screen.queryByRole('menu')).toBeNull()
    expect(screen.getByRole('button', { name: 'Actions for First conversation' })).toHaveFocus()
    expect(first).toBeInTheDocument()
  })

  it('moves focus between rows with the arrow keys', async () => {
    renderSidebar('panel')
    const first = await screen.findByRole('link', { name: /First conversation/ })
    const second = screen.getByRole('link', { name: /Second conversation/ })
    first.focus()
    fireEvent.keyDown(first, { key: 'ArrowDown' })
    expect(second).toHaveFocus()
    expect(second).toHaveAttribute('tabindex', '0')
    fireEvent.keyDown(second, { key: 'ArrowUp' })
    expect(first).toHaveFocus()
  })

  it('puts search, New conversation and Hide on one row, with no visible shortcut hint', async () => {
    const { container } = renderSidebar('panel')
    await screen.findByRole('link', { name: /First conversation/ })
    const header = container.querySelector('.chat-sidebar-header')!
    const search = within(header as HTMLElement).getByRole('searchbox', { name: 'Search conversations' })
    expect(search).toHaveAttribute('aria-keyshortcuts')
    expect(within(header as HTMLElement).getByRole('button', { name: 'New conversation' })).toHaveClass('icon-button')
    expect(within(header as HTMLElement).getByRole('button', { name: 'Hide conversations' })).toBeInTheDocument()
    expect(container.querySelector('kbd')).toBeNull()
    expect(header.textContent).not.toMatch(/(Cmd|Ctrl) K/)
  })
})

describe('ChatSidebar collapse focus', () => {
  function Harness() {
    const [variant, setVariant] = useState<'panel' | 'rail'>('panel')
    return (
      <ChatSidebar
        variant={variant}
        onExpand={() => setVariant('panel')}
        onCollapse={() => setVariant('rail')}
        selectedId={null}
        onSelect={() => {}}
        onNew={() => {}}
        searchRef={createRef()}
        focusSection={null}
      />
    )
  }

  it('moves focus to the button that replaces the one pressed, so it is never left on the page', () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <Harness />
      </QueryClientProvider>,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Hide conversations' }))
    expect(screen.getByRole('button', { name: 'Show conversations' })).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: 'Show conversations' }))
    expect(screen.getByRole('button', { name: 'Hide conversations' })).toHaveFocus()
  })
})

