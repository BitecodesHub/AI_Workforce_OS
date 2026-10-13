// @find: decide mode, swipe to decide, swipe approvals, approval deck, one card at a time, swipe right approve, swipe left reject, swipe up send back, grab handle, undo decision, keyboard A R F, double tap guard, Nothing is waiting for you, DecideDeck
// @what: The Decide view of the Approvals page: one request at a time, decided by a swipe, a big button or a key (while the deck has focus), with a short window to undo.
// @flow: Rendered by routes/Approvals.tsx (Waiting tab, Decide view); rules from lib/swipe.ts, the undo window from lib/decisionHold.ts; the page sends each decision through onDecide
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent, RefObject } from 'react'
import { Button, Card, ConfirmDialog, EmptyState, Eyebrow, Kbd, Tag, Textarea, Time } from '../ui'
import type { TagTone } from '../ui'
import { Collapsible } from '../ui/Collapsible'
import { PayloadPreview } from './PayloadPreview'
import { NOTE_MAX, approvalAnchor, contextLine, isDestructive, readableSummary } from '../../lib/approvals'
import type { ApprovalItem } from '../../lib/approvalQueries'
import { useDecisionHold } from '../../lib/decisionHold'
import type { DecisionKind, HeldDecision, SendHow } from '../../lib/decisionHold'
import { plural } from '../../lib/format'
import { actionClassLabel, categoryTone, toolLabel } from '../../lib/labels'
import type { Agent } from '../../lib/queries'
import { can } from '../../lib/session'
import { deckKeyAction, deckOrder, deckPosition, readSwipe, swipeLabel } from '../../lib/swipe'
import type { SwipeDirection, SwipeReading } from '../../lib/swipe'

/*
 * One request at a time, the way a phone wants it.
 *
 * The card shows what a person needs to decide: the agent, what it wants to do and exactly what
 * would be sent or removed, who asked and for what, and the deadline. Swipe right to approve,
 * left to reject (a sheet asks for an optional note), or drag the bar at the top of the card up
 * to send it back with feedback; or use the buttons, or - while the deck has focus - A, R and F,
 * with the arrow keys to move between requests. Whatever is decided leaves the deck at once and
 * is held for four seconds behind an Undo button before it is sent. Removing something is never
 * decided by a swipe alone: a confirmation tap follows.
 *
 * Requests the person cannot decide - their role cannot, or the workspace's four-eyes rule wants
 * someone else - come after the ones they can, read-only; a swipe on one only moves past it.
 */

const ACTION_TONE: Record<string, TagTone> = { OUTBOUND: 'warning', DESTRUCTIVE: 'danger', WRITE: 'blue' }

/** What a decision is called once made. */
const DONE: Record<DecisionKind, string> = { approve: 'Approved', reject: 'Rejected', feedback: 'Sent back' }

/**
 * How long a card that has just taken a decided card's place ignores decisions, in milliseconds:
 * long enough that a double tap or double click on Approve, whose second half lands on the next
 * card's Approve button, decides one request rather than two.
 */
export const NEW_CARD_GUARD_MS = 400

/** A decision as the page sends it; `leaving` when the page is being hidden or closed, so the request must outlive it. */
export type DeckDecision = { approval: ApprovalItem; kind: DecisionKind; note?: string; leaving?: boolean }

/** The sheet open over the deck: a reason to reject, feedback to send back, or the confirmation a removal needs. */
type SheetState = { kind: 'reject' | 'feedback' | 'confirm'; approval: ApprovalItem } | null

/** A drag in progress: how far it has moved, how wide the card it started on is, and whether it began on the grab handle. */
type Drag = { dx: number; dy: number; width: number; fromHandle: boolean }

/** Where the arrow keys belong to the request itself (its payload scrolls), not to the deck. */
const OWN_ARROWS = '[data-no-swipe]'

// @find: DecideDeck component
/**
 * The Decide view. `approvals` is the waiting queue as loaded (soonest to expire first). Each
 * decision goes through `onDecide` only once its undo window closes, or sooner when the next one is
 * made, the view goes away, or the page is hidden or closed (then with `leaving`); `onDecide`
 * reports a failure to the person itself and rejects, and the card then comes back on top of the
 * deck with a word to say so. `targetId` (from a /approvals#approval-<id> link) opens the deck on
 * that card, even when it is one the person can only read. The keys work while focus is inside
 * the deck, so typing elsewhere on the page never decides anything.
 */
