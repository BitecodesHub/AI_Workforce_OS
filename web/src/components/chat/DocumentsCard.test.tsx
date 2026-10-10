// @find: tests for DocumentsCard, documents card, sources in chat, documents an agent used, knowledge documents in thread, cited documents
// @what: Automated tests for DocumentsCard.
// @flow: Run with the web test runner; covers DocumentsCard.
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessage, Passage } from '../../lib/queries'
import { clearSession, saveSession } from '../../lib/session'
import { DocumentsCard } from './DocumentsCard'

/*
 * The card for a question about documents lists the passages that matched, quoted and numbered,
 * through the same list the sources under an answer use.
 */

function passage(index: number, overrides: Partial<Passage & { sourceId: string | null }> = {}) {
  return {
    chunkId: `chunk-${index}`,
    documentId: `doc-${index}`,
    sourceId: 'source-1',
    documentTitle: `Document ${index}`,
    uri: null,
    pageNumber: index,
    heading: null,
    content: `Passage text ${index}.`,
    score: 0.9,
    ...overrides,
  }
}

function card(passages: unknown[], detail: Record<string, unknown> = {}): ChatMessage {
  return {
    id: 'documents-1',
    position: 3,
    authorKind: 'coordinator',
    authorId: null,
    agentId: null,
    kind: 'documents',
    content: 'Found passages.',
    detail: { query: 'What is our refund policy?', grounded: true, passages, ...detail } as ChatMessage['detail'],
    goalId: null,
    createdAt: '2026-10-04T00:00:00Z',
  }
}

beforeEach(() => {
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions: ['chat:use', 'task:create', 'knowledge:read'],
    displayName: 'Maya Manager',
    email: 'maya@example.com',
    role: 'manager',
  })
})

afterEach(() => {
  clearSession()
})

function renderCard(message: ChatMessage, onAnswer = vi.fn()) {
  render(<DocumentsCard message={message} onAnswerFromDocuments={onAnswer} answering={false} />)
  return onAnswer
}

describe('DocumentsCard', () => {
  it('quotes the first two passages, with a way to show the rest', () => {
    renderCard(card([passage(1), passage(2), passage(3)]))

    expect(screen.getByText(/\[1\] Document 1/)).toBeInTheDocument()
    expect(screen.getByText(/\[2\] Document 2/)).toBeInTheDocument()
    expect(screen.queryByText(/\[3\] Document 3/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Show 1 more' }))
    expect(screen.getByText(/\[3\] Document 3/)).toBeInTheDocument()
  })

  it('links a passage to its source in Knowledge', () => {
    renderCard(card([passage(1)]))

    expect(screen.getByRole('link', { name: 'Open in Knowledge' })).toHaveAttribute('href', '/knowledge/source-1')
  })

  it('still links a passage from an older card, which has no source, to its own address', () => {
    renderCard(card([passage(1, { sourceId: null, uri: 'https://example.org/policy' })]))

    expect(screen.getByRole('link', { name: 'Open source' })).toHaveAttribute('href', 'https://example.org/policy')
  })

  it('offers to write an answer from the passages, and says when the search was keyword-only', () => {
    const onAnswer = renderCard(card([passage(1)], { degraded: true }))

    expect(screen.getByText(/Keyword search only right now/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Write an answer from these passages' }))
    expect(onAnswer).toHaveBeenCalledTimes(1)
  })

  it('says plainly that no document supports an answer', () => {
    renderCard(card([], { grounded: false }))

    expect(screen.getByText('No document supports an answer.')).toBeInTheDocument()
    expect(screen.queryByText(/Keyword search only/)).toBeNull()
  })
})
