// @find: thread header, conversation title, rename conversation, who is in conversation, private pill, add people, details mode, chat top bar
// @what: Top band of the chat panel: title, members, privacy and details control.
// @flow: Used by the Chat page; opens AddPeopleDialog.
import { useEffect, useRef, useState } from 'react'
import { Eyebrow, IconButton, Tag } from '../ui'
import { MenuButton } from '../ui/Menu'
import type { Agent, Conversation } from '../../lib/queries'
import type { useSpeaker } from '../../lib/voice'
import type { DetailsMode } from './detailsContext'
import { AgentAvatar } from '../ui/AgentAvatar'
import { visibilityLabel } from '../../lib/chatQueries'

/*
 * The one-row band at the top of the chat panel (B1.4): the conversation's title (renamable), who is in it, a
 * compact notice for a questions-only role, and the menu that holds everything else a conversation
 * can do.
 *
 * Beside the title, a small pill says who can read the conversation ("Private": only its creator
 * and the people added; "Workspace": everyone in it), with the full sentence for screen readers
 * and as its tooltip. The creator can change that from the menu. A conversation
 * that could not be opened (`unavailable`) offers none of the actions that would act on it.
 */

function MenuIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <circle cx="3.5" cy="8" r="1.4" fill="currentColor" />
      <circle cx="8" cy="8" r="1.4" fill="currentColor" />
      <circle cx="12.5" cy="8" r="1.4" fill="currentColor" />
    </svg>
  )
}

function ConversationsIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path d="M2 4h12M2 8h12M2 12h8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  )
}

function NewIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  )
}

function PanelIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <rect x="2" y="3" width="12" height="10" rx="2" stroke="currentColor" strokeWidth="1.3" />
      <path d="M10.5 3v10" stroke="currentColor" strokeWidth="1.3" />
    </svg>
  )
}

function LockIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <rect x="3" y="7" width="10" height="7" rx="1.6" stroke="currentColor" strokeWidth="1.5" />
      <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" stroke="currentColor" strokeWidth="1.5" />
    </svg>
  )
}

function WorkspaceIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <circle cx="6" cy="6" r="2.4" stroke="currentColor" strokeWidth="1.5" />
      <path d="M1.8 13.5a4.2 4.2 0 0 1 8.4 0M10.5 4a2.2 2.2 0 0 1 0 4.2M12 9.6a3.8 3.8 0 0 1 2.2 3.9" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}

