import { useEffect, useRef, useState } from 'react'
import { Eyebrow, IconButton, Tag } from '../ui'
import { MenuButton } from '../ui/Menu'
import type { Agent, Conversation } from '../../lib/queries'
import type { useSpeaker } from '../../lib/voice'
import type { DetailsMode } from './detailsContext'
import { AgentAvatar } from '../ui/AgentAvatar'

/*
 * The 52px band above the thread (B1.4): the conversation's title (renamable), who is in it, a
 * compact notice for a questions-only role, and the menu that holds everything else a conversation
 * can do.
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

export function ThreadHeader({
  eyebrow,
  title,
  conversation,
  participants,
  readOnly,
  onOpenSidebar,
  onNewConversation,
  onRename,
  onTogglePin,
  onCopyLink,
  onCopyConversation,
  onDelete,
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
  participants: Agent[]
  readOnly: boolean
  onOpenSidebar?: () => void
  onNewConversation?: () => void
  onRename: (title: string) => Promise<boolean>
  onTogglePin: () => void
  onCopyLink: () => void
  onCopyConversation: () => void
  onDelete: () => void
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

  const canManage = conversation?.canManage ?? false
  const shown = participants.slice(0, 4)
  const extra = participants.length - shown.length

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
        <Eyebrow>{eyebrow}</Eyebrow>
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
          <h1 id="chat-thread-title" className="chat-thread-title" tabIndex={-1}>
            {title}
          </h1>
        )}
      </div>

      {shown.length > 0 && (
        <ul className="chat-thread-agents" aria-label="Agents in this conversation">
          {shown.map((agent) => (
            <li key={agent.id} title={agent.name}>
              <AgentAvatar name={agent.name} category={agent.category} fallback={agent.fallback ?? false} size="sm" />
            </li>
          ))}
          {extra > 0 && <li className="caption muted">+{extra}</li>}
        </ul>
      )}

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
            { id: 'pin', label: conversation?.pinned ? 'Unpin' : 'Pin', onSelect: onTogglePin, disabled: !conversation },
            { id: 'copy-link', label: 'Copy link', onSelect: onCopyLink, disabled: !conversation },
            { id: 'copy-conversation', label: 'Copy conversation', onSelect: onCopyConversation, disabled: !conversation },
            ...(speaker.provider !== 'none'
              ? [
                  {
                    id: 'read-aloud',
                    label: 'Read new replies aloud',
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
