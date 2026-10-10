// @find: notice line, system message, thread notice, status line in chat
// @what: A one-line system notice in the thread.
// @flow: Used by MessageItem.
import { formatDateTime } from '../../lib/format'
import type { ChatMessage } from '../../lib/queries'

// @find: NoticeLine, notice line, notice line, system message, thread notice, status line in chat
/** A centred caption between two hairlines: "Stopped. Stopped from the chat.", a retry, a reroute. */
export function NoticeLine({ message }: { message: ChatMessage }) {
  return (
    <p className="chat-notice">
      <span>{message.content}</span>{' '}
      <time className="caption" dateTime={message.createdAt} title={formatDateTime(message.createdAt)}>
        {new Date(message.createdAt).toLocaleTimeString('en-AU', { hour: 'numeric', minute: '2-digit' })}
      </time>
    </p>
  )
}
