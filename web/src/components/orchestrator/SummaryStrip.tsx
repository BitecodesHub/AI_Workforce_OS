import { StatTile } from '../ui'
import { formatCount, formatMoney } from '../../lib/format'
import type { BoardStats } from '../../lib/queries'
import { countCards } from './layout'
import type { BoardCard, CardStatusKey } from './layout'

/*
 * The stat row rewritten as filters (B2.3, D-20): every figure here is counted from the cards the
 * board itself draws, so pressing a tile shows exactly the number it just read. "Done today" and
 * "Failed today" are the two figures the server counts from local midnight rather than the window,
 * so their own clicks widen the window to match what they are already counting.
 */
export function SummaryStrip({
  cards,
  stats,
  questionCount,
  approvalCount,
  status,
  onToggleStatus,
  isToday,
  onToggleDoneToday,
  onToggleFailedToday,
}: {
  cards: readonly BoardCard[]
  stats: BoardStats
  /** From the live board, not the frozen one: the inbox below never disagrees with this note. */
  questionCount: number
  approvalCount: number
  status: ReadonlySet<CardStatusKey>
  onToggleStatus: (value: CardStatusKey) => void
  isToday: boolean
  onToggleDoneToday: () => void
  onToggleFailedToday: () => void
}) {
  const spend = stats.spendToday

  return (
    <div className="orc-summary">
      <StatTile
        label="Working now"
        value={formatCount(countCards(cards, 'working'))}
        pressed={status.has('working')}
        onClick={() => onToggleStatus('working')}
        {...(stats.directRuns > 0 ? { note: `and ${formatCount(stats.directRuns)} direct runs` } : {})}
      />
      <StatTile
        label="Needs you"
        value={formatCount(countCards(cards, 'needs_you'))}
        note={`${formatCount(questionCount)} questions · ${formatCount(approvalCount)} approvals`}
        pressed={status.has('needs_you')}
        onClick={() => onToggleStatus('needs_you')}
      />
      <StatTile
        label="Queued"
        value={formatCount(countCards(cards, 'queued'))}
        pressed={status.has('queued')}
        onClick={() => onToggleStatus('queued')}
      />
      <StatTile
        label="Held"
        value={formatCount(countCards(cards, 'held'))}
        note="Agent paused"
        pressed={status.has('held')}
        onClick={() => onToggleStatus('held')}
      />
      <StatTile
        label="Done today"
        value={formatCount(stats.goalsCompletedToday)}
        pressed={isToday && status.has('finished')}
        onClick={onToggleDoneToday}
      />
      <StatTile
        label="Failed today"
        value={formatCount(stats.goalsFailedToday)}
        pressed={isToday && status.has('failed')}
        onClick={onToggleFailedToday}
      />
      {spend > 0 && spend < 0.01 ? (
        <StatTile label="Spend today" value="Under US$0.01" note={`Exactly ${formatMoney(spend)}`} href="/analytics" />
      ) : (
        <StatTile label="Spend today" value={formatMoney(spend)} href="/analytics" />
      )}
    </div>
  )
}
