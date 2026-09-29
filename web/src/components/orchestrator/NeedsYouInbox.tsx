import { useState } from 'react'
import { Button, ConfirmDialog, Tag } from '../ui'
import { describeApiError } from '../../lib/api'
import { canRetryGoal } from '../../lib/goals'
import { formatAgo, truncateWords } from '../../lib/format'
import type { Board } from '../../lib/queries'
import { useMemberNames, useRetryGoal, useSetAgentStatus } from '../../lib/queries'
import { closesIn } from '../../lib/questions'
import { readStored, writeStored } from '../../lib/persist'
import { can, profile } from '../../lib/session'
import { useToast } from '../../lib/toast'
import { useNow } from '../../lib/useNow'
import { buildNeedsYou } from './needsYou'
import type { NeedsYouItem } from './needsYou'

const SCOPE_KEY = 'orc.needs.scope'
const VISIBLE_ROWS = 5

type Scope = 'forMe' | 'everyone'

const isScope = (value: unknown): value is Scope => value === 'forMe' || value === 'everyone'

function agentNameOf(board: Board, agentId: string | null): string {
  if (!agentId) return 'The agent'
  return board.agents.find((agent) => agent.id === agentId)?.name ?? 'An agent'
}

function requesterName(userId: string | null, me: string | null, nameOf: (id: string) => string): string {
  if (!userId) return 'Unknown'
  if (me != null && userId === me) return 'You'
  return nameOf(userId)
}

/** The retry step and the agent it belongs to, for the confirmation - Tasks.tsx uses this copy too. */
function retryStepInfo(item: Extract<NeedsYouItem, { kind: 'failed' }>, board: Board): { step: number; agentName: string } {
  const goal = board.failedToday.find((candidate) => candidate.id === item.goalId)
  const tasks = goal ? [...goal.tasks].sort((a, b) => a.position - b.position) : []
  const retryTask = tasks.find((task) => ['failed', 'cancelled', 'skipped'].includes(task.status.toLowerCase()))
  const agentName = (retryTask?.agentId && agentNameOf(board, retryTask.agentId)) || 'The agent'
  return { step: (retryTask?.position ?? 0) + 1, agentName }
}

function RowAction({
  item,
  board,
  me,
  onOpenGoal,
  onOpenRun,
}: {
  item: NeedsYouItem
  board: Board
  me: string | null
  onOpenGoal: (goalId: string, focus?: 'question' | 'approval') => void
  onOpenRun: (runId: string, focus?: 'question' | 'approval') => void
}) {
  const toast = useToast()
  const retryGoal = useRetryGoal()
  const resumeAgent = useSetAgentStatus()
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const open = (focus?: 'question' | 'approval') => {
    if (item.goalId) onOpenGoal(item.goalId, focus)
    else if ('runId' in item) onOpenRun(item.runId, focus)
  }

  if (item.kind === 'question') {
    return (
      <Button variant="outline" onClick={() => open('question')}>
        {item.canAnswer ? 'Answer' : 'View'}
      </Button>
    )
  }

  if (item.kind === 'approval') {
    return (
      <Button variant="outline" onClick={() => open('approval')}>
        {item.canDecide ? 'Review' : 'View'}
      </Button>
    )
  }

  if (item.kind === 'held') {
    if (!can('agent:update')) {
      return (
        <Button variant="outline" onClick={() => open()}>
          Open
        </Button>
      )
    }
    return (
      <Button
        variant="outline"
        loading={resumeAgent.isPending}
        onClick={async () => {
          if (!item.agentId) return
          try {
            await resumeAgent.mutateAsync({ id: item.agentId, action: 'resume' })
            toast.success(`${agentNameOf(board, item.agentId)} resumed`)
          } catch (thrown) {
            toast.error(describeApiError(thrown))
          }
        }}
      >
        Resume agent
      </Button>
    )
  }

  // Failed.
  const goalLike = { status: 'failed', requestedBy: item.requestedBy }
  if (!canRetryGoal(goalLike, me, can)) {
    return (
      <Button variant="outline" onClick={() => open()}>
        Open
      </Button>
    )
  }
  const { step, agentName } = retryStepInfo(item, board)
  return (
    <>
      <Button variant="outline" onClick={() => setConfirmOpen(true)}>
        Retry
      </Button>
      <ConfirmDialog
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={async () => {
          setError(null)
          try {
            await retryGoal.mutateAsync(item.goalId)
            toast.success('Trying again.')
            setConfirmOpen(false)
          } catch (thrown) {
            setError(describeApiError(thrown))
          }
        }}
        eyebrow="Retry"
        title={`Try again from step ${step}?`}
        description={`Step ${step} (${agentName}) starts again from the beginning. Anything it already did before it stopped, such as a sent email, may happen again. Check its trace first.`}
        confirmLabel="Try again"
        tone="primary"
        loading={retryGoal.isPending}
        error={error}
      />
    </>
  )
}

