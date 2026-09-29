import { useMemo, useRef, useState } from 'react'
import { Select, StatusTag, Tag } from '../ui'
import type { FilterSelect } from '../ui'
// Imported from its own module, not the ../ui barrel: this is used from Orchestrator, a
// lazy-loaded route, and the barrel is also part of the main bundle, so going through it created
// a circular chunk dependency (Rollup warned of a "broken execution order").
import { FilterBar, FilterEmpty } from '../ui/FilterBar'
import { BoardList } from './BoardList'
import { formatCount, formatElapsed, formatMoney, formatRunElapsed, truncateWords } from '../../lib/format'
import { goalSourceLabel } from '../../lib/labels'
import { useMemberNames } from '../../lib/queries'
import type { Board as BoardData } from '../../lib/queries'
import { useRouter } from '../../lib/router'
import { profile } from '../../lib/session'
import { useListFilter } from '../../lib/useListFilter'
import { useNow } from '../../lib/useNow'
import { useBoardKeyboard } from './useBoardKeyboard'
import { useKeepCardFocus } from './useKeepCardFocus'
import { columnTitle, queueReasonText, stepLabel } from './layout'
import type { BoardCard, BoardColumn, CardStatusKey } from './layout'
import { requesterLabel } from './shared'

/*
 * Every goal in flight, and everything finished in the window, sorted into the column its current
 * step is in (B2.5). One card per goal (D1): the sheet stays keyed to the same card through a
 * column move, because `card.id` is the goal's own id, never the task's.
 */

const COLUMNS: BoardColumn[] = ['queued', 'working', 'needs_you', 'finished']
const STATUS_OPTIONS: CardStatusKey[] = ['queued', 'held', 'working', 'needs_you', 'finished', 'failed']
const STATUS_LABEL: Record<CardStatusKey, string> = {
  queued: 'Queued',
  held: 'Held',
  working: 'Working',
  needs_you: 'Needs you',
  finished: 'Finished',
  failed: 'Failed',
}
const CARDS_PER_COLUMN = 8

