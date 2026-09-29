import type { RefObject } from 'react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Button, ConfirmDialog, IconButton } from '../ui'
import { modLabel } from '../../lib/hotkeys'
import { usePersistentState } from '../../lib/persist'
import {
  useArchiveConversation,
  useConversationList,
  useDeleteConversation,
  usePinConversation,
  useRenameConversation,
} from '../../lib/queries'
import type { Conversation, ConversationScope } from '../../lib/queries'
import { useToast } from '../../lib/toast'
import { useNow } from '../../lib/useNow'
import { ConversationRow } from './ConversationRow'
import { groupConversations, nextAfterRemoval } from './conversationGroups'
import { useRovingList } from './useRovingList'

/*
 * The conversation sidebar (B1.3): a collapsible panel with search, a Mine/Everyone scope, the
 * grouped list itself, and a compact icon rail for narrower widths. `variant` picks which of those
 * to render; the surrounding shell (Chat.tsx) decides where that markup sits (a fixed column, an
 * overlay, or the inside of a mobile Sheet) and applies the matching CSS.
 */

function NewConversationIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  )
}

function SearchIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <circle cx="7" cy="7" r="4.2" stroke="currentColor" strokeWidth="1.3" />
      <path d="M12.5 12.5 15 15" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
    </svg>
  )
}

function NeedsYouIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <circle cx="8" cy="8" r="6" stroke="currentColor" strokeWidth="1.3" />
      <path d="M8 5v3.5M8 11v.01" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  )
}

function CollapseIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path d="M4 3v10M10 6l-2.5 2 2.5 2" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

type GroupToggles = Record<string, boolean>

