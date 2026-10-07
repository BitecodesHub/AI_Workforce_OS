import { formatAgo, plural } from '../../lib/format'
import { useNow } from '../../lib/useNow'

/*
 * The board's own clock (B2.11): "Updated 4 s ago" while live, ticking on its own second so
 * nothing else on the page has to re-render just to keep this line current, and "Updates paused"
 * with a way back to the freshest board once it is.
 */
export function Freshness({
  live,
  generatedAt,
  pendingChanges,
  onShowLatest,
}: {
  live: boolean
  generatedAt: string
  /** How many cards differ between the frozen board on screen and the latest one polled. */
  pendingChanges: number
  onShowLatest: () => void
}) {
  const now = useNow(1_000)

  if (!live) {
    return (
      <span className="orc-live">
        <span className="orc-status-pill" data-state="paused">
          <span className="orc-status-pill-dot" aria-hidden="true" />
          Updates paused
        </span>
        {pendingChanges > 0 && (
          <span className="orc-live-pending">
            {plural(pendingChanges, 'change', 'changes')} since{' · '}
            <button type="button" className="link" onClick={onShowLatest}>
              Show latest
            </button>
          </span>
        )}
      </span>
    )
  }

  return (
    <span className="orc-live">
      <span className="orc-status-pill" data-state="live">
        <span className="orc-live-dot" aria-hidden="true" />
        Live
      </span>
      <span className="orc-live-pending">Updated {formatAgo(now - Date.parse(generatedAt))}</span>
    </span>
  )
}
