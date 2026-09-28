import type { ChatMessage } from '../../lib/queries'

/** What the person typed, right-aligned, the way the composer sent it. */
export function UserBubble({ message, grouped }: { message: ChatMessage; grouped: boolean }) {
  return (
    <div className="chat-bubble-row chat-bubble-row-user">
      <div className={`chat-bubble chat-bubble-user${grouped ? ' chat-bubble-grouped' : ''}`}>
        <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', margin: 0 }}>{message.content}</p>
      </div>
    </div>
  )
}