export function ChatSidebar({
  variant,
  onExpand,
  onCollapse,
  selectedId,
  onSelect,
  onNew,
  searchRef,
  focusSection,
}: {
  variant: 'panel' | 'rail' | 'overlay' | 'sheet'
  onExpand: () => void
  onCollapse: () => void
  selectedId: string | null
  onSelect: (id: string) => void
  onNew: () => void
  searchRef: RefObject<HTMLInputElement | null>
  focusSection: 'search' | 'needs-you' | null
}) {
  const now = useNow(60_000)
  const toast = useToast()
  const needsYouRef = useRef<HTMLLIElement | null>(null)

  const [scope, setScope] = usePersistentState<ConversationScope>(
    'chat.scope',
    'mine',
    (v: unknown): v is ConversationScope => v === 'mine' || v === 'all' || v === 'archived',
  )
  const [query, setQuery] = useState('')
  const [debounced, setDebounced] = useState('')
  const [groupToggles, setGroupToggles] = usePersistentState<GroupToggles>('chat.groups', {})
  const [deleteTarget, setDeleteTarget] = useState<Conversation | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(query), 250)
    return () => window.clearTimeout(timer)
  }, [query])

  useEffect(() => {
    if (focusSection === 'search') searchRef.current?.focus()
    else if (focusSection === 'needs-you') needsYouRef.current?.querySelector<HTMLElement>('a, button')?.focus()
  }, [focusSection, searchRef])

  const list = useConversationList({ q: debounced, scope })
  const rename = useRenameConversation()
  const pin = usePinConversation()
  const archive = useArchiveConversation()
  const del = useDeleteConversation()

  const pages = useMemo(() => list.data?.pages ?? [], [list.data])
  const lastPage = pages[pages.length - 1]
  const pinned = useMemo(() => lastPage?.pinned ?? [], [lastPage])
  const needsYou = useMemo(() => lastPage?.needsYou ?? [], [lastPage])
  const rows = useMemo(() => {
    const seen = new Set<string>()
    const combined: Conversation[] = []
    for (const page of pages) {
      for (const row of page.conversations) {
        if (seen.has(row.id)) continue
        seen.add(row.id)
        combined.push(row)
      }
    }
    return combined
  }, [pages])

  const groups = useMemo(() => groupConversations(pinned, needsYou, rows, new Date(now)), [pinned, needsYou, rows, now])
  const allIds = useMemo(() => groups.flatMap((group) => group.conversations.map((c) => c.id)), [groups])
  const roving = useRovingList(allIds)

  const hasAnyRows = pinned.length > 0 || needsYou.length > 0 || rows.length > 0

  function toggleGroup(key: string, defaultOpen = true) {
    setGroupToggles((current) => ({ ...current, [key]: !(current[key] ?? defaultOpen) }))
  }

  async function handleArchive(conversation: Conversation) {
    const wasArchived = conversation.archived
    await archive.mutateAsync({ id: conversation.id, archived: !wasArchived })
    if (!wasArchived) {
      toast.info(`Archived "${conversation.title || 'Untitled conversation'}".`, {
        action: { label: 'Undo', onSelect: () => void archive.mutateAsync({ id: conversation.id, archived: false }) },
      })
      if (selectedId === conversation.id) {
        const next = nextAfterRemoval(allIds, conversation.id)
        if (next) onSelect(next)
      }
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return
    setDeleteError(null)
    try {
      await del.mutateAsync(deleteTarget.id)
      if (selectedId === deleteTarget.id) {
        const next = nextAfterRemoval(allIds, deleteTarget.id)
        if (next) onSelect(next)
      }
      setDeleteTarget(null)
    } catch (error) {
      setDeleteError(error instanceof Error ? error.message : 'This conversation could not be deleted.')
    }
  }

  if (variant === 'rail') {
    return (
      <nav className="chat-rail" aria-label="Conversations">
        <IconButton label="Show conversations" aria-expanded={false} aria-controls="chat-sidebar-panel" onClick={onExpand}>
          <CollapseIcon />
        </IconButton>
        <IconButton label="New conversation" onClick={onNew}>
          <NewConversationIcon />
        </IconButton>
        <IconButton
          label="Search conversations"
          onClick={() => {
            onExpand()
          }}
        >
          <SearchIcon />
        </IconButton>
        <IconButton
          label="Needs you"
          badge={needsYou.length}
          badgeLabel="waiting for you"
          onClick={() => {
            onExpand()
          }}
        >
          <NeedsYouIcon />
        </IconButton>
      </nav>
    )
  }

  return (
    <div className="chat-sidebar" id="chat-sidebar-panel" data-variant={variant}>
      <div className="chat-sidebar-header">
        <Button variant="outline" className="chat-sidebar-new" onClick={onNew} icon={<NewConversationIcon />}>
          New conversation
        </Button>
        {variant !== 'sheet' && (
          <IconButton label="Hide conversations" aria-expanded={true} aria-controls="chat-sidebar-panel" onClick={onCollapse}>
            <CollapseIcon />
          </IconButton>
        )}
      </div>

      <div className="chat-sidebar-search">
        <label htmlFor="chat-sidebar-search-input" className="visually-hidden">
          Search conversations
        </label>
        <div className="chat-sidebar-search-field">
          <SearchIcon />
          <input
            id="chat-sidebar-search-input"
            ref={searchRef}
            className="input"
            type="search"
            placeholder="Search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Escape' && query) {
                event.preventDefault()
                setQuery('')
              }
            }}
          />
          <kbd className="kbd chat-sidebar-search-kbd">{modLabel() === 'Cmd' ? 'Cmd K' : 'Ctrl K'}</kbd>
        </div>
      </div>

      <div className="chat-sidebar-scope row" role="group" aria-label="Which conversations to show">
        <button
          type="button"
          className="chat-scope-button"
          aria-pressed={scope === 'mine'}
          onClick={() => setScope('mine')}
        >
          Mine
        </button>
        <button
          type="button"
          className="chat-scope-button"
          aria-pressed={scope === 'all'}
          onClick={() => setScope('all')}
        >
          Everyone
        </button>
        <button
          type="button"
          className="link chat-sidebar-archived-link"
          onClick={() => setScope(scope === 'archived' ? 'mine' : 'archived')}
        >
          {scope === 'archived' ? 'Back to conversations' : 'Archived'}
        </button>
      </div>

      <div className="chat-sidebar-list">
        {list.isLoading ? (
          <div className="stack" style={{ gap: 'var(--space-3)', padding: 'var(--space-4)' }} role="status" aria-busy="true">
            <span className="visually-hidden">Loading conversations</span>
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="skeleton" aria-hidden="true" style={{ height: 52 }} />
            ))}
          </div>
        ) : !hasAnyRows ? (
          <p className="caption muted chat-sidebar-empty">
            {debounced ? (
              <>
                Nothing matches "{debounced}".{' '}
                <button type="button" className="link" onClick={() => setQuery('')}>
                  Clear search
                </button>
              </>
            ) : scope === 'archived' ? (
              'No archived conversations.'
            ) : scope === 'mine' ? (
              <>
                You have not started a conversation yet. Start one with the box on the right, or see{' '}
                <button type="button" className="link" onClick={() => setScope('all')}>
                  everyone’s conversations
                </button>
                .
              </>
            ) : (
              'No conversations yet. Start one with the box on the right.'
            )}
          </p>
        ) : (
          <ul className="chat-sidebar-groups">
            {groups.map((group) => {
              const open = groupToggles[group.key] ?? true
              return (
                <li key={group.key} ref={group.key === 'needs-you' ? needsYouRef : undefined}>
                  <button
                    type="button"
                    className="chat-group-toggle"
                    aria-expanded={open}
                    onClick={() => toggleGroup(group.key)}
                  >
                    <span>
                      {group.label}
                      {!open && ` · ${group.conversations.length}`}
                    </span>
                  </button>
                  {open && (
                    <ul className="chat-row-list">
                      {group.conversations.map((conversation) => (
                        <ConversationRow
                          key={conversation.id}
                          conversation={conversation}
                          current={conversation.id === selectedId}
                          tabbable={roving.activeId === conversation.id}
                          now={now}
                          highlight={debounced}
                          rowRef={() => {}}
                          onNavigate={roving.onKeyDown}
                          onSelect={() => {
                            roving.setActiveId(conversation.id)
                            onSelect(conversation.id)
                          }}
                          onRename={async (title) => {
                            await rename.mutateAsync({ id: conversation.id, title })
                            return true
                          }}
                          onTogglePin={() => void pin.mutateAsync({ id: conversation.id, pinned: !conversation.pinned })}
                          onToggleArchive={() => void handleArchive(conversation)}
                          onCopyLink={() =>
                            void navigator.clipboard?.writeText(`${window.location.origin}/chat?c=${conversation.id}`)
                          }
                          onDelete={() => {
                            setDeleteError(null)
                            setDeleteTarget(conversation)
                          }}
                        />
                      ))}
                    </ul>
                  )}
                </li>
              )
            })}
            {list.hasNextPage && (
              <li className="chat-sidebar-more">
                <Button variant="quiet" onClick={() => void list.fetchNextPage()} loading={list.isFetchingNextPage}>
                  Show older conversations
                </Button>
              </li>
            )}
          </ul>
        )}
      </div>

      <ConfirmDialog
        open={deleteTarget !== null}
        onClose={() => setDeleteTarget(null)}
        onConfirm={() => void handleDelete()}
        eyebrow="Delete conversation"
        title="Delete this conversation?"
        description={`"${deleteTarget?.title || 'Untitled conversation'}" and its messages are removed for everyone. Work still running from it is stopped. This cannot be undone.`}
        confirmLabel="Delete"
        tone="danger"
        loading={del.isPending}
        error={deleteError}
      />
    </div>
  )
}
