// @find: queued message, message queue, waiting message, starts after current answer, send again, cancel queued, queued status
// @what: Shows messages waiting in the conversation queue and their status.
// @flow: Used by the Chat page.
import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Button, ConfirmDialog } from '../ui'
import type { ConversationBusy, QueuedMessage } from '../../lib/queries'

export const QUEUED_TEXT = 'Queued — will start when the current answer finishes'
export const WAITING_DECISION_TEXT = 'Waiting for your decision above — this will start after it'
export const STARTING_TEXT = 'Starting…'
export const EXPIRED_TEXT = 'Expired — send again'

export type QueuedActions = {
  /** Removes the queued message. Resolves true when it is gone. */
  onCancel: (item: QueuedMessage) => Promise<boolean>
  /** Saves new words for the queued message. Resolves true when saved. */
  onEdit: (item: QueuedMessage, text: string) => Promise<boolean>
  /** Stops the work waiting on a decision and starts this message. Resolves true when started. */
  onStartNow: (item: QueuedMessage) => Promise<boolean>
  /** Puts an expired message's words back in the message box and removes it from the queue. */
  onSendAgain: (item: QueuedMessage) => void
}

// @find: queuedStatusText, queued status text, queued message, message queue, waiting message, starts after current answer
/** The status sentence a queued message shows, from its own status and the conversation's. */
export function queuedStatusText(item: Pick<QueuedMessage, 'status'>, busy: ConversationBusy): string {
  if (item.status === 'starting') return STARTING_TEXT
  if (item.status === 'expired') return EXPIRED_TEXT
  return busy === 'waiting_decision' ? WAITING_DECISION_TEXT : QUEUED_TEXT
}

// @find: QueuedMessages, queued messages, queued message, message queue, waiting message, starts after current answer
/**
 * Every message waiting in this conversation's queue, oldest first, after the last message in the
 * thread. Each is drawn as a queued bubble, never as an ordinary sent message.
 */
export function QueuedMessages({ items, busy, ...actions }: { items: readonly QueuedMessage[]; busy: ConversationBusy } & QueuedActions) {
  if (items.length === 0) return null
  const ordered = [...items].sort((a, b) => a.order - b.order)
  return (
    <ol className="chat-queue" aria-label={ordered.length === 1 ? 'Queued message' : `${ordered.length} queued messages`}>
      {ordered.map((item, index) => (
        <li key={item.id}>
          <QueuedBubble item={item} busy={busy} position={index + 1} total={ordered.length} {...actions} />
        </li>
      ))}
    </ol>
  )
}