// @find: ThreadHeader, thread header, thread header, conversation title, rename conversation, who is in conversation
export function ThreadHeader({
  eyebrow,
  title,
  conversation,
  unavailable = false,
  participants,
  readOnly,
  onOpenSidebar,
  onNewConversation,
  onRename,
  onTogglePin,
  onCopyLink,
  onCopyConversation,
  onDelete,
  onSetVisibility,
  onAddPeople,
  speaker,
  detailsMode,
  onDetailsMode,
  onShowShortcuts,
  workPanelOpen,
  onToggleWorkPanel,
}: {
  eyebrow: string
  title: string
  conversation: Conversation | null
  /** The conversation could not be opened: deleted, not this person's to see, or failed to load. */
  unavailable?: boolean
  participants: Agent[]
  readOnly: boolean
  onOpenSidebar?: () => void
  onNewConversation?: () => void
  onRename: (title: string) => Promise<boolean>
  onTogglePin: () => void
  onCopyLink: () => void
  onCopyConversation: () => void
  onDelete: () => void
  onSetVisibility?: (visibility: 'private' | 'workspace') => void
  onAddPeople?: () => void
  speaker: ReturnType<typeof useSpeaker>
  detailsMode: DetailsMode
  onDetailsMode: (mode: DetailsMode) => void
  onShowShortcuts: () => void
  workPanelOpen?: boolean
  onToggleWorkPanel?: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [busy, setBusy] = useState(false)
  const inputRef = useRef<HTMLInputElement | null>(null)

  useEffect(() => {
    if (editing) inputRef.current?.focus()
  }, [editing])

  async function commitRename(value: string) {
    const trimmed = value.trim()
    if (!trimmed || trimmed === title) {
      setEditing(false)
      return
    }
    setBusy(true)
    await onRename(trimmed)
    setBusy(false)
    setEditing(false)
  }

  const canManage = !unavailable && (conversation?.canManage ?? false)
  const usable = !unavailable && conversation !== null
  const canShare = usable && (conversation?.canShare ?? false)
  const shown = participants.slice(0, 4)
  const extra = participants.length - shown.length
  // A conversation not yet started is private until it is shared.
  const visibility = conversation ? (conversation.visibility ?? 'workspace') : 'private'
  const visibilityText = visibilityLabel(visibility)

  return (
    <header className="chat-thread-header">
      {onOpenSidebar && (
        <IconButton label="Conversations" className="chat-thread-mobile-only" onClick={onOpenSidebar}>
          <ConversationsIcon />
        </IconButton>
      )}
      {onNewConversation && (
        <IconButton label="New conversation" className="chat-thread-mobile-only" onClick={onNewConversation}>
          <NewIcon />
        </IconButton>
      )}

      <div className="chat-thread-heading">
        {/* The screen's eyebrow, kept for screen readers; the row itself carries the title. */}
        <div className="visually-hidden">
          <Eyebrow>{eyebrow}</Eyebrow>
        </div>
        {editing ? (
          <input
            ref={inputRef}
            className="input chat-thread-rename"
            aria-label="Conversation title"
            defaultValue={title}
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
          <h1 id="chat-thread-title" className="chat-thread-title" tabIndex={-1} title={title}>
            {title}
          </h1>
        )}
        {!unavailable && (
          <span className="chat-thread-privacy" data-visibility={visibility} title={visibilityText}>
            {visibility === 'private' ? <LockIcon /> : <WorkspaceIcon />}
            <span aria-hidden="true">{visibility === 'private' ? 'Private' : 'Workspace'}</span>
            <span className="visually-hidden">{visibilityText}</span>
          </span>
        )}
        {!editing && shown.length > 0 && (
          <ul className="chat-thread-agents" aria-label="Agents in this conversation">
            {shown.map((agent) => (
              <li key={agent.id} title={agent.name}>
                <AgentAvatar name={agent.name} category={agent.category} fallback={agent.fallback ?? false} size="sm" />
              </li>
            ))}
            {extra > 0 && <li className="caption muted">+{extra}</li>}
          </ul>
        )}
      </div>

      {readOnly && (
        <>
          <Tag tone="neutral" title="Your role can ask document questions here, but cannot start agents on new work.">
            Questions only
          </Tag>
          <span className="visually-hidden">
            Your role can ask document questions here, but cannot start agents on new work.
          </span>
        </>
      )}

      <div className="chat-thread-header-actions row">
        {onToggleWorkPanel && (
          <IconButton
            label={workPanelOpen ? 'Hide work panel' : 'Show work panel'}
            aria-expanded={workPanelOpen}
            aria-controls="chat-work-panel"
            className="chat-thread-panel-toggle"
            onClick={onToggleWorkPanel}
          >
            <PanelIcon />
          </IconButton>
        )}

        <MenuButton
          label="Conversation actions"
          trigger="icon"
          icon={<MenuIcon />}
          align="end"
          items={[
            ...(canManage ? [{ id: 'rename', label: 'Rename', onSelect: () => setEditing(true) }] : []),
            ...(unavailable
              ? []
              : [
                  { id: 'pin', label: conversation?.pinned ? 'Unpin' : 'Pin', onSelect: onTogglePin, disabled: !usable },
                  { id: 'copy-link', label: 'Copy link', onSelect: onCopyLink, disabled: !usable },
                  { id: 'copy-conversation', label: 'Copy conversation', onSelect: onCopyConversation, disabled: !usable },
                ]),
            ...(canShare && (conversation?.visibility ?? 'workspace') === 'private' && onSetVisibility
              ? [{ id: 'share', label: 'Share with workspace', onSelect: () => onSetVisibility('workspace') }]
              : []),
            ...(canShare && (conversation?.visibility ?? 'workspace') === 'workspace' && onSetVisibility
              ? [{ id: 'make-private', label: 'Make private', onSelect: () => onSetVisibility('private') }]
              : []),
            ...(canShare && (conversation?.visibility ?? 'workspace') === 'private' && onAddPeople
              ? [{ id: 'add-people', label: 'Add people', onSelect: onAddPeople }]
              : []),
            ...(speaker.provider !== 'none'
              ? [
                  {
                    id: 'read-aloud',
                    label: speaker.muted ? 'Sound off, turn on' : 'Sound on, turn off',
                    checked: !speaker.muted,
                    onSelect: () => speaker.setMuted(!speaker.muted),
                  },
                ]
              : []),
            { id: 'details-group', groupLabel: 'Details' } as const,
            { id: 'details-auto', label: 'Automatic', group: 'details-group', checked: detailsMode === 'auto', onSelect: () => onDetailsMode('auto') },
            { id: 'details-expanded', label: 'Expanded', group: 'details-group', checked: detailsMode === 'expanded', onSelect: () => onDetailsMode('expanded') },
            { id: 'details-collapsed', label: 'Collapsed', group: 'details-group', checked: detailsMode === 'collapsed', onSelect: () => onDetailsMode('collapsed') },
            { id: 'shortcuts', label: 'Keyboard shortcuts', onSelect: onShowShortcuts },
            ...(canManage ? [{ id: 'delete', label: 'Delete', danger: true, onSelect: onDelete }] : []),
          ]}
        />
      </div>
    </header>
  )
}
