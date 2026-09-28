import { Button } from '../ui'
import { formatRelativeTicked } from '../../lib/format'
import { useNow } from '../../lib/useNow'
import type { Conversation } from '../../lib/queries'

/*
 * Which conversation is open. A real left rail at desktop width, a compact header select below
 * 900px - two markup blocks, one hidden by CSS at each width, so there is no JavaScript
 * breakpoint to get out of sync with the stylesheet (see the same pattern in Navbar's capsule).
 */

export function ConversationRail({
  conversations,
  loading,
  selectedId,
  onSelect,
  onNew,
}: {
  conversations: Conversation[] | undefined
  loading: boolean
  selectedId: string | null
  onSelect: (id: string) => void
  onNew: () => void
}) {
  const now = useNow(60_000)
  const rows = conversations ?? []

  return (
    <>
      <nav className="chat-rail" aria-label="Conversations">
        <Button variant="outline" className="chat-rail-new" onClick={onNew}>
          New conversation
        </Button>
        {loading && rows.length === 0 ? (
          <p className="caption muted" style={{ padding: 'var(--space-3)' }}>
            Loading conversations…
          </p>
        ) : rows.length === 0 ? (
          <p className="caption muted" style={{ padding: 'var(--space-3)' }}>
            Nothing here yet. Start a conversation below.
          </p>
        ) : (
          <ul className="chat-rail-list">
            {rows.map((conversation) => (
              <li key={conversation.id}>
                <button
                  type="button"
                  className="chat-rail-item"
                  aria-current={conversation.id === selectedId ? 'true' : undefined}
                  onClick={() => onSelect(conversation.id)}
                >
                  <span className="chat-rail-item-title">{conversation.title || 'Untitled conversation'}</span>
                  <span className="chat-rail-item-preview">{conversation.lastMessagePreview || 'No messages yet'}</span>
                  <span className="chat-rail-item-time caption">{formatRelativeTicked(conversation.updatedAt, now, 60_000)}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </nav>

      <div className="chat-rail-compact">
        <label className="visually-hidden" htmlFor="chat-conversation-select">
          Conversation
        </label>
        <select
          id="chat-conversation-select"
          className="select"
          value={selectedId ?? ''}
          onChange={(event) => {
            if (event.target.value) onSelect(event.target.value)
          }}
        >
          <option value="">New conversation</option>
          {rows.map((conversation) => (
            <option key={conversation.id} value={conversation.id}>
              {conversation.title || 'Untitled conversation'}
            </option>
          ))}
        </select>
        <Button variant="outline" onClick={onNew}>
          New
        </Button>
      </div>
    </>
  )
}