// @find: QueuedBubble, queued bubble, queued message, message queue, waiting message, starts after current answer
export function QueuedBubble({
  item,
  busy,
  position = 1,
  total = 1,
  onCancel,
  onEdit,
  onStartNow,
  onSendAgain,
}: {
  item: QueuedMessage
  busy: ConversationBusy
  position?: number
  total?: number
} & QueuedActions) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(item.text)
  const [saving, setSaving] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [starting, setStarting] = useState(false)
  const editRef = useRef<HTMLTextAreaElement | null>(null)
  const editButtonRef = useRef<HTMLButtonElement | null>(null)
  const returnFocus = useRef(false)

  const statusText = queuedStatusText(item, busy)
  const label = total > 1 ? `Queued message ${position} of ${total}` : 'Queued message'
  const manageable = item.canManage && item.status !== 'starting'
  const expired = item.status === 'expired'
  const waitingDecision = item.status === 'queued' && busy === 'waiting_decision'
  // A message that starts while it is being changed can no longer be changed.
  const canEdit = manageable && item.status === 'queued'
  if (editing && !canEdit) setEditing(false)

  useEffect(() => {
    if (editing) {
      const el = editRef.current
      if (el) {
        el.focus()
        el.selectionStart = el.selectionEnd = el.value.length
        // The editor is taller than the bubble it replaces, and the thread does not follow it
        // down by itself: without this its Save button opened below the message box.
        el.closest('.chat-queued')?.scrollIntoView?.({ block: 'nearest' })
      }
    } else if (returnFocus.current) {
      returnFocus.current = false
      editButtonRef.current?.focus()
    }
  }, [editing])

  function openEditor() {
    setDraft(item.text)
    setEditing(true)
  }

  function closeEditor() {
    returnFocus.current = true
    setEditing(false)
  }

  async function save() {
    const text = draft.trim()
    if (!text || saving) return
    if (text === item.text.trim()) {
      closeEditor()
      return
    }
    setSaving(true)
    const ok = await onEdit(item, text)
    setSaving(false)
    if (ok) closeEditor()
  }

  function onEditorKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      closeEditor()
    } else if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
      event.preventDefault()
      void save()
    }
  }

  async function cancel() {
    setCancelling(true)
    const ok = await onCancel(item)
    if (!ok) setCancelling(false)
  }

  const editorId = `queued-edit-${item.id}`
  const files = item.attachmentCount === 1 ? '1 file attached' : item.attachmentCount > 1 ? `${item.attachmentCount} files attached` : null

  return (
    <div className="chat-bubble-row chat-bubble-row-user" data-queued-status={item.status}>
      <article className="stack chat-queued" aria-label={label} style={{ gap: 'var(--space-2)', alignItems: 'flex-end' }}>
        {editing ? (
          <div className="chat-bubble chat-bubble-queued stack" style={{ gap: 'var(--space-2)', width: '100%' }}>
            <label htmlFor={editorId} className="caption muted">
              Change your queued message
            </label>
            <textarea
              id={editorId}
              ref={editRef}
              className="input textarea chat-queued-editor"
              rows={3}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={onEditorKeyDown}
            />
            <div className="row" style={{ gap: 'var(--space-2)', justifyContent: 'flex-end' }}>
              <Button variant="quiet" onClick={closeEditor} disabled={saving}>
                Cancel
              </Button>
              <Button onClick={() => void save()} loading={saving} disabled={draft.trim().length === 0}>
                Save
              </Button>
            </div>
          </div>
        ) : (
          <div className="chat-bubble chat-bubble-queued">
            <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', margin: 0 }}>{item.text}</p>
            {files && <p className="caption muted" style={{ margin: 'var(--space-1) 0 0' }}>{files}</p>}
          </div>
        )}
        <p className="caption muted chat-queued-status" style={{ margin: 0 }}>
          {statusText}
        </p>
        {!editing && manageable && (
          <div className="row chat-actions chat-queued-actions" style={{ gap: 'var(--space-3)' }}>
            {expired ? (
              <>
                <button type="button" className="link caption" onClick={() => onSendAgain(item)}>
                  Send again
                </button>
                <button type="button" className="link caption" onClick={() => void cancel()} disabled={cancelling}>
                  Remove
                </button>
              </>
            ) : (
              <>
                {waitingDecision && (
                  <button type="button" className="link caption" onClick={() => setConfirmOpen(true)}>
                    Start now anyway
                  </button>
                )}
                {canEdit && (
                  <button type="button" className="link caption" ref={editButtonRef} onClick={openEditor}>
                    Edit
                  </button>
                )}
                <button type="button" className="link caption" onClick={() => void cancel()} disabled={cancelling} aria-label={`Cancel ${label.toLowerCase()}`}>
                  Cancel
                </button>
              </>
            )}
          </div>
        )}
      </article>
      <ConfirmDialog
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={async () => {
          setStarting(true)
          const ok = await onStartNow(item)
          setStarting(false)
          if (ok) setConfirmOpen(false)
        }}
        eyebrow="Chat"
        title="Start this message now?"
        description="The work above is waiting for your decision. Starting this message now stops that work, and it will not finish. Your message then starts straight away."
        confirmLabel="Stop it and start this"
        cancelLabel="Keep waiting"
        tone="danger"
        loading={starting}
      />
    </div>
  )
}
