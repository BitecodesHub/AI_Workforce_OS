// @find: user message, my message, sent message bubble, person bubble, chat bubble
// @what: A person's own message in the thread.
// @flow: Used by MessageItem; shows AttachmentCards.
import { useState } from 'react'
import { CopyButton } from '../ui/CopyButton'
import { sentAttachmentsOf } from '../../lib/attachments'
import type { ChatMessage } from '../../lib/queries'
import { AttachmentCards } from './AttachmentCards'

const CLAMP_LINES = 12

// @find: UserBubble, user bubble, user message, my message, sent message bubble, person bubble
/** What a person typed, right-aligned, the way the composer sent it. */
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
  const hasText = message.content.trim().length > 0
  const lines = message.content.split('\n').length
  const clampable = lines > CLAMP_LINES

  return (
    <div className="chat-bubble-row chat-bubble-row-user">
      <div className="stack" style={{ gap: 'var(--space-1)', alignItems: 'flex-end' }}>
        {!grouped && !author.isMe && <span className="caption chat-bubble-author">{author.name}</span>}
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
