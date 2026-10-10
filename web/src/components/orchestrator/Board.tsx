// @find: orchestrator board, goals board, kanban, queued working needs you finished, goal cards, board view, list view, filters, search goals, open goal, live updates, OrchestratorBoard
// @what: The Orchestrator board of goal cards in columns, with toolbar, list view, live change highlighting and keyboard support.
// @flow: Uses BoardToolbar, BoardList, layout.ts, boardChanges, useBoardKeyboard, useKeepCardFocus; opens GoalSheet
import { useId, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Button, EmptyState, StatusTag } from '../ui'
// Imported from its own module, not the ../ui barrel: this is used from Orchestrator, a
// lazy-loaded route, and the barrel is also part of the main bundle, so going through it created
// a circular chunk dependency (Rollup warned of a "broken execution order").
import { FilterEmpty } from '../ui/FilterBar'
import { EmptyIcon } from '../ui/QueryState'
import { BoardList } from './BoardList'
import { BoardToolbar } from './BoardToolbar'
import type { ListGroup } from './BoardToolbar'
import { formatCount, formatElapsed, formatMoney, formatRunElapsed, truncateWords } from '../../lib/format'
import { goalSourceLabel } from '../../lib/labels'
import type { Board as BoardData } from '../../lib/queries'
import { useRouter } from '../../lib/router'
import { usePersistentState } from '../../lib/persist'
import { profile } from '../../lib/session'
import { useListFilter } from '../../lib/useListFilter'
import { useNow } from '../../lib/useNow'
import { useBoardKeyboard } from './useBoardKeyboard'
import { useKeepCardFocus } from './useKeepCardFocus'
import { useMemberDirectory } from './useMemberDirectory'
import { cardMetaParts, columnTitle, queueReasonText, showsStepProgress, stepLabel } from './layout'
import type { BoardCard, BoardColumn, CardStatusKey } from './layout'
import { requesterLabel } from './shared'

/*
 * Every goal in flight, and everything finished in the window, sorted into the column its current
 * step is in (B2.5). One card per goal (D1): the sheet stays keyed to the same card through a
 * column move, because `card.id` is the goal's own id, never the task's.
 */

/** The columns still in flight. Finished sits below them, folded away until asked for. */
const ACTIVE_COLUMNS: BoardColumn[] = ['queued', 'working', 'needs_you']
const isBoolean = (value: unknown): value is boolean => typeof value === 'boolean'
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

