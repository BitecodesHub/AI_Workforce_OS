// @find: conversation row, sidebar item, rename inline, pin conversation, archive, delete, open conversation, conversation title, status text
// @what: One row of the conversation sidebar with a renamable title and actions.
// @flow: Used by ChatSidebar.
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { useEffect, useRef, useState } from 'react'
import { formatDateTime, formatShortTime } from '../../lib/format'
import type { Conversation } from '../../lib/queries'
import type { MenuEntry } from '../ui/Menu'
import { MenuButton } from '../ui/Menu'
import { highlightParts } from './conversationGroups'

/*
 * One row of the conversation sidebar (B1.3), kept to two things: the title (renamable inline),
 * single-line, with a compact time on the right ('26m', 'Yesterday', '3 Oct'). A conversation
 * waiting on the viewer adds one small accent dot before the title, labelled for screen readers;
 * nothing else - no preview, no status line, no lock. The row menu (a floating, portalled panel)
 * shows on hover, focus or for the open row, and is always reachable by keyboard: Tab from the
 * row, or Shift+F10 / the ContextMenu key on it.
 *
 * The row is a real `<a>` so it can be opened in a new tab; a plain click is intercepted and
 * handled by `onSelect`. A search hit links to the matching message itself (`#m-<id>`), and hands
 * that message's id to `onSelect` so the thread can scroll to it.
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

// @find: ConversationRow, conversation row, conversation row, sidebar item, rename inline, pin conversation
export function ConversationRow({
  conversation,
  current,
  needsYou = false,
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
  /** Shown under "Needs you": the row gets the accent dot even if its own activity has moved on. */
  needsYou?: boolean
  tabbable: boolean
  now: number
  highlight: string
  /** Called with the matching message's id when the row is a search hit. */
  onSelect: (messageId?: string) => void
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
  const waitingOnViewer =
    needsYou || conversation.activity === 'needs_answer' || conversation.activity === 'needs_approval'
  const waitingLabel = conversation.activity === 'needs_approval' ? 'Needs your approval' : 'Needs your answer'
  const matchedMessageId = conversation.match?.messageId
  const href = `/chat?c=${conversation.id}${matchedMessageId ? `#m-${matchedMessageId}` : ''}`

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

  const menuItems: MenuEntry[] = [
    ...(conversation.canManage ? [{ id: 'rename', label: 'Rename', onSelect: () => setEditing(true) }] : []),
    { id: 'pin', label: conversation.pinned ? 'Unpin' : 'Pin', onSelect: onTogglePin },
    { id: 'archive', label: conversation.archived ? 'Unarchive' : 'Archive', onSelect: onToggleArchive },
    { id: 'copy', label: 'Copy link', onSelect: onCopyLink },
    ...(conversation.canManage
      ? [
          { id: 'delete-separator', separator: true as const },
          { id: 'delete', label: 'Delete', danger: true, onSelect: onDelete },
        ]
      : []),
  ]

  return (
    <li className="chat-row-item">
      <div className="chat-row" data-current={current || undefined} data-editing={editing || undefined}>
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
            href={href}
            className="chat-row-link"
            data-unread={conversation.unread || undefined}
            title={conversation.match ? conversation.match.snippet : undefined}
            aria-keyshortcuts="Shift+F10"
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
              onSelect(matchedMessageId)
            }}
          >
            {waitingOnViewer && (
              <span className="chat-row-needs" role="img" aria-label={waitingLabel} title={waitingLabel} />
            )}
            <Highlighted text={title} query={highlight} className="chat-row-title" />
            {conversation.unread && <span className="visually-hidden">, new reply</span>}
            <time className="chat-row-time" dateTime={conversation.updatedAt} title={formatDateTime(conversation.updatedAt)}>
              {formatShortTime(conversation.updatedAt, now)}
            </time>
          </a>
        )}
        {!editing && (
          <MenuButton
            className="chat-row-menu"
            trigger="icon"
            icon={<DotsIcon />}
            label={`Actions for ${title}`}
            triggerTabIndex={tabbable ? 0 : -1}
            openRef={menuOpenRef}
            align="end"
            portal
            items={menuItems}
          />
        )}
      </div>
    </li>
  )
}
