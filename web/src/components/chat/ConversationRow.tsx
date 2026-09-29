import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { useEffect, useRef, useState } from 'react'
import { formatRelativeTicked } from '../../lib/format'
import type { Conversation } from '../../lib/queries'
import { MenuButton } from '../ui/Menu'
import { conversationStatusText, highlightParts } from './conversationGroups'

/*
 * One row of the conversation sidebar (B1.3): the title (renamable inline), an unread dot, a
 * status caption, and a row menu. The row is a real `<a>` so it can be opened in a new tab; a plain
 * click is intercepted and handled by `onSelect`, and Shift+F10 or the ContextMenu key opens the
 * row's own actions menu without leaving the keyboard.
 */

function DotsIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <circle cx="3.5" cy="8" r="1.4" fill="currentColor" />
      <circle cx="8" cy="8" r="1.4" fill="currentColor" />
      <circle cx="12.5" cy="8" r="1.4" fill="currentColor" />
    </svg>
  )
}

function Highlighted({ text, query, className }: { text: string; query: string; className?: string }) {
  const parts = highlightParts(text, query)
  return (
    <span className={className}>
      {parts.map((part, index) =>
        part.match ? (
          <mark key={index} className="chat-row-match">
            {part.text}
          </mark>
        ) : (
          <span key={index}>{part.text}</span>
        ),
      )}
    </span>
  )
}

export function ConversationRow({
  conversation,
  current,
  tabbable,
  now,
  highlight,
  onSelect,
  onRename,
  onTogglePin,
  onToggleArchive,
  onCopyLink,
  onDelete,
  rowRef,
  onNavigate,
}: {
  conversation: Conversation
  current: boolean
  tabbable: boolean
  now: number
  highlight: string
  onSelect: () => void
  onRename: (title: string) => Promise<boolean>
  onTogglePin: () => void
  onToggleArchive: () => void
  onCopyLink: () => void
  onDelete: () => void
  rowRef: (el: HTMLAnchorElement | null) => void
  /** The roving-focus list's own key handler (useRovingList), wired directly to this row's anchor. */
  onNavigate?: (event: ReactKeyboardEvent) => void
}) {
  const [editing, setEditing] = useState(false)
  const [busy, setBusy] = useState(false)
  const inputRef = useRef<HTMLInputElement | null>(null)
  const menuOpenRef = useRef<(() => void) | null>(null)
  const title = conversation.title || 'Untitled conversation'

  useEffect(() => {
    if (editing) inputRef.current?.focus()
  }, [editing])

  async function commitRename(value: string) {
    const trimmed = value.trim()
    if (!trimmed || trimmed === conversation.title) {
      setEditing(false)
      return
    }
    setBusy(true)
    await onRename(trimmed)
    setBusy(false)
    setEditing(false)
  }

  return (
    <li className="chat-row-item">
      <div className="chat-row" data-current={current || undefined}>
        {editing ? (
          <input
            ref={inputRef}
            className="input chat-row-rename"
            aria-label="Conversation title"
            defaultValue={conversation.title}
            disabled={busy}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                void commitRename(event.currentTarget.value)
              } else if (event.key === 'Escape') {
                event.preventDefault()
                setEditing(false)
              }
            }}
            onBlur={(event) => void commitRename(event.currentTarget.value)}
          />
        ) : (
          <a
            ref={rowRef}
            href={`/chat?c=${conversation.id}`}
            className="chat-row-link"
            aria-current={current ? 'page' : undefined}
            tabIndex={tabbable ? 0 : -1}
            onKeyDown={(event) => {
              if ((event.key === 'F10' && event.shiftKey) || event.key === 'ContextMenu') {
                event.preventDefault()
                menuOpenRef.current?.()
                return
              }
              onNavigate?.(event)
            }}
            onClick={(event) => {
              if (event.defaultPrevented || event.button !== 0) return
              if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
              event.preventDefault()
              onSelect()
            }}
          >
            <span className="chat-row-main">
              {conversation.unread && <span className="chat-row-unread" aria-hidden="true" />}
              <Highlighted text={title} query={highlight} className="chat-row-title" />
              {conversation.unread && <span className="visually-hidden">New reply</span>}
            </span>
            <span className="chat-row-meta caption muted">
              <span className="chat-row-dot" data-activity={conversation.activity} aria-hidden="true" />
              {conversation.match ? (
                <Highlighted text={conversation.match.snippet} query={highlight} className="chat-row-snippet" />
              ) : (
                <span>{conversationStatusText(conversation.activity)}</span>
              )}
              <span> · {formatRelativeTicked(conversation.updatedAt, now, 60_000)}</span>
            </span>
          </a>
        )}
        {!editing && (
          <MenuButton
            className="chat-row-menu"
            trigger="icon"
            icon={<DotsIcon />}
            label={`Actions for ${title}`}
            triggerTabIndex={-1}
            openRef={menuOpenRef}
            align="end"
            items={[
              ...(conversation.canManage
                ? [{ id: 'rename', label: 'Rename', onSelect: () => setEditing(true) }]
                : []),
              { id: 'pin', label: conversation.pinned ? 'Unpin' : 'Pin', onSelect: onTogglePin },
              { id: 'archive', label: conversation.archived ? 'Unarchive' : 'Archive', onSelect: onToggleArchive },
              { id: 'copy', label: 'Copy link', onSelect: onCopyLink },
              ...(conversation.canManage
                ? [{ id: 'delete', label: 'Delete', danger: true, onSelect: onDelete }]
                : []),
            ]}
          />
        )}
      </div>
    </li>
  )
}