// @find: orchestrator board component, goals board, board or list
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
  onNewGoal,
  doneToday,
}: {
  board: BoardData
  cards: BoardCard[]
  view: 'board' | 'list'
  group: ListGroup
  selectedAgentId: string | null
  onSelectAgent: (agentId: string | null) => void
  onOpenGoal: (goalId: string) => void
  changedIds: ReadonlySet<string>
  onCardMoved?: (cardId: string, column: string) => void
  /** Offered from the empty board, when the viewer may start a goal. */
  onNewGoal?: (() => void) | undefined
  /** "Done today", counted from local midnight: pressing it widens the window to today. */
  doneToday?: { count: number; pressed: boolean; onToggle: () => void }
}) {
  const { search, navigate } = useRouter()
  const now = useNow(1_000)
  const directory = useMemberDirectory()
  const currentUserId = profile()?.userId ?? null
  const openGoalId = search.get('goal')
  const containerRef = useRef<HTMLDivElement>(null)

  const agentNames = useMemo(() => Object.fromEntries(board.agents.map((agent) => [agent.id, agent.name])), [board.agents])

  const rows = useMemo(
    () =>
      cards.map((card) => ({
        card,
        agentName: card.task?.agentId ? (agentNames[card.task.agentId] ?? '') : '',
        requester: requesterLabel(card.goal, directory, currentUserId),
      })),
    [cards, agentNames, directory, currentUserId],
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

  const [showEmpty, setShowEmpty] = usePersistentState('orc.board.showEmpty', false, isBoolean)
  const [finishedOpenStored, setFinishedOpen] = usePersistentState('orc.board.finishedOpen', false, isBoolean)
  // A search or filter shows every goal it matched, the finished ones included, without a click.
  const filtering = filter.active || Boolean(selectedAgentId)
  const finishedOpen = finishedOpenStored || filtering

  const columnOf = (cardId: string): string | undefined => cards.find((card) => card.id === cardId)?.column
  useBoardKeyboard(containerRef)
  useKeepCardFocus({ containerRef, columnOf, version: cards, ...(onCardMoved ? { onMoved: onCardMoved } : {}) })

  const setView = (next: 'board' | 'list') => navigate(withParam('view', next === 'board' ? null : next), { replace: true, scroll: false })
  const setGroup = (next: ListGroup) => navigate(withParam('group', next === 'none' ? null : next), { replace: true, scroll: false })

  function withParam(param: string, value: string | null): string {
    return withParams({ [param]: value })
  }

  /** Reads the live URL, not the rendered one, so a change made earlier in the same event lands. */
  function withParams(changes: Record<string, string | null>): string {
    const next = new URLSearchParams(window.location.search)
    for (const [param, value] of Object.entries(changes)) {
      if (value) next.set(param, value)
      else next.delete(param)
    }
    const qs = next.toString().replace(/%2C/gi, ',')
    return `${window.location.pathname}${qs ? `?${qs}` : ''}`
  }

  // One navigation for the search, every facet and the agent together: clearing them one at a
  // time let the agent's own update, built from the rendered URL, put the facets straight back.
  const clearAll = () =>
    navigate(withParams({ q: null, source: null, status: null, requester: null, agent: null }), { replace: true, scroll: false })

  if (cards.length === 0) {
    const hours = Math.max(1, Math.round(board.windowMinutes / 60))
    const when = board.window === 'TODAY' ? 'today' : hours === 1 ? 'in the last hour' : `in the last ${hours} hours`
    return (
      <EmptyState
        icon={<EmptyIcon kind="task" />}
        title={`No goals ${when}`}
        body="A goal is a piece of work you hand to the workforce. Start one here, or ask for it in Chat, and it appears on this board as it moves along."
        titleAs="h3"
        action={onNewGoal ? <Button onClick={onNewGoal}>New goal</Button> : undefined}
      />
    )
  }

  return (
    <div className="orc-goals">
      <BoardToolbar
        query={filter.query}
        onQueryChange={filter.setQuery}
        statusOptions={STATUS_OPTIONS.map((value) => ({ value, label: STATUS_LABEL[value], count: filter.counts.status?.[value] ?? 0 }))}
        statusSelected={filter.selected.status ?? []}
        onToggleStatus={(value) => filter.toggle('status', value)}
        sourceOptions={(['manual', 'chat', 'schedule'] as const).map((value) => ({ value, label: goalSourceLabel(value).label }))}
        sourceSelected={filter.selected.source ?? []}
        onToggleSource={(value) => filter.toggle('source', value)}
        agentOptions={board.agents.map((agent) => ({ value: agent.id, label: agent.name }))}
        agentSelected={selectedAgentId}
        onSelectAgent={onSelectAgent}
        requesterOptions={requesterOptions.map(([value, label]) => ({ value, label }))}
        requesterSelected={filter.selected.requester?.[0] ?? null}
        onSelectRequester={(value) => filter.setOnly('requester', value)}
        shown={visible.length}
        total={cards.length}
        active={filter.active || Boolean(selectedAgentId)}
        onClear={clearAll}
        view={view}
        onSetView={setView}
        group={group}
        onSetGroup={setGroup}
        {...(doneToday
          ? { extraStatus: { label: 'Done today', count: doneToday.count, pressed: doneToday.pressed, onToggle: doneToday.onToggle } }
          : {})}
        {...(view === 'board' ? { showEmpty: { value: showEmpty, onChange: setShowEmpty } } : {})}
      />

      <div ref={containerRef}>
        {visible.length === 0 ? (
          <FilterEmpty onClear={clearAll} what="goals" />
        ) : view === 'list' ? (
          <ListWithFinished
            visible={visible}
            finishedOpen={finishedOpen}
            onToggleFinished={filtering ? undefined : () => setFinishedOpen(!finishedOpenStored)}
            title={columnTitle('finished', board.window, board.windowMinutes)}
            renderList={(listCards) => <BoardList cards={listCards} board={board} group={group} onOpenGoal={onOpenGoal} />}
          />
        ) : (
          <BoardColumns
            visible={visible}
            showEmpty={showEmpty}
            finishedOpen={finishedOpen}
            onToggleFinished={filtering ? undefined : () => setFinishedOpen(!finishedOpenStored)}
            renderColumn={(column, columnCards) => {
              const isExpanded = expanded[column] ?? false
              const shown = isExpanded ? columnCards : columnCards.slice(0, CARDS_PER_COLUMN)
              return (
                <>
                  {shown.map((card) => (
                    <BoardGoalCard
                      key={card.id}
                      card={card}
                      now={now}
                      agentName={card.task?.agentId ? (agentNames[card.task.agentId] ?? 'Unknown agent') : null}
                      requester={requesterLabel(card.goal, directory, currentUserId)}
                      current={openGoalId === card.id}
                      changed={changedIds.has(card.id)}
                      onOpen={() => onOpenGoal(card.id)}
                    />
                  ))}
                  {!isExpanded && columnCards.length > CARDS_PER_COLUMN && (
                    <button type="button" className="link orc-column-more" onClick={() => setExpanded((current) => ({ ...current, [column]: true }))}>
                      Show all {columnCards.length}
                    </button>
                  )}
                </>
              )
            }}
            titleOf={(column) => columnTitle(column, board.window, board.windowMinutes)}
          />
        )}
      </div>
    </div>
  )
}

/**
 * The board view: the columns still in flight side by side (an empty one hidden unless "Show
 * empty" is on), then Finished as one folded row beneath them, closed by default so the goals that
 * still need something stay above the fold.
 */
function BoardColumns({
  visible,
  showEmpty,
  finishedOpen,
  onToggleFinished,
  renderColumn,
  titleOf,
}: {
  visible: BoardCard[]
  showEmpty: boolean
  finishedOpen: boolean
  /** Absent while a filter holds Finished open. */
  onToggleFinished: (() => void) | undefined
  renderColumn: (column: BoardColumn, cards: BoardCard[]) => ReactNode
  titleOf: (column: BoardColumn) => string
}) {
  const active = ACTIVE_COLUMNS.map((column) => ({ column, cards: visible.filter((card) => card.column === column) }))
  const shownColumns = active.filter((entry) => showEmpty || entry.cards.length > 0)
  const finished = visible.filter((card) => card.column === 'finished')

  return (
    <div className="orc-board">
      {shownColumns.length === 0 ? (
        <p className="orc-board-idle caption muted">Nothing queued, working or waiting on anyone right now.</p>
      ) : (
        <div className="orc-columns" data-cols={shownColumns.length}>
          {shownColumns.map(({ column, cards: columnCards }) => {
            const title = titleOf(column)
            return (
              <section key={column} className="orc-column" aria-label={title} data-empty={columnCards.length === 0 || undefined}>
                <div className="orc-column-head">
                  <h3 className="orc-column-title">{title}</h3>
                  <span className="orc-column-count tabular">{formatCount(columnCards.length)}</span>
                </div>
                {columnCards.length === 0 ? (
                  <p className="orc-column-empty">No goals</p>
                ) : (
                  <div className="orc-column-cards">{renderColumn(column, columnCards)}</div>
                )}
              </section>
            )
          })}
        </div>
      )}

      {(finished.length > 0 || showEmpty) && (
        <FinishedDisclosure title={titleOf('finished')} count={finished.length} open={finishedOpen} onToggle={onToggleFinished}>
          {finished.length === 0 ? <p className="orc-column-empty">No goals</p> : <div className="orc-finished-cards">{renderColumn('finished', finished)}</div>}
        </FinishedDisclosure>
      )}
    </div>
  )
}

/** The list view, with the finished goals folded under their own heading the same way. */
function ListWithFinished({
  visible,
  finishedOpen,
  onToggleFinished,
  title,
  renderList,
}: {
  visible: BoardCard[]
  finishedOpen: boolean
  onToggleFinished: (() => void) | undefined
  title: string
  renderList: (cards: BoardCard[]) => ReactNode
}) {
  // While a filter holds Finished open, the list stays one list, sorted and grouped as a whole.
  if (!onToggleFinished) return <>{renderList(visible)}</>
  const active = visible.filter((card) => card.column !== 'finished')
  const finished = visible.filter((card) => card.column === 'finished')
  return (
    <div className="orc-board">
      {active.length > 0 ? renderList(active) : <p className="orc-board-idle caption muted">Nothing queued, working or waiting on anyone right now.</p>}
      {finished.length > 0 && (
        <FinishedDisclosure title={title} count={finished.length} open={finishedOpen} onToggle={onToggleFinished}>
          {renderList(finished)}
        </FinishedDisclosure>
      )}
    </div>
  )
}

/** Finished goals, folded to one heading row by default; a filter holds it open without a toggle. */
function FinishedDisclosure({
  title,
  count,
  open,
  onToggle,
  children,
}: {
  title: string
  count: number
  open: boolean
  onToggle: (() => void) | undefined
  children: ReactNode
}) {
  const bodyId = useId()
  return (
    <section className="orc-finished" aria-label={title} data-open={open || undefined}>
      <h3 className="orc-finished-heading">
        {onToggle ? (
          <button type="button" className="orc-finished-toggle" aria-expanded={open} aria-controls={open ? bodyId : undefined} onClick={onToggle}>
            <svg className="collapsible-chevron" data-open={open} width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
              <path d="M4 2.5 8 6l-4 3.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            <span className="orc-column-title">{title}</span>
            <span className="orc-column-count tabular">{formatCount(count)}</span>
          </button>
        ) : (
          <span className="orc-finished-static">
            <span className="orc-column-title">{title}</span>
            <span className="orc-column-count tabular">{formatCount(count)}</span>
          </span>
        )}
      </h3>
      {open && (
        <div id={bodyId} className="orc-finished-body">
          {children}
        </div>
      )}
    </section>
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
  const failure = failureCaption(card)
  const meta = cardMetaParts({
    agent: agentName,
    source: goalSourceLabel(goal.source).label,
    requester,
    timing: timingFor(card, now),
    cost: task?.cost != null && task.cost > 0 ? formatMoney(task.cost) : null,
  })
  const metaLine = meta.join(' · ')

  return (
    <button
      type="button"
      className="card card-interactive orc-card"
      data-card-id={card.id}
      data-changed={changed || undefined}
      aria-current={current || undefined}
      onClick={onOpen}
    >
      <span className="orc-card-head">
        <span className="orc-card-title" title={goal.title}>
          {goal.title}
        </span>
        <span className="orc-card-status">
          {task ? <StatusTag kind="task" status={task.status} /> : <StatusTag kind="goal" status={goal.status} />}
        </span>
      </span>

      <span className="orc-card-meta" title={metaLine}>
        {metaLine}
      </span>

      {showsStepProgress(goal) && (
        <span className="orc-card-steps">
          <span>{stepLabel(goal, task)}</span>
          <ChainDots goal={goal} />
        </span>
      )}

      {card.column === 'queued' && card.reason && (
        <span className="orc-card-note">
          {queueReasonText(card.reason)}
          {card.queuePosition != null && ` · #${card.queuePosition + 1} in the queue`}
        </span>
      )}

      {failure && <span className="orc-card-note orc-card-failure">{failure}</span>}
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
  return ''
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
