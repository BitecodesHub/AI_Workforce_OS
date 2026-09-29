/** The pill above the composer that returns the reader to the bottom of the thread (B1.6). */
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
        ? `${newCount} new ${newCount === 1 ? 'reply' : 'replies'}`
        : 'Jump to latest'
  return (
    <button type="button" className="chat-jump" onClick={onJump}>
      {label}
    </button>
  )
}
