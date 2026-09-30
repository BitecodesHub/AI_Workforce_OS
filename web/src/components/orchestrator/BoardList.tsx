import { useMemo } from 'react'
import { DataTable, StatusTag, Time } from '../ui'
import type { Column } from '../ui'
import { formatElapsed, formatMoney, formatRunElapsed } from '../../lib/format'
import type { Board } from '../../lib/queries'
import { profile } from '../../lib/session'
import { useNow } from '../../lib/useNow'
import { stepLabel } from './layout'
import type { BoardCard } from './layout'
import { requesterLabel } from './shared'
import type { MemberDirectory } from './shared'
import { useMemberDirectory } from './useMemberDirectory'

/*
 * The board as a sortable table (B2.5), for a person who wants to scan every goal at once, or
 * order them by cost or duration rather than by column. Grouping splits the same rows into one
 * table per agent or requester, each headed by its own name.
 */

function timingValue(card: BoardCard, now: number): number | null {
  const task = card.task
  if (!task?.startedAt) return null
  const end = task.completedAt ? Date.parse(task.completedAt) : now
  return end - Date.parse(task.startedAt)
}

function timingLabel(card: BoardCard, now: number): string {
  const task = card.task
  if (!task?.startedAt) return 'Not started'
  if (task.completedAt) return formatElapsed(task.startedAt, task.completedAt, now)
  return formatRunElapsed({ status: task.status, startedAt: task.startedAt, completedAt: task.completedAt }, now)
}

function groupKeyFor(card: BoardCard, group: 'agent' | 'requester', agentNames: Record<string, string>, members: MemberDirectory, me: string | null): string {
  if (group === 'agent') return (card.task?.agentId && agentNames[card.task.agentId]) || 'No agent'
  return requesterLabel(card.goal, members, me)
}

export function BoardList({
  cards,
  board,
  group,
  onOpenGoal,
}: {
  cards: readonly BoardCard[]
  board: Board
  group: 'none' | 'agent' | 'requester'
  onOpenGoal: (goalId: string) => void
}) {
  const members = useMemberDirectory()
  const me = profile()?.userId ?? null
  const agentNames = useMemo(() => Object.fromEntries(board.agents.map((agent) => [agent.id, agent.name])), [board.agents])
  const now = useNow(10_000)

  const columns: Column<BoardCard>[] = [
    { key: 'goal', header: 'Goal', render: (card) => card.goal.title, sortValue: (card) => card.goal.title },
    {
      key: 'status',
      header: 'Status',
      render: (card) => (card.task ? <StatusTag kind="task" status={card.task.status} /> : <StatusTag kind="goal" status={card.goal.status} />),
      sortValue: (card) => card.task?.status ?? card.goal.status,
    },
    {
      key: 'agent',
      header: 'Agent',
      render: (card) => (card.task?.agentId ? (agentNames[card.task.agentId] ?? 'Unknown agent') : '—'),
      sortValue: (card) => (card.task?.agentId ? (agentNames[card.task.agentId] ?? '') : ''),
    },
    {
      key: 'requester',
      header: 'Requester',
      render: (card) => requesterLabel(card.goal, members, me),
      sortValue: (card) => requesterLabel(card.goal, members, me),
    },
    { key: 'step', header: 'Step', render: (card) => stepLabel(card.goal, card.task) },
    {
      key: 'started',
      header: 'Started',
      render: (card) => (card.task?.startedAt ? <Time iso={card.task.startedAt} /> : '—'),
      sortValue: (card) => (card.task?.startedAt ? Date.parse(card.task.startedAt) : null),
    },
    {
      key: 'duration',
      header: 'Duration',
      numeric: true,
      render: (card) => timingLabel(card, now),
      sortValue: (card) => timingValue(card, now),
    },
    {
      key: 'cost',
      header: 'Cost',
      numeric: true,
      render: (card) => (card.task?.cost ? formatMoney(card.task.cost) : '—'),
      sortValue: (card) => card.task?.cost ?? null,
    },
  ]

  const table = (rows: readonly BoardCard[], caption: string) => (
    <DataTable
      columns={columns}
      rows={[...rows]}
      getKey={(card) => card.id}
      caption={caption}
      getRowHref={(card) => `/orchestrator?goal=${card.goal.id}`}
      getRowLabel={(card) => card.goal.title}
      onRowOpen={(card) => onOpenGoal(card.goal.id)}
    />
  )

  if (group === 'none') return table(cards, 'The goals on the board')

  const groups = new Map<string, BoardCard[]>()
  for (const card of cards) {
    const key = groupKeyFor(card, group, agentNames, members, me)
    const list = groups.get(key)
    if (list) list.push(card)
    else groups.set(key, [card])
  }
  const sortedKeys = [...groups.keys()].sort((a, b) => a.localeCompare(b))

  return (
    <div className="stack" style={{ gap: 'var(--space-6)' }}>
      {sortedKeys.map((key) => (
        <div key={key}>
          <h3 className="section-heading" style={{ marginBottom: 'var(--space-3)' }}>
            {key}
          </h3>
          {table(groups.get(key)!, `Goals for ${key}`)}
        </div>
      ))}
    </div>
  )
}
