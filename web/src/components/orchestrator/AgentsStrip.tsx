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

const BUSY = new Set(['running', 'asking', 'waiting'])

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

  const busy = sorted.filter((agent) => BUSY.has(nodeStatus(agent))).length

  return (
    <div className="orc-agents">
      <div className="orc-agents-head">
        <h3 className="orc-panel-title">
          Agents <span className="orc-panel-count tabular">{formatCount(sorted.length)}</span>
        </h3>
        <span className="caption muted">{busy > 0 ? `${formatCount(busy)} busy` : 'All idle'}</span>
        {canUpdate && (
          <span className="orc-agents-bulk">
            <button type="button" className="link" onClick={() => setBulkOpen('pause')}>
              Pause all
            </button>
            <button type="button" className="link" onClick={() => setBulkOpen('resume')}>
              Resume all
            </button>
          </span>
        )}
      </div>

      <ul className="orc-agents-list">
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
    <li className="orc-agent-row" data-status={status}>
      <AgentAvatar name={agent.name} category={agent.category} fallback={agent.fallback} size="sm" />
      <div className="orc-agent-main">
        <p className="orc-agent-name">
          <a className="orc-agent-link" href={`/agents/${agent.id}`} title={`Open ${agent.name}`}>
            {agent.name}
          </a>
          {agent.fallback && <Tag>Default</Tag>}
          {(status === 'paused' || status === 'retired') && <StatusTag kind="agent" status={agent.status} />}
        </p>
        <p className="orc-agent-work">
          {work ? (
            <button type="button" className="link" onClick={() => onOpenGoal(work.goal.id)} title={work.task.title}>
              {truncateWords(work.task.title, 48)}
            </button>
          ) : (
            <span>{status === 'paused' || status === 'retired' ? 'Nothing running' : nodeStatusLabel(status)}</span>
          )}
          {agent.queued > 0 && <span>{formatCount(agent.queued)} queued</span>}
        </p>
        {error && <p className="orc-agent-error">{error}</p>}
      </div>
      {canUpdate && agent.status !== 'retired' && (
        <Button variant="quiet" className="orc-agent-toggle" loading={setStatus.isPending} onClick={() => void toggle()}>
          {paused ? 'Resume' : 'Pause'}
        </Button>
      )}
    </li>
  )
}
