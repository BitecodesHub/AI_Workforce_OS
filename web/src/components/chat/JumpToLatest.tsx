// @find: jump to latest, scroll to bottom, new messages button, chat scroll
// @what: Button that scrolls the thread to the newest message.
// @flow: Used by the Chat page with useStickToBottom.
// @find: JumpToLatest, jump to latest, jump to latest, scroll to bottom, new messages button, chat scroll
/**
 * The pill above the composer that returns the reader to the bottom of the thread (B1.6).
 *
 * Props: visible (the Chat page shows it when the reader is far from the bottom, from useStickToBottom),
 * newCount (messages that arrived meanwhile), newestIsQuestion and agentName (name who asked a new
 * question) and onJump. The label is "New question from <agent>", "New messages" with a count, or "Jump to
 * latest". Renders nothing when not visible.
 */
export function JumpToLatest({
  visible,
  newCount,
  newestIsQuestion,
  agentName,
  onJump,
}: {
  visible: boolean
  newCount: number
  /** Whether the newest unseen item is a question, so the pill can name who asked it. */
  newestIsQuestion?: boolean
  agentName?: string | null
  onJump: () => void
}) {
  if (!visible) return null
  const label =
    newestIsQuestion && agentName
      ? `New question from ${agentName}`
      : newCount > 0
        ? newCount === 1
          ? 'New messages'
          : `New messages (${newCount})`
        : 'Jump to latest'
  return (
    <button type="button" className="chat-jump" onClick={onJump}>
      {label}
    </button>
  )
}