export function DecideDeck({
  approvals,
  agentNames,
  agentsKnown,
  me,
  nameOf,
  targetId,
  hasMore,
  loadingMore,
  onLoadMore,
  onDecide,
  onShowList,
}: {
  approvals: readonly ApprovalItem[]
  agentNames: Record<string, Agent | undefined>
  agentsKnown: boolean
  me: string | null
  nameOf: (userId: string) => string
  targetId: string | null
  hasMore: boolean
  loadingMore: boolean
  onLoadMore: () => void
  onDecide: (decision: DeckDecision) => Promise<void>
  onShowList: () => void
}) {
  const [hidden, setHidden] = useState<ReadonlySet<string>>(() => new Set())
  const [cursor, setCursor] = useState<{ id: string | null; index: number }>({ id: targetId, index: 0 })
  const [sheet, setSheet] = useState<SheetState>(null)
  const [note, setNote] = useState('')
  const [noteError, setNoteError] = useState<string | null>(null)
  const [announcement, setAnnouncement] = useState('')
  // Chosen from the "Nothing is waiting for you" state: read the requests others must decide here.
  const [browsing, setBrowsing] = useState(false)
  const cardRef = useRef<HTMLDivElement>(null)
  const doneRef = useRef<HTMLDivElement>(null)
  const focusCard = useRef(false)
  const guardUntil = useRef(0)

  // The live region only speaks when its text changes, so a repeated message ("Approved: ..."
  // twice for two identical requests) alternates a trailing no-break space to be heard again.
  const say = useCallback((text: string) => {
    setAnnouncement((previous) => (previous === text ? `${text}\u00a0` : text))
  }, [])

  const setVisible = useCallback((id: string, visible: boolean) => {
    setHidden((current) => {
      const next = new Set(current)
      if (visible) next.delete(id)
      else next.add(id)
      return next
    })
  }, [])

  // A failed send has already been explained by the page; the card comes back on top of the deck,
  // so the request that still needs a decision is the one in front of the person.
  const send = useCallback(
    (decision: HeldDecision<ApprovalItem>, how: SendHow) => {
      onDecide({
        approval: decision.item,
        kind: decision.kind,
        ...(decision.note ? { note: decision.note } : {}),
        ...(how.leaving ? { leaving: true } : {}),
      }).catch(() => {
        setVisible(decision.item.id, true)
        setCursor((was) => ({ id: decision.item.id, index: was.index }))
        say(`Not sent: ${readableSummary(decision.item)}. It is back in the deck.`)
        focusCard.current = true
      })
    },
    [onDecide, setVisible, say],
  )
  const { held, hold } = useDecisionHold<ApprovalItem>(send)

  // When the undo window closes, the bar and its Undo button leave. Focus on that button fell to
  // the page, and the deck's keys then did nothing until the person clicked back in (a press of F
  // looked ignored, 13 Oct 2026). Focus left on the page as the bar goes moves to the card on top,
  // or to the heading that says nothing is left; focus anywhere else stays where it is.
  const wasHeld = useRef(false)
  useEffect(() => {
    const before = wasHeld.current
    wasHeld.current = Boolean(held)
    if (!before || held) return
    const active = document.activeElement
    if (active && active !== document.body) return
    if (cardRef.current) {
      cardRef.current.focus({ preventScroll: true })
      return
    }
    const heading = doneRef.current?.querySelector<HTMLElement>('.empty-title')
    if (heading) {
      heading.tabIndex = -1
      heading.focus({ preventScroll: true })
    }
  }, [held])

  // A link to another card (a notification tapped while this page is open) moves the deck there.
  // Adjusted while rendering, as React advises for state that follows a prop.
  const [linkedTo, setLinkedTo] = useState(targetId)
  if (targetId !== linkedTo) {
    setLinkedTo(targetId)
    if (targetId) setCursor({ id: targetId, index: cursor.index })
  }

  const deck = useMemo(() => deckOrder(approvals.filter((approval) => !hidden.has(approval.id))), [approvals, hidden])
  const position = deckPosition(
    deck.map((approval) => approval.id),
    cursor.id,
    cursor.index,
  )
  const current = position >= 0 ? deck[position] : undefined
  const decidable = deck.filter((approval) => approval.canDecide).length
  const forOthers = deck.length - decidable
  // With nothing this person can decide, the deck celebrates - unless they asked to read the
  // others' requests here, or a link names one of them, which is then shown read-only.
  const linkedHere = linkedTo !== null && deck.some((approval) => approval.id === linkedTo)
  const readingOthers = decidable === 0 && deck.length > 0 && (browsing || linkedHere)
  const caughtUp = !current || (decidable === 0 && !readingOthers)

  // The card on top stays on top when the queue refreshes under it: its id is remembered as soon
  // as it is shown, so a request that arrives earlier in the queue never swaps the card being read.
  // A linked card not loaded yet is waited for instead (the page loads more until it arrives).
  if (current && cursor.id !== current.id && (cursor.id === null || cursor.id !== linkedTo)) {
    setCursor({ id: current.id, index: position })
  }

  // After a decision, an undo or a move, focus follows: to the card now on top, or - when nothing
  // is left to decide - to the heading that says so, never back to the page itself.
  useEffect(() => {
    if (!focusCard.current) return
    focusCard.current = false
    if (cardRef.current) {
      cardRef.current.focus()
      return
    }
    const heading = doneRef.current?.querySelector<HTMLElement>('.empty-title')
    if (heading) {
      heading.tabIndex = -1
      heading.focus()
    }
  }, [current?.id, caughtUp])

  const moveTo = useCallback(
    (index: number) => {
      const target = deck[index]
      if (!target) return false
      setCursor({ id: target.id, index })
      focusCard.current = true
      return true
    },
    [deck],
  )

  const next = () => {
    if (!moveTo(position + 1)) say('This is the last request.')
  }
  const previous = () => {
    if (!moveTo(position - 1)) say('This is the first request.')
  }

  /** Takes the decision: the card leaves, the next one takes its place, and Undo is offered. */
  const commit = (kind: DecisionKind, approval: ApprovalItem, text?: string) => {
    setVisible(approval.id, false)
    setCursor({ id: null, index: position })
    hold.hold({ item: approval, kind, ...(text ? { note: text } : {}) })
    say(`${DONE[kind]}: ${readableSummary(approval)}. It is sent in 4 seconds unless you undo it.`)
    focusCard.current = true
    guardUntil.current = Date.now() + NEW_CARD_GUARD_MS
  }

  /**
   * Starts a decision from a swipe, a button or a key: approve at once (a removal asks first),
   * reject and send back by their sheets. Ignored for a moment after a card takes a decided one's
   * place, so one double tap cannot decide two requests.
   */
  const begin = (kind: DecisionKind, approval: ApprovalItem) => {
    if (Date.now() < guardUntil.current) return
    if (!approval.canDecide) {
      say('You cannot decide this request. Someone else needs to.')
      return
    }
    setNote('')
    setNoteError(null)
    if (kind === 'approve') {
      if (isDestructive(approval.actionClass)) setSheet({ kind: 'confirm', approval })
      else commit('approve', approval)
      return
    }
    setSheet({ kind, approval })
  }

  const undo = () => {
    const back = hold.undo()
    if (!back) return
    setVisible(back.item.id, true)
    setCursor({ id: back.item.id, index: position })
    say('Undone. Nothing was sent.')
    focusCard.current = true
    guardUntil.current = 0
  }

  const onSwipe = (direction: SwipeDirection, approval: ApprovalItem) => {
    // A request this person cannot decide is only ever moved past.
    if (!approval.canDecide) {
      next()
      return
    }
    begin(direction, approval)
  }

  const confirmSheet = () => {
    if (!sheet) return
    const text = note.trim()
    if (sheet.kind === 'feedback' && !text) {
      setNoteError('Say what should change, so the agent has something to revise.')
      return
    }
    const { kind, approval } = sheet
    setSheet(null)
    commit(kind === 'confirm' ? 'approve' : kind, approval, kind === 'confirm' ? undefined : text)
  }

  const readOthersHere = () => {
    setBrowsing(true)
    const first = deck[0]
    if (first) setCursor({ id: first.id, index: 0 })
    focusCard.current = true
  }

  /*
   * Keys: A, R, F, the arrows and U - only while focus is inside the deck, never while a sheet is
   * open or the person is typing, and the arrows are left to a request's own scrolling payload.
   */
  const onKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    // A held-down key repeats; one press must never decide a run of requests.
    if (event.repeat || event.defaultPrevented || sheet || document.querySelector('dialog[open]')) return
    const target = event.target
    if (target instanceof HTMLElement && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName))) {
      return
    }
    const action = deckKeyAction(event)
    if (!action) return
    if ((action === 'next' || action === 'previous') && target instanceof Element && target.closest(OWN_ARROWS)) return
    if (action === 'undo') {
      if (!held) return
      event.preventDefault()
      undo()
      return
    }
    if (!current || caughtUp) return
    event.preventDefault()
    if (action === 'next') next()
    else if (action === 'previous') previous()
    else begin(action, current)
  }

  const undoBar = held && (
    <div className="deck-undo">
      <span className="deck-undo-text">
        {DONE[held.kind]}: {readableSummary(held.item)}
      </span>
      <button type="button" className="deck-undo-button" onClick={undo}>
        Undo
      </button>
      {/* Restarts with each decision: the bar empties over the four seconds Undo is offered. */}
      <span key={held.item.id + held.kind} className="deck-undo-timer" aria-hidden="true" />
    </div>
  )

  const live = (
    <p className="visually-hidden" role="status" aria-live="polite">
      {announcement}
    </p>
  )

  return (
    <div className="decide-deck" onKeyDown={onKeyDown}>
      {live}

      {caughtUp ? (
        <>
          {undoBar}
          <div ref={doneRef}>
            <Card>
              <EmptyState
                icon={<DoneIcon />}
                title="Nothing is waiting for you"
                body={
                  forOthers > 0
                    ? `You are all caught up. ${plural(forOthers, 'request is', 'requests are')} waiting for someone else to decide.`
                    : 'You are all caught up. New requests appear here, and on this device too if push notifications are on.'
                }
                action={
                  hasMore ? (
                    <Button variant="outline" onClick={onLoadMore} loading={loadingMore}>
                      Load more requests
                    </Button>
                  ) : forOthers > 0 ? (
                    <div className="row deck-empty-actions">
                      <Button variant="outline" onClick={readOthersHere}>
                        Read them here
                      </Button>
                      <Button variant="quiet" onClick={onShowList}>
                        See them in the list
                      </Button>
                    </div>
                  ) : undefined
                }
              />
            </Card>
          </div>
        </>
      ) : (
        <>
          {readingOthers && (
            <p className="caption muted deck-others-note">
              Nothing is waiting for you. These requests need someone else to decide; you can read them here.
            </p>
          )}
          <div className="deck-progress">
            <Button variant="quiet" onClick={previous} disabled={position === 0} aria-label="Previous request">
              Previous
            </Button>
            <span className="caption muted">
              {position + 1} of {deck.length}
              {hasMore ? ' loaded' : ''}
            </span>
            <Button variant="quiet" onClick={next} disabled={position >= deck.length - 1} aria-label="Next request">
              Next
            </Button>
          </div>

          {undoBar}

          <div className="deck-stack" data-depth={Math.min(deck.length - position - 1, 2)}>
            <DeckCard
              key={current.id}
              approval={current}
              agent={agentNames[current.agentId]}
              agentsKnown={agentsKnown}
              me={me}
              nameOf={nameOf}
              cardRef={cardRef}
              onSwipe={(direction) => onSwipe(direction, current)}
              onDecide={(kind) => begin(kind, current)}
              onSkip={next}
              isLast={position >= deck.length - 1}
            />
          </div>
        </>
      )}

      {/* The sheets hold the request they were opened for, so one stays right if the queue moves under it. */}
      <ConfirmDialog
        open={sheet?.kind === 'reject'}
        onClose={() => setSheet(null)}
        onConfirm={confirmSheet}
        eyebrow="Reject request"
        title="Reject this action?"
        description="The agent does not take this action, and its run stops. You can undo for a few seconds after."
        confirmLabel="Reject and stop"
        cancelLabel="Go back"
        tone="danger"
      >
        <Textarea
          label="Note"
          optional
          value={note}
          onChange={(event) => setNote(event.target.value.slice(0, NOTE_MAX))}
          placeholder="Why is this being rejected?"
          maxLength={NOTE_MAX}
          rows={3}
          hint="Shown to the person who asked and recorded in the audit log. Up to 1,000 characters."
        />
      </ConfirmDialog>

      <ConfirmDialog
        open={sheet?.kind === 'feedback'}
        onClose={() => setSheet(null)}
        onConfirm={confirmSheet}
        eyebrow="Send back"
        title="Send this back with feedback?"
        description="The agent does not take this action as written. It is told what you want changed, may revise it, and asks again."
        confirmLabel="Send back"
        cancelLabel="Go back"
        tone="primary"
      >
        <Textarea
          label="What should change"
          value={note}
          onChange={(event) => {
            setNote(event.target.value.slice(0, NOTE_MAX))
            setNoteError(null)
          }}
          error={noteError ?? undefined}
          placeholder="For example: use a softer tone, and leave out the discount."
          maxLength={NOTE_MAX}
          rows={3}
          data-autofocus
          hint="The agent reads this, and it is recorded in the audit log. Up to 1,000 characters."
        />
      </ConfirmDialog>

      <ConfirmDialog
        open={sheet?.kind === 'confirm'}
        onClose={() => setSheet(null)}
        onConfirm={confirmSheet}
        eyebrow="This removes something"
        title="Approve this removal?"
        description="The agent removes exactly what the request shows, and that cannot be undone once it is sent."
        confirmLabel="Approve removal"
        cancelLabel="Go back"
        tone="danger"
      />
    </div>
  )
}

