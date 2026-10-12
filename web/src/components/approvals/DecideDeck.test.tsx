// @find: tests for decide mode, swipe approvals deck, undo window delays the POST, undo cancels, destructive confirmation, read-only cards skipped, keyboard A R F, swipe threshold gesture, grab handle, double tap guard, keys only in the deck, Nothing is waiting for you
// @what: Component tests for DecideDeck: one card at a time, decisions held behind Undo before they are sent, removals confirmed, read-only cards skipped and readable, swipes (up only from the handle), keys only while the deck has focus, focus and announcements, and a failed send brought back.
// @flow: Renders DecideDeck with a mocked onDecide; fake timers stand in for the four-second undo window
import axe from 'axe-core'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DecideDeck, NEW_CARD_GUARD_MS } from './DecideDeck'
import type { DeckDecision } from './DecideDeck'
import type { ApprovalItem } from '../../lib/approvalQueries'
import type { Agent } from '../../lib/queries'
import { UNDO_WINDOW_MS } from '../../lib/decisionHold'

// jsdom has the <dialog> element but not its modal methods; every sheet here opens with one.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

// jsdom has no PointerEvent; a mouse event carrying a pointer id is all the card reads.
if (typeof window.PointerEvent === 'undefined') {
  class PointerEventStandIn extends MouseEvent {
    pointerId: number
    pointerType: string
    constructor(type: string, init: PointerEventInit = {}) {
      super(type, init)
      this.pointerId = init.pointerId ?? 1
      this.pointerType = init.pointerType ?? 'touch'
    }
  }
  ;(window as unknown as { PointerEvent: unknown }).PointerEvent = PointerEventStandIn
}

const ME = 'user-1'
const NAMES: Record<string, string> = { 'user-1': 'Maya Manager', 'user-2': 'Priya Shah' }
const AGENTS = { 'agent-1': { id: 'agent-1', key: 'hr', name: 'HR Agent', category: 'operations', status: 'active', revision: 1 } as Agent }

function item(id: string, overrides: Partial<ApprovalItem> = {}): ApprovalItem {
  return {
    id,
    runId: `run-${id}`,
    agentId: 'agent-1',
    tool: 'gmail.send_message',
    actionClass: 'OUTBOUND',
    summary: `Send ${id} outside the workspace using gmail.send_message`,
    payload: JSON.stringify({ to: 'jane@customer.example', subject: `Welcome ${id}`, body: 'Hello Jane' }),
    status: 'pending',
    requestedAt: '2026-10-12T09:00:00Z',
    expiresAt: '2026-10-13T09:00:00Z',
    decidedBy: null,
    decidedAt: null,
    decisionNote: null,
    goalId: 'goal-1',
    goalTitle: 'Welcome the new starter',
    requestedBy: 'user-2',
    taskInstruction: 'Email the new starter their welcome pack.',
    canDecide: true,
    ...overrides,
  }
}

let onDecide: ReturnType<typeof vi.fn<(decision: DeckDecision) => Promise<void>>>
let onShowList: ReturnType<typeof vi.fn>

function renderDeck(approvals: ApprovalItem[], targetId: string | null = null) {
  return render(
    <DecideDeck
      approvals={approvals}
      agentNames={AGENTS}
      agentsKnown
      me={ME}
      nameOf={(userId) => NAMES[userId] ?? 'Someone'}
      targetId={targetId}
      hasMore={false}
      loadingMore={false}
      onLoadMore={() => {}}
      onDecide={onDecide}
      onShowList={onShowList}
    />,
  )
}

/** The title of the card on top of the deck (a sheet's title is a heading too). */
function cardTitle(): HTMLElement {
  const title = document.querySelector<HTMLElement>('.deck-card h2')
  if (!title) throw new Error('No card is on top of the deck')
  return title
}

/** The text of the title of the card on top of the deck. */
function topCard(): string {
  return cardTitle().textContent ?? ''
}

