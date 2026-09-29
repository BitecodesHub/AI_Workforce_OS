import axe from 'axe-core'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
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

function renderComposer() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <Composer
        conversationId={null}
        agents={AGENTS}
        onSend={async () => true}
        sending={false}
        replyTo={null}
        onClearReply={() => {}}
        lastUserText={null}
        inputRef={createRef()}
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
