import { useState } from 'react'
import { Button, ConfirmDialog, StatusTag, Tag } from '../ui'
import { AgentAvatar } from '../ui/AgentAvatar'
import { describeApiError } from '../../lib/api'
import { formatCount, truncateWords } from '../../lib/format'
import { useSetAgentStatus } from '../../lib/queries'
import type { Board, BoardAgent } from '../../lib/queries'
import { can } from '../../lib/session'
import { useToast } from '../../lib/toast'
import { nodeStatus, nodeStatusLabel } from './FlowMap'
import { currentTaskFor } from './shared'

/*
 * Every agent, as a list (B2.9): who is doing what right now, how much is queued behind them, and
 * the one control an orchestrator actually needs in the moment - hold an agent's work, or let it
 * carry on - plus the pair of "everyone at once" actions above the list.
 */

export function AgentsStrip({ board, onOpenGoal }: { board: Board; onOpenGoal: (goalId: string) => void }) {
  const toast = useToast()
  const setStatus = useSetAgentStatus()
  const canUpdate = can('agent:update')
  const canCreate = can('task:create')
  const sorted = [...board.agents].sort((a, b) => a.name.localeCompare(b.name))
  const [bulkOpen, setBulkOpen] = useState<'pause' | 'resume' | null>(null)
  const [includeGeneral, setIncludeGeneral] = useState(true)
  const [bulkError, setBulkError] = useState<string | null>(null)

  const runBulk = async (action: 'pause' | 'resume') => {
    setBulkError(null)
    const targets = sorted.filter((agent) => (action === 'pause' && includeGeneral ? true : !agent.fallback))
    const results = await Promise.allSettled(targets.map((agent) => setStatus.mutateAsync({ id: agent.id, action })))
    const failed = results.filter((result) => result.status === 'rejected').length
    const succeeded = results.length - failed
    setBulkOpen(null)
    const verb = action === 'pause' ? 'Paused' : 'Resumed'
    toast.info(failed > 0 ? `${verb} ${formatCount(succeeded)} agents. ${formatCount(failed)} could not be ${action === 'pause' ? 'paused' : 'resumed'}.` : `${verb} ${formatCount(succeeded)} agents.`)
  }

  return (
    <div className="stack" style={{ gap: 'var(--space-4)' }}>
      {canUpdate && (
        <div className="row" style={{ gap: 'var(--space-3)' }}>
          <Button variant="outline" onClick={() => setBulkOpen('pause')}>
            Pause all
          </Button>
          <Button variant="outline" onClick={() => setBulkOpen('resume')}>
            Resume all
          </Button>
        </div>
      )}

      <ul className="orc-agents-list" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
        {sorted.map((agent) => (
          <AgentRow key={agent.id} agent={agent} board={board} canUpdate={canUpdate} onOpenGoal={onOpenGoal} />
        ))}
      </ul>

      <ConfirmDialog
        open={bulkOpen === 'pause'}
        onClose={() => setBulkOpen(null)}
        onConfirm={() => runBulk('pause')}
        eyebrow="Pause all"
        title="Pause every agent?"
        description="Queued work waits until an agent is resumed. Work already running is not stopped."
        confirmLabel="Pause all"
        tone="danger"
        loading={setStatus.isPending}
        error={bulkError}
      >
        {canCreate && (
          <label className="question-option" style={{ marginTop: 'var(--space-3)' }}>
            <input type="checkbox" checked={includeGeneral} onChange={(event) => setIncludeGeneral(event.target.checked)} />
            <span className="question-option-label">Include General Employee</span>
          </label>
        )}
      </ConfirmDialog>

      <ConfirmDialog
        open={bulkOpen === 'resume'}
        onClose={() => setBulkOpen(null)}
        onConfirm={() => runBulk('resume')}
        eyebrow="Resume all"
        title="Resume every paused agent?"
        description="Each paused agent picks its queued work back up."
        confirmLabel="Resume all"
        tone="primary"
        loading={setStatus.isPending}
        error={bulkError}
      />
    </div>
  )
}

function AgentRow({
  agent,
  board,
  canUpdate,
  onOpenGoal,
}: {
  agent: BoardAgent
  board: Board
  canUpdate: boolean
  onOpenGoal: (goalId: string) => void
}) {
  const toast = useToast()
  const [error, setError] = useState<string | null>(null)
  const setStatus = useSetAgentStatus()
  const paused = agent.status === 'paused'
  const status = nodeStatus(agent)
  const work = currentTaskFor(board, agent.id)

  const toggle = async () => {
    setError(null)
    try {
      await setStatus.mutateAsync({ id: agent.id, action: paused ? 'resume' : 'pause' })
      toast.success(`${agent.name} ${paused ? 'resumed' : 'paused'}`)
    } catch (thrown) {
      setError(describeApiError(thrown))
    }
  }

  return (
    <li className="orc-agent-row">
      <div className="row" style={{ gap: 'var(--space-3)', alignItems: 'center' }}>
        <AgentAvatar name={agent.name} category={agent.category} fallback={agent.fallback} size="sm" />
        <span style={{ fontWeight: 'var(--weight-strong)' }} title={agent.name}>
          {agent.name}
        </span>
        {agent.fallback && <Tag>Default</Tag>}
        <StatusTag kind="agent" status={agent.status} />
        <span className="caption muted">{nodeStatusLabel(status)}</span>
      </div>

      <div className="row" style={{ gap: 'var(--space-3)', alignItems: 'center', flexWrap: 'wrap', marginTop: 'var(--space-2)' }}>
        {work ? (
          <button type="button" className="link" onClick={() => onOpenGoal(work.goal.id)}>
            {truncateWords(work.task.title, 60)}
          </button>
        ) : (
          <span className="caption muted">Nothing running</span>
        )}
        <span className="caption muted">{formatCount(agent.queued)} queued</span>
      </div>

      <div className="row" style={{ gap: 'var(--space-3)', alignItems: 'center', marginTop: 'var(--space-2)' }}>
        {canUpdate && agent.status !== 'retired' && (
          <Button variant="outline" loading={setStatus.isPending} onClick={() => void toggle()}>
            {paused ? 'Resume' : 'Pause'}
          </Button>
        )}
        <a className="link" href={`/agents/${agent.id}`}>
          Open agent
        </a>
      </div>

      {error && (
        <p className="caption" style={{ color: 'var(--danger)', marginTop: 'var(--space-1)' }}>
          {error}
        </p>
      )}
    </li>
  )
}