/** Presses a key with focus inside the deck: on the element that has focus there, else on the deck itself. */
function press(key: string, init: Record<string, unknown> = {}) {
  const deck = document.querySelector<HTMLElement>('.decide-deck')
  if (!deck) throw new Error('No deck')
  const focused = document.activeElement
  const target = focused instanceof HTMLElement && deck.contains(focused) ? focused : deck
  act(() => {
    fireEvent.keyDown(target, { key, ...init })
  })
}

function wait(ms: number) {
  act(() => {
    vi.advanceTimersByTime(ms)
  })
}

/** Drags the card from its title (or from the grab handle) by (dx, dy) and lets go. */
function swipe(dx: number, dy: number, release = true, from: 'title' | 'handle' = 'title') {
  const start = from === 'handle' ? document.querySelector<HTMLElement>('.deck-handle') : cardTitle()
  if (!start) throw new Error(`No ${from} to drag from`)
  fireEvent.pointerDown(start, { pointerId: 7, clientX: 200, clientY: 300, button: 0 })
  fireEvent.pointerMove(start, { pointerId: 7, clientX: 200 + dx, clientY: 300 + dy })
  if (release) fireEvent.pointerUp(start, { pointerId: 7, clientX: 200 + dx, clientY: 300 + dy })
}

/** The live region's words, without the no-break space that makes a repeat heard again. */
function announced(): string {
  return (document.querySelector('[role="status"]')?.textContent ?? '').replace(/\u00a0/g, '').trim()
}

