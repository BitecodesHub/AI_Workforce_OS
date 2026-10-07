/*
 * What the sidebar and the thread draw while they load: the same rows and bubbles the content will
 * have, at about the same height, so nothing jumps when the real thing arrives. The shimmer comes
 * from .skeleton, which stops for people who ask for less motion.
 */

export function SidebarSkeleton() {
  return (
    <div className="chat-skel-rows" role="status" aria-busy="true">
      <span className="visually-hidden">Loading conversations</span>
      {[0, 1, 2, 3, 4].map((i) => (
        <div key={i} className="chat-skel-row" aria-hidden="true">
          <span className="skeleton chat-skel-title" />
          <span className="skeleton chat-skel-preview" />
          <span className="skeleton chat-skel-meta" />
        </div>
      ))}
    </div>
  )
}

export function ThreadSkeleton() {
  return (
    <div className="chat-skel-thread" role="status" aria-busy="true">
      <span className="visually-hidden">Loading this conversation</span>
      <div className="chat-skel-user" aria-hidden="true">
        <span className="skeleton chat-skel-bubble-user" />
      </div>
      <div className="chat-skel-agent" aria-hidden="true">
        <span className="skeleton chat-skel-avatar" />
        <div className="chat-skel-lines">
          <span className="skeleton chat-skel-line" style={{ width: '38%' }} />
          <span className="skeleton chat-skel-line" style={{ width: '92%' }} />
          <span className="skeleton chat-skel-line" style={{ width: '84%' }} />
          <span className="skeleton chat-skel-line" style={{ width: '56%' }} />
        </div>
      </div>
    </div>
  )
}