/* ---- One card ------------------------------------------------------------------------------------- */

/** Marks the grab handle: the one place an upward drag sends a request back rather than scrolling. */
const SWIPE_UP_HANDLE = '[data-swipe-up]'

/** Elements a drag must not start on: they have their own job for a tap or a drag. */
const NO_DRAG = 'button, a, input, textarea, select, summary, [role="button"]'

/** Where a mouse drag selects text instead (the request itself); a finger may swipe from anywhere. */
const MOUSE_SELECTS = '[data-no-swipe]'

/** What a reading paints on the card while it is dragged. */
function dragStyle(drag: Drag | null): CSSProperties | undefined {
  if (!drag) return undefined
  const up = drag.fromHandle ? Math.min(drag.dy, 0) : 0
  return { transform: `translate(${drag.dx}px, ${up}px) rotate(${drag.dx / 28}deg)` }
}

/**
 * The card on top of the deck: the request, read in full, that follows a finger or a mouse while
 * it is dragged and says what letting go would do. A drag only counts past the threshold
 * (lib/swipe.ts), and an upward one only from the grab handle at the top; anywhere else, up and
 * down scroll the page. The buttons do what the swipes do.
 */
function DeckCard({
  approval,
  agent,
  agentsKnown,
  me,
  nameOf,
  cardRef,
  onSwipe,
  onDecide,
  onSkip,
  isLast,
}: {
  approval: ApprovalItem
  agent: Agent | undefined
  agentsKnown: boolean
  me: string | null
  nameOf: (userId: string) => string
  cardRef: RefObject<HTMLDivElement | null>
  onSwipe: (direction: SwipeDirection) => void
  onDecide: (kind: DecisionKind) => void
  onSkip: () => void
  isLast: boolean
}) {
  const [drag, setDrag] = useState<Drag | null>(null)
  const [askedOpen, setAskedOpen] = useState(false)
  const start = useRef<{ x: number; y: number; pointer: number; width: number; fromHandle: boolean } | null>(null)
  const actionClass = approval.actionClass.toUpperCase()
  const reading: SwipeReading | null = drag ? readSwipe(drag.dx, drag.dy, drag.width, drag.fromHandle) : null
  const needsSomeoneElse = !approval.canDecide && can('approval:decide') && me != null && approval.requestedBy === me

  const onPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.pointerType === 'mouse' && event.button !== 0) return
    if (event.target instanceof Element) {
      if (event.target.closest(NO_DRAG)) return
      if (event.pointerType === 'mouse' && event.target.closest(MOUSE_SELECTS)) return
    }
    start.current = {
      x: event.clientX,
      y: event.clientY,
      pointer: event.pointerId,
      width: event.currentTarget.getBoundingClientRect().width,
      fromHandle: event.target instanceof Element && event.target.closest(SWIPE_UP_HANDLE) !== null,
    }
    event.currentTarget.setPointerCapture?.(event.pointerId)
  }

  const onPointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const from = start.current
    if (!from || event.pointerId !== from.pointer) return
    setDrag({ dx: event.clientX - from.x, dy: event.clientY - from.y, width: from.width, fromHandle: from.fromHandle })
  }

  const onPointerUp = (event: ReactPointerEvent<HTMLDivElement>) => {
    const from = start.current
    start.current = null
    if (!from || event.pointerId !== from.pointer) return
    const final = readSwipe(event.clientX - from.x, event.clientY - from.y, from.width, from.fromHandle)
    setDrag(null)
    if (final.armed && final.direction) onSwipe(final.direction)
  }

  const cancel = () => {
    start.current = null
    setDrag(null)
  }

  return (
    <div
      id={approvalAnchor(approval.id)}
      ref={cardRef}
      tabIndex={-1}
      className="deck-card"
      role="group"
      aria-label={`${readableSummary(approval)}. ${approval.canDecide ? 'Waiting for your decision.' : 'You cannot decide this one.'}`}
      data-direction={reading?.direction ?? undefined}
      data-armed={reading?.armed ? 'true' : undefined}
      data-dragging={drag ? 'true' : undefined}
      style={dragStyle(drag)}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={cancel}
      onLostPointerCapture={() => {
        if (start.current) cancel()
      }}
    >
      {reading?.direction && (
        <div
          className="deck-swipe-label"
          data-direction={approval.canDecide ? reading.direction : 'skip'}
          data-armed={reading.armed ? 'true' : undefined}
          style={{ opacity: Math.max(0.35, reading.progress) }}
          aria-hidden="true"
        >
          {swipeLabel(reading.direction, approval.canDecide, reading.armed)}
        </div>
      )}

      <Card as="article">
        {approval.canDecide && (
          <div className="deck-handle" data-swipe-up aria-hidden="true">
            <span className="deck-handle-bar" />
          </div>
        )}
        <div className="deck-grab">
          <Eyebrow>{toolLabel(approval.tool)}</Eyebrow>
          <div className="row deck-title-row">
            <h2 className="section-heading" style={{ overflowWrap: 'anywhere' }}>
              {readableSummary(approval)}
            </h2>
            <Tag tone={ACTION_TONE[actionClass] ?? 'neutral'}>{actionClassLabel(approval.actionClass)}</Tag>
          </div>
          <div className="row deck-meta">
            {(agent || agentsKnown) && (
              <Tag tone={categoryTone(agent?.category)} withDot>
                {agent?.name ?? 'Unknown agent'}
              </Tag>
            )}
            <span className="caption">
              Expires <Time iso={approval.expiresAt} />
            </span>
          </div>
          <p className="deck-context">{contextLine(approval, me, nameOf)}</p>
        </div>

        {approval.taskInstruction && (
          <div style={{ marginBottom: 'var(--space-4)' }}>
            <Collapsible title="What the agent was asked to do" open={askedOpen} onToggle={setAskedOpen} headingLevel="p">
              <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', margin: 'var(--space-2) 0 0' }}>
                {approval.taskInstruction}
              </p>
            </Collapsible>
          </div>
        )}

        <div className="deck-payload" data-no-swipe>
          <PayloadPreview payload={approval.payload} actionClass={approval.actionClass} />
        </div>

        {approval.canDecide ? (
          <>
            <div className="deck-actions">
              <Button variant="outline" className="deck-action deck-action-reject" onClick={() => onDecide('reject')}>
                Reject and stop
              </Button>
              <Button variant="outline" className="deck-action" onClick={() => onDecide('feedback')}>
                Send back with feedback
              </Button>
              <Button className="deck-action deck-action-approve" onClick={() => onDecide('approve')}>
                Approve
              </Button>
            </div>
            <p className="caption muted deck-hint deck-hint-touch">
              Swipe right to approve or left to reject. Drag the bar at the top of the card up to send it back with
              feedback.
            </p>
            <p className="caption muted deck-hint deck-hint-keys">
              While the request has focus: <Kbd>A</Kbd> approve, <Kbd>R</Kbd> reject, <Kbd>F</Kbd> send back,{' '}
              <Kbd>U</Kbd> undo, and the left and right arrow keys to move.
            </p>
          </>
        ) : (
          <div className="deck-readonly">
            <p className="caption">
              {needsSomeoneElse
                ? 'You asked for this work, so someone else needs to decide it.'
                : 'You can read this request, but your role cannot decide it.'}
            </p>
            <Button variant="outline" onClick={onSkip} disabled={isLast}>
              Skip
            </Button>
          </div>
        )}
        <a className="button button-outline deck-run-link" href={`/runs/${approval.runId}`}>
          View the run
        </a>
      </Card>
    </div>
  )
}

/** A tick in a circle: everything that was waiting has been seen to. */
function DoneIcon() {
  return (
    <svg width="28" height="28" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="8.5" stroke="currentColor" strokeWidth="1.6" />
      <path d="M8.5 12.2l2.4 2.4 4.6-5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}
