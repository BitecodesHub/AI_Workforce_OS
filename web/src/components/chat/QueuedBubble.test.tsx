// @find: tests for QueuedBubble, queued message, message queue, waiting message, starts after current answer, send again, cancel queued, queued status
// @what: Automated tests for QueuedBubble.
// @flow: Run with the web test runner; covers QueuedBubble.
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { QueuedMessage } from '../../lib/queries'
import { EXPIRED_TEXT, QUEUED_TEXT, QueuedMessages, STARTING_TEXT, WAITING_DECISION_TEXT, queuedStatusText } from './QueuedBubble'

// jsdom has no modal dialog; the confirmation opens through one.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const item = (over: Partial<QueuedMessage> = {}): QueuedMessage => ({
  id: 'q1',
  order: 1,
  authorId: 'user-1',
  text: 'Summarise the Q3 quotes',
  agentIds: [],
  attachmentCount: 0,
  createdAt: '2026-10-08T00:00:00Z',
  updatedAt: '2026-10-08T00:00:00Z',
  status: 'queued',
  canManage: true,
  ...over,
})

function actions() {
  return {
    onCancel: vi.fn(async () => true),
    onEdit: vi.fn(async () => true),
    onStartNow: vi.fn(async () => true),
    onSendAgain: vi.fn(),
  }
}

describe('queuedStatusText', () => {
  it('names each state in plain words', () => {
    expect(queuedStatusText({ status: 'queued' }, 'working')).toBe(QUEUED_TEXT)
    expect(queuedStatusText({ status: 'queued' }, 'waiting_decision')).toBe(WAITING_DECISION_TEXT)
    expect(queuedStatusText({ status: 'starting' }, 'waiting_decision')).toBe(STARTING_TEXT)
    expect(queuedStatusText({ status: 'expired' }, 'idle')).toBe(EXPIRED_TEXT)
  })
})

describe('QueuedMessages', () => {
  it('shows a queued message with Cancel and Edit, never as a sent message', () => {
    render(<QueuedMessages items={[item()]} busy="working" {...actions()} />)
    expect(screen.getByText('Summarise the Q3 quotes')).toBeInTheDocument()
    expect(screen.getByText(QUEUED_TEXT)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Edit' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel queued message' })).toBeInTheDocument()
    expect(screen.queryByText('Start now anyway')).toBeNull()
    expect(document.querySelector('.chat-bubble-user')).toBeNull()
  })

  it('lists several in queue order', () => {
    render(
      <QueuedMessages
        items={[item({ id: 'b', order: 2, text: 'Second' }), item({ id: 'a', order: 1, text: 'First' })]}
        busy="working"
        {...actions()}
      />,
    )
    const bubbles = screen.getAllByRole('article')
    expect(bubbles.map((el) => el.getAttribute('aria-label'))).toEqual(['Queued message 1 of 2', 'Queued message 2 of 2'])
    expect(bubbles[0]).toHaveTextContent('First')
    expect(bubbles[1]).toHaveTextContent('Second')
  })

  it('cancels', async () => {
    const a = actions()
    render(<QueuedMessages items={[item()]} busy="working" {...a} />)
    fireEvent.click(screen.getByRole('button', { name: 'Cancel queued message' }))
    await waitFor(() => expect(a.onCancel).toHaveBeenCalledWith(expect.objectContaining({ id: 'q1' })))
  })

  it('edits inline: focus moves to the box, Save sends the new words, focus returns to Edit', async () => {
    const a = actions()
    render(<QueuedMessages items={[item()]} busy="working" {...a} />)
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const box = screen.getByLabelText('Change your queued message') as HTMLTextAreaElement
    expect(box).toHaveFocus()
    expect(box.value).toBe('Summarise the Q3 quotes')
    fireEvent.change(box, { target: { value: 'Summarise the Q4 quotes' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(a.onEdit).toHaveBeenCalledWith(expect.objectContaining({ id: 'q1' }), 'Summarise the Q4 quotes'))
    await waitFor(() => expect(screen.queryByLabelText('Change your queued message')).toBeNull())
    expect(screen.getByRole('button', { name: 'Edit' })).toHaveFocus()
  })

  it('brings the whole editor into view when it opens, so Save is not below the message box', () => {
    const scroll = vi.fn()
    const original = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = scroll
    try {
      render(<QueuedMessages items={[item()]} busy="working" {...actions()} />)
      fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
      expect(scroll).toHaveBeenCalledWith({ block: 'nearest' })
    } finally {
      Element.prototype.scrollIntoView = original
    }
  })

  it('leaves the edit with Escape, unchanged, and keeps Save off for empty words', () => {
    const a = actions()
    render(<QueuedMessages items={[item()]} busy="working" {...a} />)
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const box = screen.getByLabelText('Change your queued message')
    fireEvent.change(box, { target: { value: '   ' } })
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    fireEvent.keyDown(box, { key: 'Escape' })
    expect(screen.queryByLabelText('Change your queued message')).toBeNull()
    expect(a.onEdit).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Edit' })).toHaveFocus()
  })

  it('offers Start now anyway while work waits on a decision, after a plain confirmation', async () => {
    const a = actions()
    render(<QueuedMessages items={[item()]} busy="waiting_decision" {...a} />)
    expect(screen.getByText(WAITING_DECISION_TEXT)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Start now anyway' }))
    expect(await screen.findByText(/stops that work/)).toBeInTheDocument()
    expect(a.onStartNow).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Stop it and start this' }))
    await waitFor(() => expect(a.onStartNow).toHaveBeenCalledWith(expect.objectContaining({ id: 'q1' })))
  })

  it('shows Starting with no actions', () => {
    render(<QueuedMessages items={[item({ status: 'starting' })]} busy="working" {...actions()} />)
    expect(screen.getByText(STARTING_TEXT)).toBeInTheDocument()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('offers Send again and Remove for an expired message', async () => {
    const a = actions()
    render(<QueuedMessages items={[item({ status: 'expired' })]} busy="idle" {...a} />)
    expect(screen.getByText(EXPIRED_TEXT)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Send again' }))
    expect(a.onSendAgain).toHaveBeenCalledWith(expect.objectContaining({ id: 'q1' }))
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(a.onCancel).toHaveBeenCalled())
  })

  it('gives no actions on someone else’s queued message', () => {
    render(<QueuedMessages items={[item({ canManage: false })]} busy="working" {...actions()} />)
    expect(screen.getByText(QUEUED_TEXT)).toBeInTheDocument()
    expect(screen.queryByRole('button')).toBeNull()
  })
})
