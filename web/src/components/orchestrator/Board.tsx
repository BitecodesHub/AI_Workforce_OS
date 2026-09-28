import { useMemo, useState } from 'react'
import { Card, FilterBar, FilterEmpty, StatusTag, Tag } from '../ui'
import type { FilterSelect } from '../ui'
import { formatCount, formatElapsed, formatMoney, formatRunElapsed, truncateWords } from '../../lib/format'
import { categoryTone, goalSourceLabel } from '../../lib/labels'
import { useAgentNames, useMemberNames } from '../../lib/queries'
import type { Agent, Board as BoardData } from '../../lib/queries'
import { profile } from '../../lib/session'
import { useListFilter } from '../../lib/useListFilter'
import { useNow } from '../../lib/useNow'
import { GoalDrawer } from './GoalDrawer'
import { COLUMN_TITLE, buildBoardCards, queueReasonText, stepLabel } from './layout'
import type { BoardCard, BoardColumn } from './layout'
import { requesterLabel } from './shared'

/*
 * Every goal in flight, and everything finished in the last two hours, sorted into the column its
 * current step is in. A card says who asked for it, where the ask came from, which agent has it
 * now, and how far along the chain it is; opening one shows the whole story.
 */

const COLUMNS: BoardColumn[] = ['queued', 'working', 'waiting', 'finished']

export function OrchestratorBoard({
  board,
  selectedAgentId,
  onSelectAgent,
}: {
  board: BoardData
  selectedAgentId: string | null
  onSelectAgent: (agentId: string | null) => void
}) {
  const now = useNow(1_000)
  const agents = useAgentNames()
  const members = useMemberNames()
  const currentUserId = profile()?.userId ?? null
  const [openCardId, setOpenCardId] = useState<string | null>(null)

  const cards = useMemo(() => buildBoardCards(board), [board])
  const rows = useMemo(
    () =>
      cards.map((card) => ({
        card,
        agentName: card.task?.agentId ? (agents[card.task.agentId]?.name ?? '') : '',
        requester: requesterLabel(card.goal, members, currentUserId),
      })),
    [cards, agents, members, currentUserId],
  )

  const filter = useListFilter({
    rows,
    text: (row) => [row.card.goal.title, row.agentName, row.requester].join(' '),
    facets: {
      source: (row) => row.card.goal.source,
      requester: (row) => row.card.goal.requestedBy ?? '',
    },
  })

  const requesterOptions = useMemo(() => {
    const seen = new Map<string, string>()
    for (const row of rows) {
      const id = row.card.goal.requestedBy ?? ''
      if (id && !seen.has(id)) seen.set(id, row.requester)
    }
    return [...seen.entries()].sort((a, b) => a[1].localeCompare(b[1]))
  }, [rows])

  const bySource = filter.filtered
  const byAgent = selectedAgentId ? bySource.filter((row) => row.card.task?.agentId === selectedAgentId) : bySource
  const visible = byAgent.map((row) => row.card)

  const columns: Record<BoardColumn, BoardCard[]> = { queued: [], working: [], waiting: [], finished: [] }
  for (const card of visible) columns[card.column].push(card)

  const requesterSelect: FilterSelect = {
    label: 'Requester',
    value: filter.selected.requester?.[0] ?? '',
    options: [{ value: '', label: 'Everyone' }, ...requesterOptions.map(([value, label]) => ({ value, label }))],
    onChange: (value) => filter.setOnly('requester', value || null),
  }

  const openCard = openCardId ? cards.find((card) => card.id === openCardId) : undefined
  const nothingAtAll = cards.length === 0

  if (nothingAtAll) {
    return <p className="caption muted">Nothing is moving right now, and nothing finished in the last two hours.</p>
  }

  return (
    <div>
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
        ]}
        selects={[requesterSelect]}
        shown={visible.length}
        total={cards.length}
        scopeNote={selectedAgentId ? 'Also filtered to the agent selected on the map above.' : undefined}
        active={filter.active || Boolean(selectedAgentId)}
        onClear={() => {
          filter.clear()
          onSelectAgent(null)
        }}
      />

      {visible.length === 0 ? (
        <FilterEmpty
          onClear={() => {
            filter.clear()
            onSelectAgent(null)
          }}
          what="goals"
        />
      ) : (
        <div className="orc-columns">
          {COLUMNS.map((column) => (
            <div key={column} className="orc-column">
              <div className="orc-column-head">
                <h3 className="section-heading">{COLUMN_TITLE[column]}</h3>
                <span className="caption tabular">{formatCount(columns[column].length)}</span>
              </div>
              {columns[column].length === 0 ? (
                <p className="caption muted">Nothing here.</p>
              ) : (
                <div className="stack" style={{ gap: 'var(--space-3)' }}>
                  {columns[column].map((card) => (
                    <BoardGoalCard
                      key={card.id}
                      card={card}
                      now={now}
                      agent={card.task?.agentId ? agents[card.task.agentId] : undefined}
                      requester={requesterLabel(card.goal, members, currentUserId)}
                      onOpen={() => setOpenCardId(card.id)}
                    />
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {openCard && <GoalDrawer card={openCard} onClose={() => setOpenCardId(null)} />}
    </div>
  )
}

function BoardGoalCard({
  card,
  now,
  agent,
  requester,
  onOpen,
}: {
  card: BoardCard
  now: number
  agent: Agent | undefined
  requester: string
  onOpen: () => void
}) {
  const { goal, task } = card
  const sourceEntry = goalSourceLabel(goal.source)
  const step = stepLabel(goal, task)
  const timing = timingFor(card, now)

  return (
    <Card onClick={onOpen} className="orc-card">
      <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-2)', flexWrap: 'wrap' }}>
        <span className="caption" style={{ fontWeight: 'var(--weight-strong)' }} title={goal.title}>
          {truncateWords(goal.title, 60)}
        </span>
        {task && <StatusTag kind="task" status={task.status} />}
      </div>

      <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap', margin: 'var(--space-2) 0' }}>
        <Tag tone={sourceEntry.tone}>{sourceEntry.label}</Tag>
        {agent && (
          <Tag tone={categoryTone(agent.category)} title={agent.name}>
            {agent.name}
          </Tag>
        )}
        {!agent && task?.agentId && <Tag title="Unknown agent">Unknown agent</Tag>}
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

      <div className="row" style={{ justifyContent: 'space-between', marginTop: 'var(--space-3)' }}>
        <span className="caption tabular">{timing}</span>
        {task?.cost != null && task.cost > 0 && <span className="caption tabular">{formatMoney(task.cost)}</span>}
      </div>
    </Card>
  )
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