beforeEach(() => {
  vi.useFakeTimers()
  onDecide = vi.fn(async () => {})
  onShowList = vi.fn()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('DecideDeck', () => {
  it('shows one request at a time: the agent, exactly what will be sent, who asked, the goal and the deadline', () => {
    renderDeck([item('a-1'), item('a-2'), item('a-3')])

    expect(topCard()).toContain('a-1')
    expect(screen.getByText('Exactly what will be sent')).toBeInTheDocument()
    expect(screen.getByText('jane@customer.example')).toBeInTheDocument()
    expect(screen.getByText('For: Welcome the new starter · asked by Priya Shah')).toBeInTheDocument()
    expect(screen.getByText('HR Agent')).toBeInTheDocument()
    // The deadline reads as a sentence: "Expires in 5 hours", never "Decide by in 5 hours".
    expect(screen.getByText(/^Expires/)).toBeInTheDocument()
    expect(screen.queryByText(/Decide by/)).not.toBeInTheDocument()
    // The same words as the list's cards, and the run a button like theirs.
    expect(screen.getByRole('button', { name: 'Reject and stop' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send back with feedback' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View the run' })).toHaveClass('button', 'button-outline')
    expect(screen.getByText('1 of 3')).toBeInTheDocument()
    expect(screen.queryByText(/a-2/)).not.toBeInTheDocument()
  })

  it('holds an approval for four seconds before sending it, then sends it once', () => {
    renderDeck([item('a-1'), item('a-2')])

    press('a')

    expect(topCard()).toContain('a-2')
    expect(screen.getByText(/^Approved:/, { selector: '.deck-undo-text' })).toBeInTheDocument()
    expect(onDecide).not.toHaveBeenCalled()
    wait(UNDO_WINDOW_MS - 1)
    expect(onDecide).not.toHaveBeenCalled()
    wait(1)
    expect(onDecide).toHaveBeenCalledTimes(1)
    expect(onDecide.mock.calls[0]![0]).toMatchObject({ kind: 'approve', approval: { id: 'a-1' } })
    expect(screen.queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument()
    wait(UNDO_WINDOW_MS * 2)
    expect(onDecide).toHaveBeenCalledTimes(1)
  })

  it('undo brings the card back and nothing is ever sent', () => {
    renderDeck([item('a-1'), item('a-2')])

    press('a')
    wait(2_000)
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }))

    expect(topCard()).toContain('a-1')
    wait(UNDO_WINDOW_MS * 3)
    expect(onDecide).not.toHaveBeenCalled()
    expect(screen.getByText('Undone. Nothing was sent.')).toBeInTheDocument()

    // U undoes too.
    press('a')
    press('u')
    wait(UNDO_WINDOW_MS)
    expect(onDecide).not.toHaveBeenCalled()
    expect(topCard()).toContain('a-1')
  })

  it('deciding the next card sends the held one at once', () => {
    renderDeck([item('a-1'), item('a-2'), item('a-3')])

    press('a')
    wait(1_000)
    press('a')

    expect(onDecide).toHaveBeenCalledTimes(1)
    expect(onDecide.mock.calls[0]![0].approval.id).toBe('a-1')
    wait(UNDO_WINDOW_MS)
    expect(onDecide).toHaveBeenCalledTimes(2)
    expect(onDecide.mock.calls[1]![0].approval.id).toBe('a-2')
  })

  it('a removal needs a confirmation tap, whether it was approved by key, button or swipe', () => {
    renderDeck([item('d-1', { actionClass: 'DESTRUCTIVE', tool: 'drive.delete_file' }), item('a-2')])

    press('a')
    expect(screen.getByRole('dialog', { name: 'Approve this removal?' })).toBeInTheDocument()
    expect(topCard()).toContain('d-1')
    fireEvent.click(screen.getByRole('button', { name: 'Go back' }))
    wait(UNDO_WINDOW_MS)
    expect(onDecide).not.toHaveBeenCalled()

    swipe(160, 0)
    expect(screen.getByRole('dialog', { name: 'Approve this removal?' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Approve removal' }))
    expect(topCard()).toContain('a-2')
    wait(UNDO_WINDOW_MS)
    expect(onDecide).toHaveBeenCalledTimes(1)
    expect(onDecide.mock.calls[0]![0]).toMatchObject({ kind: 'approve', approval: { id: 'd-1' } })
  })

  it('reject asks for an optional note in a sheet, and the note goes with it', () => {
    renderDeck([item('a-1'), item('a-2')])

    press('r')
    const sheet = screen.getByRole('dialog', { name: 'Reject this action?' })
    expect(sheet).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText(/Note/), { target: { value: '  Not this week.  ' } })
    fireEvent.click(within(sheet).getByRole('button', { name: 'Reject and stop' }))

    expect(screen.getByText(/^Rejected:/, { selector: '.deck-undo-text' })).toBeInTheDocument()
    wait(UNDO_WINDOW_MS)
    expect(onDecide.mock.calls[0]![0]).toMatchObject({ kind: 'reject', note: 'Not this week.', approval: { id: 'a-1' } })
  })

  it('sending back needs feedback before it goes', () => {
    renderDeck([item('a-1')])

    press('f')
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Send back' }))
    expect(screen.getByText('Say what should change, so the agent has something to revise.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('What should change'), { target: { value: 'Use a softer tone.' } })
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Send back' }))

    wait(UNDO_WINDOW_MS)
    expect(onDecide.mock.calls[0]![0]).toMatchObject({ kind: 'feedback', note: 'Use a softer tone.' })
  })

  it('a swipe commits only past the threshold, saying what letting go would do while it is dragged', () => {
    renderDeck([item('a-1'), item('a-2')])

    swipe(60, 0, false)
    expect(document.querySelector('.deck-swipe-label')).toHaveTextContent(/^Approve$/)
    fireEvent.pointerUp(cardTitle(), { pointerId: 7, clientX: 260, clientY: 300 })
    expect(topCard()).toContain('a-1')

    swipe(120, 0, false)
    expect(screen.getByText('Release to approve')).toBeInTheDocument()
    fireEvent.pointerUp(cardTitle(), { pointerId: 7, clientX: 320, clientY: 300 })
    expect(topCard()).toContain('a-2')
    wait(UNDO_WINDOW_MS)
    expect(onDecide.mock.calls[0]![0]).toMatchObject({ kind: 'approve', approval: { id: 'a-1' } })
  })

  it('a swipe left opens the reject sheet and a swipe up from the grab handle the send back sheet', () => {
    renderDeck([item('a-1')])

    swipe(-150, 10)
    expect(screen.getByRole('dialog', { name: 'Reject this action?' })).toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('button', { name: 'Go back' })[0]!)

    swipe(5, -130, false, 'handle')
    expect(screen.getByText('Release to send back')).toBeInTheDocument()
    fireEvent.pointerUp(document.querySelector('.deck-handle')!, { pointerId: 7, clientX: 205, clientY: 170 })
    expect(screen.getByRole('dialog', { name: 'Send this back with feedback?' })).toBeInTheDocument()

    expect(onDecide).not.toHaveBeenCalled()
  })

  it('an upward drag anywhere but the grab handle is the page scrolling: it never opens a sheet', () => {
    renderDeck([item('a-1')])

    // A finger moving up from the title, the eyebrow or the context is a scroll down the page.
    swipe(4, -200, false)
    expect(document.querySelector('.deck-swipe-label')).toBeNull()
    expect(cardTitle().closest('.deck-card')).not.toHaveAttribute('data-direction')
    fireEvent.pointerUp(cardTitle(), { pointerId: 7, clientX: 204, clientY: 100 })
    swipe(0, 250)

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(topCard()).toContain('a-1')
  })

  it('a request the person cannot decide comes last, read-only: keys do nothing and a swipe only moves past it', () => {
    renderDeck([
      item('r-1', { canDecide: false, requestedBy: ME }),
      item('a-1'),
      item('r-2', { canDecide: false }),
    ])

    expect(topCard()).toContain('a-1')
    press('ArrowRight')
    expect(topCard()).toContain('r-1')
    expect(screen.getByText('2 of 3')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()

    press('a')
    press('r')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByText('You cannot decide this request. Someone else needs to.')).toBeInTheDocument()

    swipe(150, 0)
    expect(topCard()).toContain('r-2')
    wait(UNDO_WINDOW_MS * 2)
    expect(onDecide).not.toHaveBeenCalled()
  })

  it('celebrates when nothing is waiting, and points to requests others must decide', () => {
    const { unmount } = renderDeck([])
    expect(screen.getByRole('heading', { name: 'Nothing is waiting for you' })).toBeInTheDocument()
    unmount()

    renderDeck([item('r-1', { canDecide: false })])
    expect(screen.getByRole('heading', { name: 'Nothing is waiting for you' })).toBeInTheDocument()
    expect(screen.getByText(/1 request is waiting for someone else to decide/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'See them in the list' }))
    expect(onShowList).toHaveBeenCalled()
  })

  it('with only requests others must decide, they can still be read here, one by one, read-only', () => {
    renderDeck([item('r-1', { canDecide: false }), item('r-2', { canDecide: false })])

    fireEvent.click(screen.getByRole('button', { name: 'Read them here' }))

    expect(topCard()).toContain('r-1')
    expect(screen.getByText(/These requests need someone else to decide/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    swipe(160, 0)
    expect(topCard()).toContain('r-2')
    wait(UNDO_WINDOW_MS)
    expect(onDecide).not.toHaveBeenCalled()
  })

  it('a link to a request the person can only read shows that request, even when nothing else is waiting', () => {
    renderDeck([item('r-1', { canDecide: false, requestedBy: ME }), item('r-2', { canDecide: false })], 'r-2')

    expect(topCard()).toContain('r-2')
    expect(document.getElementById('approval-r-2')).not.toBeNull()
    expect(screen.queryByRole('heading', { name: 'Nothing is waiting for you' })).not.toBeInTheDocument()
    expect(screen.getByText('You can read this request, but your role cannot decide it.')).toBeInTheDocument()
  })

  it('keeps Undo after the last card is decided, and sends when the window closes', () => {
    renderDeck([item('a-1')])

    press('a')
    expect(screen.getByRole('heading', { name: 'Nothing is waiting for you' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Undo' })).toBeInTheDocument()
    wait(UNDO_WINDOW_MS)
    expect(onDecide).toHaveBeenCalledTimes(1)
  })

  it('sends a held decision at once when the view goes away', () => {
    const { unmount } = renderDeck([item('a-1'), item('a-2')])

    press('a')
    unmount()

    expect(onDecide).toHaveBeenCalledTimes(1)
  })

  it('brings a card back on top when its send fails, and says it was not sent', async () => {
    onDecide = vi.fn(() => Promise.reject(new Error('already decided')))
    renderDeck([item('a-1'), item('a-2')])

    press('a')
    expect(topCard()).toContain('a-2')
    wait(UNDO_WINDOW_MS)
    await act(async () => {})

    // The request that still needs a decision is the one in front of the person, and the deck says why.
    expect(topCard()).toContain('a-1')
    expect(screen.getByText('1 of 2')).toBeInTheDocument()
    expect(announced()).toBe(`Not sent: ${cardTitle().textContent}. It is back in the deck.`)
    expect(document.activeElement).toBe(document.getElementById('approval-a-1'))
  })

  it('opens on the card a link names, and moves with the arrow keys and buttons', () => {
    renderDeck([item('a-1'), item('a-2'), item('a-3')], 'a-3')

    expect(topCard()).toContain('a-3')
    press('ArrowLeft')
    expect(topCard()).toContain('a-2')
    fireEvent.click(screen.getByRole('button', { name: 'Previous request' }))
    expect(topCard()).toContain('a-1')
    expect(screen.getByRole('button', { name: 'Previous request' })).toBeDisabled()
  })

  it('a held-down key decides one request, never a run of them', () => {
    renderDeck([item('a-1'), item('a-2'), item('a-3')])

    press('a')
    wait(NEW_CARD_GUARD_MS)
    press('a', { repeat: true })
    press('a', { repeat: true })

    expect(topCard()).toContain('a-2')
    wait(UNDO_WINDOW_MS)
    expect(onDecide).toHaveBeenCalledTimes(1)
  })

  it('keeps the card being read on top when a request arrives earlier in the queue', () => {
    const { rerender } = renderDeck([item('a-1'), item('a-2')])
    expect(topCard()).toContain('a-1')

    rerender(
      <DecideDeck
        approvals={[item('a-0'), item('a-1'), item('a-2')]}
        agentNames={AGENTS}
        agentsKnown
        me={ME}
        nameOf={(userId) => NAMES[userId] ?? 'Someone'}
        targetId={null}
        hasMore={false}
        loadingMore={false}
        onLoadMore={() => {}}
        onDecide={onDecide}
        onShowList={onShowList}
      />,
    )

    expect(topCard()).toContain('a-1')
    expect(screen.getByText('2 of 3')).toBeInTheDocument()
  })

  it('a double click on Approve decides one request, not the one that takes its place', () => {
    renderDeck([item('a-1'), item('a-2'), item('a-3')])

    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
    // The second click of the pair lands on the next card's Approve button, in the same place.
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))

    expect(topCard()).toContain('a-2')
    expect(screen.getByText(/^Approved:/, { selector: '.deck-undo-text' })).toHaveTextContent('a-1')
    wait(UNDO_WINDOW_MS)
    expect(onDecide).toHaveBeenCalledTimes(1)
    expect(onDecide.mock.calls[0]![0].approval.id).toBe('a-1')

    // Once the new card has been on screen a moment, it is decided as usual.
    wait(NEW_CARD_GUARD_MS)
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
    expect(topCard()).toContain('a-3')
  })

  it('keys act only while focus is inside the deck, and the arrows stay with a scrolling payload', () => {
    render(
      <>
        <button type="button">Somewhere else on the page</button>
        <DecideDeck
          approvals={[item('a-1'), item('a-2')]}
          agentNames={AGENTS}
          agentsKnown
          me={ME}
          nameOf={(userId) => NAMES[userId] ?? 'Someone'}
          targetId={null}
          hasMore={false}
          loadingMore={false}
          onLoadMore={() => {}}
          onDecide={onDecide}
          onShowList={onShowList}
        />
      </>,
    )
    const elsewhere = screen.getByRole('button', { name: 'Somewhere else on the page' })
    elsewhere.focus()

    act(() => {
      fireEvent.keyDown(elsewhere, { key: 'a' })
      fireEvent.keyDown(window, { key: 'a' })
      fireEvent.keyDown(document.body, { key: 'ArrowRight' })
    })
    wait(UNDO_WINDOW_MS)
    expect(onDecide).not.toHaveBeenCalled()
    expect(topCard()).toContain('a-1')

    // In the request's own payload the arrow keys scroll it; they do not change the card.
    const payload = document.querySelector<HTMLElement>('[data-no-swipe] .approval-payload')!
    payload.focus()
    expect(document.activeElement).toBe(payload)
    press('ArrowRight')
    expect(topCard()).toContain('a-1')

    // With the card focused, the keys work.
    document.getElementById('approval-a-1')!.focus()
    press('ArrowRight')
    expect(topCard()).toContain('a-2')
  })

  it('after the last request is decided, focus goes to the heading that says so, not to the page', () => {
    renderDeck([item('a-1')])
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))

    const heading = screen.getByRole('heading', { name: 'Nothing is waiting for you' })
    expect(document.activeElement).toBe(heading)
    // Undo still works from there.
    press('u')
    expect(topCard()).toContain('a-1')
  })

  it('says each decision aloud, naming the request, even when two in a row are alike', () => {
    renderDeck([item('a-1', { summary: 'Send the same note' }), item('a-2', { summary: 'Send the same note' }), item('a-3')])

    const status = document.querySelector('[role="status"]')!

    press('a')
    const first = status.textContent
    expect(announced()).toBe('Approved: Send the same note. It is sent in 4 seconds unless you undo it.')
    wait(NEW_CARD_GUARD_MS)
    press('a')

    // The same words again, but the region's text changes, so a screen reader speaks them again.
    expect(announced()).toBe('Approved: Send the same note. It is sent in 4 seconds unless you undo it.')
    expect(status.textContent).not.toBe(first)
  })

  it('tells the page a decision sent as the app is put away, so it can outlive the page', () => {
    const visibility = { state: 'visible' as DocumentVisibilityState }
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility.state })
    try {
      renderDeck([item('a-1'), item('a-2')])
      press('a')

      visibility.state = 'hidden'
      act(() => {
        document.dispatchEvent(new Event('visibilitychange'))
      })

      expect(onDecide).toHaveBeenCalledTimes(1)
      expect(onDecide.mock.calls[0]![0]).toMatchObject({ kind: 'approve', leaving: true, approval: { id: 'a-1' } })
      expect(screen.queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument()
    } finally {
      delete (document as unknown as { visibilityState?: unknown }).visibilityState
    }
  })

  it('leaves the keys alone while the person types', () => {
    renderDeck([item('a-1')])
    press('r')
    const note = screen.getByLabelText(/Note/)

    act(() => {
      fireEvent.keyDown(note, { key: 'a' })
    })

    expect(screen.getByRole('dialog', { name: 'Reject this action?' })).toBeInTheDocument()
    expect(onDecide).not.toHaveBeenCalled()
  })
})

describe('DecideDeck accessibility', () => {
  beforeEach(() => {
    vi.useRealTimers()
  })

  it('has no axe violations with a card and with nothing waiting', async () => {
    const { container, unmount } = renderDeck([item('a-1'), item('r-1', { canDecide: false })])
    expect((await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })).violations).toEqual([])
    unmount()

    const empty = renderDeck([])
    expect((await axe.run(empty.container, { rules: { 'color-contrast': { enabled: false } } })).violations).toEqual([])
  })
})
