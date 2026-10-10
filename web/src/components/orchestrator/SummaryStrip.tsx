// @find: summary strip, summary tiles, running, waiting, needs you, failed today, held, done today, filter by status, counts, SummaryStrip, Orchestrator
// @what: The compact strip of figures that answer what is running and what needs me, each acting as a filter.
// @flow: Counts from layout.countCards; rendered above the Board
import { formatCount, formatMoney } from '../../lib/format'
import type { BoardStats } from '../../lib/queries'
import { can } from '../../lib/session'
import { countCards, directRunsTileNote, waitingTileNote } from './layout'
import type { BoardCard, CardStatusKey } from './layout'

/*
 * The figures that answer "what is running and what needs me", as one compact strip of filters
 * (B2.3, D-20). Every figure is counted from the cards the board itself draws, so pressing one
 * shows exactly the number it just read. "Failed today" is counted from local midnight rather than
 * the window, so its click widens the window to match. Held and Done today live in the Goals
 * filter, beside the other statuses, so nothing the old tiles showed is lost.
 *
 * "Spend today" opens the Budget card in Analytics, but only for somebody who can open Analytics:
 * a link to a page a person is refused would be a dead end, so everyone else gets the figure alone.
 */

function Figure({
  label,
  value,
  note,
  pressed,
  onClick,
  tone,
}: {
  label: string
  value: string
  note?: string | null
  pressed: boolean
  onClick: () => void
  tone?: 'attention' | 'danger'
}) {
  return (
    <button type="button" className="orc-figure" aria-pressed={pressed} data-tone={tone} onClick={onClick}>
      <span className="orc-figure-label">{label}</span>
      <span className="orc-figure-value tabular">{value}</span>
      {note && <span className="orc-figure-note">{note}</span>}
    </button>
  )
}

// @find: summary strip component, status tiles and counts
export function SummaryStrip({
  cards,
  stats,
  questionCount,
  approvalCount,
  status,
  onToggleStatus,
  isToday,
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
  onToggleFailedToday: () => void
}) {
  const spend = stats.spendToday
  const needsCount = countCards(cards, 'needs_you')
  const spendLink = can('analytics:read') ? '/analytics#budget' : undefined
  const spendText = spend > 0 && spend < 0.01 ? 'Under US$0.01' : formatMoney(spend)
  const spendTitle = spend > 0 && spend < 0.01 ? `Exactly ${formatMoney(spend)}` : undefined

  return (
    <div className="orc-summary" role="group" aria-label="Summary">
      <Figure
        label="Working"
        value={formatCount(countCards(cards, 'working'))}
        note={directRunsTileNote(stats.directRuns)}
        pressed={status.has('working')}
        onClick={() => onToggleStatus('working')}
      />
      <Figure
        label="Needs you"
        value={formatCount(needsCount)}
        note={waitingTileNote(questionCount, approvalCount)}
        pressed={status.has('needs_you')}
        onClick={() => onToggleStatus('needs_you')}
        {...(needsCount > 0 ? { tone: 'attention' as const } : {})}
      />
      <Figure
        label="Queued"
        value={formatCount(countCards(cards, 'queued'))}
        pressed={status.has('queued')}
        onClick={() => onToggleStatus('queued')}
      />
      <Figure
        label="Failed today"
        value={formatCount(stats.goalsFailedToday)}
        pressed={isToday && status.has('failed')}
        onClick={onToggleFailedToday}
        {...(stats.goalsFailedToday > 0 ? { tone: 'danger' as const } : {})}
      />
      {spendLink ? (
        <a className="orc-figure orc-figure-link" href={spendLink} title={spendTitle}>
          <span className="orc-figure-label">Spend today</span>
          <span className="orc-figure-value tabular">{spendText}</span>
          {spendTitle && <span className="visually-hidden">{spendTitle}</span>}
        </a>
      ) : (
        <span className="orc-figure orc-figure-static" title={spendTitle}>
          <span className="orc-figure-label">Spend today</span>
          <span className="orc-figure-value tabular">{spendText}</span>
          {spendTitle && <span className="visually-hidden">{spendTitle}</span>}
        </span>
      )}
    </div>
  )
}
