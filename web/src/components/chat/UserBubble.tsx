// @find: user message, my message, sent message bubble, person bubble, chat bubble
// @what: A person's own message in the thread.
// @flow: Used by MessageItem; shows AttachmentCards.
import { useState } from 'react'
import { CopyButton } from '../ui/CopyButton'
import { sentAttachmentsOf } from '../../lib/attachments'
import type { ChatMessage } from '../../lib/queries'
import { AttachmentCards } from './AttachmentCards'
import { sentContextOf } from './composerContext'
import { SentContextChips } from './ContextChips'

const CLAMP_LINES = 12

// @find: UserBubble, user bubble, user message, my message, sent message bubble, person bubble
/**
 * What a person typed, right-aligned, the way the composer sent it.
 *
 * Rendered by MessageItem for a person's text message. Shows the author's name when the message does not
 * continue a group and is not the viewer's own, the context chips that were attached (SentContextChips), the
 * attached files (AttachmentCards), the text (clamped to 12 lines with Show more or Show less) and actions:
 * Copy, and for the viewer's own text "Edit and send again", which hands the text to onEditAndResend so
 * Composer can refill. A message with files or context only has no text bubble or text actions.
 */
export function UserBubble({
  message,
  grouped,
  author,
  onEditAndResend,
}: {
  message: ChatMessage
  grouped: boolean
  author: { isMe: boolean; name: string }
  onEditAndResend: (text: string) => void
}) {
  const [expanded, setExpanded] = useState(false)
  const files = sentAttachmentsOf(message)
  const attached = sentContextOf(message.detail as Record<string, unknown> | null)
  const hasText = message.content.trim().length > 0
  const lines = message.content.split('\n').length
  const clampable = lines > CLAMP_LINES

  return (
    <div className="chat-bubble-row chat-bubble-row-user">
      <div className="stack" style={{ gap: 'var(--space-1)', alignItems: 'flex-end' }}>
        {!grouped && !author.isMe && <span className="caption chat-bubble-author">{author.name}</span>}
        {attached && <SentContextChips context={attached} />}
        {files.length > 0 && <AttachmentCards attachments={files} align="end" />}
        {hasText && (
        <div className={`chat-bubble chat-bubble-user${grouped ? ' chat-bubble-grouped' : ''}`}>
          <p
            style={{
              whiteSpace: 'pre-wrap',
              overflowWrap: 'anywhere',
              margin: 0,
              ...(clampable && !expanded
                ? {
                    display: '-webkit-box',
                    WebkitLineClamp: CLAMP_LINES,
                    WebkitBoxOrient: 'vertical' as const,
                    overflow: 'hidden',
                  }
                : {}),
            }}
          >
            {message.content}
          </p>
          {clampable && (
            <button type="button" className="link caption" onClick={() => setExpanded((current) => !current)}>
              {expanded ? 'Show less' : 'Show more'}
            </button>
          )}
        </div>
        )}
        <div className="row chat-actions" style={{ gap: 'var(--space-3)' }}>
          {hasText && <CopyButton text={message.content} label="Copy" />}
          {author.isMe && hasText && (
            <button type="button" className="link caption" onClick={() => onEditAndResend(message.content)}>
              Edit and send again
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
