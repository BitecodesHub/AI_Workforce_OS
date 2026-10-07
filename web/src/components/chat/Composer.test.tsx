import axe from 'axe-core'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { createRef } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Agent } from '../../lib/queries'
import { Composer } from './Composer'

/*
 * The composer's @mention list, checked with axe the way the live audit checked it: a textarea may
 * not carry role="combobox", and a listbox may hold only options, so each option's list item is
 * presentational and the no-match message is itself a disabled option.
 */

const AGENTS: Agent[] = [
  { id: 'a1', key: 'hr', name: 'HR', category: 'people', status: 'active', revision: 1 },
  { id: 'a2', key: 'support', name: 'Customer Support', category: 'support', status: 'active', revision: 1 },
] as Agent[]

type ReplyTarget = { questionId: string; agentName: string; agentId: string | null; auto: boolean }

function renderComposer(
  options: { replyTo?: ReplyTarget | null; onSend?: (text: string, agentIds: string[]) => Promise<boolean>; disabled?: boolean } = {},
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <Composer
        userId="user-1"
        conversationId={null}
        agents={AGENTS}
        onSend={options.onSend ?? (async () => true)}
        sending={false}
        replyTo={options.replyTo ?? null}
        onClearReply={() => {}}
        lastUserText={null}
        inputRef={createRef()}
        {...(options.disabled ? { disabled: true, readOnlyNote: 'Nothing can be sent here.' } : {})}
      />
    </QueryClientProvider>,
  )
}

function type(value: string) {
  const field = screen.getByRole('textbox', { name: 'Message the workforce' })
  fireEvent.change(field, { target: { value, selectionStart: value.length } })
  return field
}

beforeEach(() => {
  vi.stubGlobal('fetch', () => Promise.reject(new Error('No network in tests')))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('Composer mention list', () => {
  it('keeps the field a textbox that points at the open list', () => {
    renderComposer()
    const field = type('@')
    expect(field.getAttribute('role')).toBeNull()
    expect(field.getAttribute('aria-autocomplete')).toBe('list')
    expect(field.getAttribute('aria-controls')).toBe(screen.getByRole('listbox').id)
    expect(screen.getAllByRole('option')).toHaveLength(AGENTS.length)
  })

  it('has no axe violations with matches', async () => {
    const { container } = renderComposer()
    type('@')
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((v) => v.id)).toEqual([])
  })

  it('has no axe violations when nothing matches', async () => {
    const { container } = renderComposer()
    type('@zzzz')
    expect(screen.getByRole('option', { name: /No agent matches/ }).getAttribute('aria-disabled')).toBe('true')
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((v) => v.id)).toEqual([])
  })
})

/*
 * A question answered from the composer automatically gives way to a mention of some other agent,
 * and the composer says so before anything is sent. A reply the person chose stays a reply.
 */
describe('Composer while a question is targeted', () => {
  const auto: ReplyTarget = { questionId: 'q1', agentName: 'HR', agentId: 'a1', auto: true }

  it('turns an automatic answer that mentions another agent into a new request', () => {
    renderComposer({ replyTo: auto })
    expect(screen.getByText(/Answering HR's question/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send answer' })).toBeInTheDocument()

    type('@Support draft the reply')
    expect(screen.getByText('Will send as a new request to Customer Support')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Send answer' })).toBeNull()
  })

  it('still answers when only the asking agent is mentioned', () => {
    renderComposer({ replyTo: auto })
    type('@HR the whole team')
    expect(screen.getByText(/Answering HR's question/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send answer' })).toBeInTheDocument()
  })

  it('keeps a chosen reply a reply, whoever it mentions', () => {
    renderComposer({ replyTo: { ...auto, auto: false } })
    type('@Support see this too')
    expect(screen.getByText(/Replying to HR's question/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send answer' })).toBeInTheDocument()
  })

  it('hands the mentioned agents to the send, for Chat to route', async () => {
    const onSend = vi.fn(async () => true)
    renderComposer({ replyTo: auto, onSend })
    type('@Support draft the reply')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    })
    expect(onSend).toHaveBeenCalledWith('@Support draft the reply', ['a2'], [])
  })
})

describe('Composer drafts', () => {
  function memoryStorage(seed: Record<string, string> = {}): Storage {
    const items = new Map<string, string>(Object.entries(seed))
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

  const originalDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  let storage: Storage

  beforeEach(() => {
    storage = memoryStorage({ 'chat.draft.new': JSON.stringify('Someone else typed this'), 'chat.details': '"auto"' })
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: storage })
  })

  afterEach(() => {
    if (originalDescriptor) Object.defineProperty(globalThis, 'localStorage', originalDescriptor)
  })

  it('keeps the draft per person, and never shows an unscoped one', () => {
    vi.useFakeTimers()
    try {
      renderComposer()
      expect(screen.getByRole('textbox', { name: 'Message the workforce' })).toHaveValue('')
      type('Half a thought')
      act(() => {
        vi.advanceTimersByTime(400)
      })
      expect(storage.getItem('chat.draft.user-1.new')).toBe(JSON.stringify('Half a thought'))
    } finally {
      vi.useRealTimers()
    }
  })

  it('cannot be written in or sent from when disabled', () => {
    renderComposer({ disabled: true })
    expect(screen.getByRole('textbox', { name: 'Message the workforce' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()
    expect(screen.getByText('Nothing can be sent here.')).toBeInTheDocument()
  })
})