function RowText({ item, board }: { item: NeedsYouItem; board: Board }) {
  if (item.kind === 'question') {
    const agentName = agentNameOf(board, item.agentId)
    return (
      <span className="orc-needs-text">
        <Tag>{item.header}</Tag> {agentName}: {truncateWords(item.text, 120)}
      </span>
    )
  }
  if (item.kind === 'approval') {
    return (
      <span className="orc-needs-text">
        {agentNameOf(board, item.agentId)}: {item.summary}
      </span>
    )
  }
  return (
    <span className="orc-needs-text">
      {agentNameOf(board, item.agentId)} · {item.title}
    </span>
  )
}

function RowCaption({ item, me, nameOf, now }: { item: NeedsYouItem; me: string | null; nameOf: (id: string) => string; now: number }) {
  const requester = requesterName(item.requestedBy, me, nameOf)
  const ago = `asked ${formatAgo(now - Date.parse(item.createdAt))}`
  if (item.kind === 'failed' || item.kind === 'held') {
    return (
      <p className="caption muted">
        {requester} · {ago}
      </p>
    )
  }
  return (
    <p className="caption muted">
      For: {item.title} · {requester} · {ago} · {closesIn(item.expiresAt, now)}
    </p>
  )
}

function kindTag(item: NeedsYouItem) {
  switch (item.kind) {
    case 'question':
      return <Tag tone="blue">Question</Tag>
    case 'approval':
      return <Tag tone="warning">Approval</Tag>
    case 'failed':
      return <Tag tone="danger">Failed</Tag>
    case 'held':
      return <Tag tone="neutral">Held</Tag>
  }
}

export function NeedsYouInbox({
  board,
  onOpenGoal,
  onOpenRun,
}: {
  board: Board
  onOpenGoal: (goalId: string, focus?: 'question' | 'approval') => void
  onOpenRun: (runId: string, focus?: 'question' | 'approval') => void
}) {
  const me = profile()?.userId ?? null
  const members = useMemberNames()
  const nameOf = (id: string) => members[id]?.displayName ?? id.slice(0, 8)
  const now = useNow(60_000)

  const [scope, setScopeState] = useState<Scope>(() => readStored(SCOPE_KEY, 'forMe', isScope))
  const setScope = (value: Scope) => {
    setScopeState(value)
    writeStored(SCOPE_KEY, value)
  }
  const [showAll, setShowAll] = useState(false)

  const everyone = buildNeedsYou(board, { me, scope: 'everyone' })
  const forMe = everyone.filter((item) => item.mine)
  const items = scope === 'forMe' ? forMe : everyone
  const visible = showAll ? items : items.slice(0, VISIBLE_ROWS)

  return (
    <section aria-labelledby="orc-needs-heading" className="stack" style={{ gap: 'var(--space-4)' }}>
      <div className="row" style={{ justifyContent: 'space-between', flexWrap: 'wrap', gap: 'var(--space-3)' }}>
        <div className="row" style={{ gap: 'var(--space-3)', alignItems: 'center' }}>
          <h2 id="orc-needs-heading" className="section-heading">
            Needs you
          </h2>
          <Tag>{items.length}</Tag>
        </div>
        <div className="orc-segmented" role="group" aria-label="Whose items to show">
          <button type="button" aria-pressed={scope === 'forMe'} onClick={() => setScope('forMe')}>
            For me
          </button>
          <button type="button" aria-pressed={scope === 'everyone'} onClick={() => setScope('everyone')}>
            Everyone
          </button>
        </div>
      </div>

      {items.length === 0 ? (
        <p className="caption muted">
          {scope === 'forMe' ? (
            everyone.length > 0 ? (
              <>
                Nothing you asked for is waiting on you. {everyone.length} items from other people are.{' '}
                <button type="button" className="link" onClick={() => setScope('everyone')}>
                  Show everyone
                </button>
              </>
            ) : (
              'Nothing you asked for is waiting on you.'
            )
          ) : (
            'Nothing needs anyone right now.'
          )}
        </p>
      ) : (
        <ul className="orc-needs" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {visible.map((item) => (
            <li key={`${item.kind}-${item.id}`} className="orc-needs-row">
              {kindTag(item)}
              <div>
                <RowText item={item} board={board} />
                <RowCaption item={item} me={me} nameOf={nameOf} now={now} />
              </div>
              <RowAction item={item} board={board} me={me} onOpenGoal={onOpenGoal} onOpenRun={onOpenRun} />
            </li>
          ))}
        </ul>
      )}

      {!showAll && items.length > VISIBLE_ROWS && (
        <Button variant="quiet" onClick={() => setShowAll(true)}>
          Show all {items.length}
        </Button>
      )}
    </section>
  )
}