export function OrchestratorBoard({
  board,
  cards,
  view,
  group,
  selectedAgentId,
  onSelectAgent,
  onOpenGoal,
  changedIds,
  onCardMoved,
}: {
  board: BoardData
  cards: BoardCard[]
  view: 'board' | 'list'
  group: 'none' | 'agent' | 'requester'
  selectedAgentId: string | null
  onSelectAgent: (agentId: string | null) => void
  onOpenGoal: (goalId: string) => void
  changedIds: ReadonlySet<string>
  onCardMoved?: (cardId: string, column: string) => void
}) {
  const { search, navigate } = useRouter()
  const now = useNow(1_000)
  const members = useMemberNames()
  const currentUserId = profile()?.userId ?? null
  const openGoalId = search.get('goal')
  const containerRef = useRef<HTMLDivElement>(null)

  const agentNames = useMemo(() => Object.fromEntries(board.agents.map((agent) => [agent.id, agent.name])), [board.agents])

  const rows = useMemo(
    () =>
      cards.map((card) => ({
        card,
        agentName: card.task?.agentId ? (agentNames[card.task.agentId] ?? '') : '',
        requester: requesterLabel(card.goal, members, currentUserId),
      })),
    [cards, agentNames, members, currentUserId],
  )

  const filter = useListFilter({
    rows,
    text: (row) => [row.card.goal.title, row.agentName, row.requester].join(' '),
    facets: {
      source: (row) => row.card.goal.source,
      status: (row) => row.card.statusKey,
      requester: (row) => (row.card.goal.requestedBy && row.card.goal.requestedBy === currentUserId ? 'me' : (row.card.goal.requestedBy ?? '')),
    },
  })

  const requesterOptions = useMemo(() => {
    const seen = new Map<string, string>()
    for (const row of rows) {
      if (!row.card.goal.requestedBy) continue
      const key = row.card.goal.requestedBy === currentUserId ? 'me' : row.card.goal.requestedBy
      if (!seen.has(key)) seen.set(key, key === 'me' ? 'You' : row.requester)
    }
    const others = [...seen.entries()].filter(([key]) => key !== 'me').sort((a, b) => a[1].localeCompare(b[1]))
    const mine = seen.has('me') ? [['me', 'You'] as [string, string]] : []
    return [...mine, ...others]
  }, [rows, currentUserId])

  const bySource = filter.filtered
  const byAgent = selectedAgentId ? bySource.filter((row) => row.card.task?.agentId === selectedAgentId) : bySource
  const visible = byAgent.map((row) => row.card)

  const filterKey = `${filter.query}|${JSON.stringify(filter.selected)}|${selectedAgentId ?? ''}`
  const [expanded, setExpanded] = useState<Partial<Record<BoardColumn, boolean>>>({})
  const [followedFilterKey, setFollowedFilterKey] = useState(filterKey)
  if (filterKey !== followedFilterKey) {
    setFollowedFilterKey(filterKey)
    setExpanded({})
  }

  const columnOf = (cardId: string): string | undefined => cards.find((card) => card.id === cardId)?.column
  useBoardKeyboard(containerRef)
  useKeepCardFocus({ containerRef, columnOf, version: cards, ...(onCardMoved ? { onMoved: onCardMoved } : {}) })

  const requesterSelect: FilterSelect = {
    label: 'Requester',
    value: filter.selected.requester?.[0] ?? '',
    options: [{ value: '', label: 'Everyone' }, ...requesterOptions.map(([value, label]) => ({ value, label }))],
    onChange: (value) => filter.setOnly('requester', value || null),
  }

  const setView = (next: 'board' | 'list') => navigate(withParam('view', next === 'board' ? null : next), { replace: true, scroll: false })
  const setGroup = (next: 'none' | 'agent' | 'requester') => navigate(withParam('group', next === 'none' ? null : next), { replace: true, scroll: false })

  function withParam(param: string, value: string | null): string {
    const next = new URLSearchParams(window.location.search)
    if (value) next.set(param, value)
    else next.delete(param)
    const qs = next.toString()
    return `${window.location.pathname}${qs ? `?${qs}` : ''}`
  }

  const selectedAgentName = selectedAgentId ? (agentNames[selectedAgentId] ?? 'this agent') : null
  const nothingAtAll = cards.length === 0

  if (nothingAtAll) {
    const windowNote = board.window === 'TODAY' ? 'No goals today yet.' : `No goals in the last ${Math.max(1, Math.round(board.windowMinutes / 60))} hours. Start one from Chat, or with New goal.`
    return <p className="caption muted">{windowNote}</p>
  }

  return (
    <div ref={containerRef}>
      <FilterBar
        searchLabel="Search goals"
        query={filter.query}
        onQueryChange={filter.setQuery}
        placeholder="Goal, agent or requester"
        facets={[
          {
            param: 'source',
            label: 'Source',
            options: (['manual', 'chat', 'schedule'] as const).map((value) => ({
              value,
              label: goalSourceLabel(value).label,
              count: filter.counts.source?.[value] ?? 0,
            })),
            selected: filter.selected.source ?? [],
            onToggle: (value) => filter.toggle('source', value),
          },
          {
            param: 'status',
            label: 'Status',
            options: STATUS_OPTIONS.map((value) => ({
              value,
              label: STATUS_LABEL[value],
              count: filter.counts.status?.[value] ?? 0,
            })),
            selected: filter.selected.status ?? [],
            onToggle: (value) => filter.toggle('status', value),
          },
        ]}
        selects={[
          {
            label: 'Agent',
            value: selectedAgentId ?? '',
            options: [{ value: '', label: 'Every agent' }, ...board.agents.map((agent) => ({ value: agent.id, label: agent.name }))],
            onChange: (value) => onSelectAgent(value || null),
          },
          requesterSelect,
        ]}
        shown={visible.length}
        total={cards.length}
        scopeNote={selectedAgentId ? `Filtered to ${selectedAgentName}.` : undefined}
        active={filter.active || Boolean(selectedAgentId)}
        onClear={() => {
          filter.clear()
          onSelectAgent(null)
        }}
      />

      <div className="row" style={{ justifyContent: 'space-between', flexWrap: 'wrap', gap: 'var(--space-3)', margin: 'var(--space-4) 0' }}>
        <div className="orc-segmented" role="group" aria-label="Board or list view">
          <button type="button" aria-pressed={view === 'board'} onClick={() => setView('board')}>
            Board
          </button>
          <button type="button" aria-pressed={view === 'list'} onClick={() => setView('list')}>
            List
          </button>
        </div>
        {view === 'list' && (
          <Select label="Group by" value={group} onChange={(event) => setGroup(event.target.value as 'none' | 'agent' | 'requester')}>
            <option value="none">None</option>
            <option value="agent">Agent</option>
            <option value="requester">Requester</option>
          </Select>
        )}
      </div>

      {visible.length === 0 ? (
        <FilterEmpty
          onClear={() => {
            filter.clear()
            onSelectAgent(null)
          }}
          what="goals"
        />
      ) : view === 'list' ? (
        <BoardList cards={visible} board={board} group={group} onOpenGoal={onOpenGoal} />
      ) : (
        <div className="orc-columns">
          {COLUMNS.map((column) => {
            const columnCards = visible.filter((card) => card.column === column)
            const isExpanded = expanded[column] ?? false
            const shown = isExpanded ? columnCards : columnCards.slice(0, CARDS_PER_COLUMN)
            return (
              <div key={column} className="orc-column">
                <div className="orc-column-head">
                  <h3 className="section-heading">{columnTitle(column, board.window, board.windowMinutes)}</h3>
                  <span className="caption tabular">{formatCount(columnCards.length)}</span>
                </div>
                {columnCards.length === 0 ? (
                  <p className="caption muted">Nothing here.</p>
                ) : (
                  <div className="stack" style={{ gap: 'var(--space-3)' }}>
                    {shown.map((card) => (
                      <BoardGoalCard
                        key={card.id}
                        card={card}
                        now={now}
                        agentName={card.task?.agentId ? (agentNames[card.task.agentId] ?? null) : null}
                        requester={requesterLabel(card.goal, members, currentUserId)}
                        current={openGoalId === card.id}
                        changed={changedIds.has(card.id)}
                        onOpen={() => onOpenGoal(card.id)}
                      />
                    ))}
                    {!isExpanded && columnCards.length > CARDS_PER_COLUMN && (
                      <button type="button" className="link" onClick={() => setExpanded((current) => ({ ...current, [column]: true }))}>
                        Show all {columnCards.length}
                      </button>
                    )}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

function BoardGoalCard({
  card,
  now,
  agentName,
  requester,
  current,
  changed,
  onOpen,
}: {
  card: BoardCard
  now: number
  agentName: string | null
  requester: string
  current: boolean
  changed: boolean
  onOpen: () => void
}) {
  const { goal, task } = card
  const sourceEntry = goalSourceLabel(goal.source)
  const step = stepLabel(goal, task)
  const timing = timingFor(card, now)
  const failure = failureCaption(card)

  return (
    <button
      type="button"
      className="card card-interactive orc-card"
      data-card-id={card.id}
      data-changed={changed || undefined}
      aria-current={current || undefined}
      onClick={onOpen}
    >
      <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-2)', flexWrap: 'wrap' }}>
        <span className="caption" style={{ fontWeight: 'var(--weight-strong)' }} title={goal.title}>
          {truncateWords(goal.title, 60)}
        </span>
        {task ? <StatusTag kind="task" status={task.status} /> : <StatusTag kind="goal" status={goal.status} />}
      </div>

      <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap', margin: 'var(--space-2) 0' }}>
        <Tag tone={sourceEntry.tone}>{sourceEntry.label}</Tag>
        {agentName && <Tag title={agentName}>{agentName}</Tag>}
        {!agentName && task?.agentId && <Tag title="Unknown agent">Unknown agent</Tag>}
      </div>

      <p className="caption muted">{requester}</p>

      {step && (
        <div className="row" style={{ gap: 'var(--space-2)', alignItems: 'center', marginTop: 'var(--space-2)' }}>
          <span className="caption">{step}</span>
          <ChainDots goal={goal} />
        </div>
      )}

      {card.column === 'queued' && card.reason && (
        <p className="caption muted" style={{ marginTop: 'var(--space-2)' }}>
          {queueReasonText(card.reason)}
          {card.queuePosition != null && ` · #${card.queuePosition + 1} in the queue`}
        </p>
      )}

      {failure && (
        <p className="caption" style={{ color: 'var(--danger)', marginTop: 'var(--space-2)' }}>
          {failure}
        </p>
      )}

      <div className="row" style={{ justifyContent: 'space-between', marginTop: 'var(--space-3)' }}>
        <span className="caption tabular">{timing}</span>
        {task?.cost != null && task.cost > 0 && <span className="caption tabular">{formatMoney(task.cost)}</span>}
      </div>
    </button>
  )
}

function failureCaption(card: BoardCard): string | null {
  if (card.statusKey !== 'failed' || !card.task?.failureReason) return null
  const ordered = [...card.goal.tasks].sort((a, b) => a.position - b.position)
  const index = ordered.findIndex((candidate) => candidate.id === card.task!.id)
  const step = index < 0 ? 1 : index + 1
  return `Stopped at step ${step}: ${truncateWords(card.task.failureReason, 90)}`
}

function timingFor(card: BoardCard, now: number): string {
  const { task } = card
  if (!task) return ''
  if (task.status.toLowerCase() === 'running' && task.startedAt) {
    return `Running ${formatElapsed(task.startedAt, null, now)}`
  }
  if (task.startedAt && task.completedAt) return formatElapsed(task.startedAt, task.completedAt, now)
  if (task.startedAt) return formatRunElapsed({ status: task.status, startedAt: task.startedAt, completedAt: task.completedAt }, now)
  return 'Not started'
}

function ChainDots({ goal }: { goal: BoardCard['goal'] }) {
  const tasks = [...goal.tasks].sort((a, b) => a.position - b.position)
  if (tasks.length <= 1) return null
  return (
    <span className="orc-chain-dots" aria-hidden="true">
      {tasks.map((task) => (
        <span
          key={task.id}
          className="orc-chain-dot"
          data-done={task.status.toLowerCase() === 'completed' || undefined}
        />
      ))}
    </span>
  )
}
