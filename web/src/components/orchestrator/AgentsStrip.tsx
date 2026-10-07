import { useState } from 'react'
import type { ReactNode } from 'react'
import { ConfirmDialog, StatusTag, Tag } from '../ui'
import { AgentAvatar } from '../ui/AgentAvatar'
import { AgentStatusButton } from '../agents/AgentStatusButton'
import { bulkMessage, bulkTargets } from '../../lib/agentQueries'
import { formatCount, truncateWords } from '../../lib/format'
import { useSetAgentStatus } from '../../lib/queries'
import type { Board, BoardAgent } from '../../lib/queries'
import { can } from '../../lib/session'
import { useToast } from '../../lib/toast'
import { nodeStatus, nodeStatusLabel } from './FlowMap'
import { currentTaskFor } from './shared'

/*
 * Every agent, as a wrap of compact chips (B2.9): who is doing what right now, how much is queued
 * behind them, and the one control an orchestrator actually needs in the moment - hold an agent's
 * work, or let it carry on - plus the pair of "everyone at once" actions.
 *
 * The bulk actions live in `useAgentBulk`, so the page can put them in the Workforce section's own
 * header (visible while it is collapsed) and still share the per-agent spinners with the chips.
 * Rendered on its own, the strip draws them in its own header.
 */

const BUSY = new Set(['running', 'asking', 'waiting'])

export type AgentBulk = {
  canUpdate: boolean
  /** The agents a bulk action is still changing. */
  changing: ReadonlySet<string>
  /** The Pause all and Resume all controls, or null for somebody who may not change agents. */
  actions: ReactNode
  /** The two confirmations; render them once, anywhere on the page. */
  dialogs: ReactNode
}

function sortedAgents(board: Board): BoardAgent[] {
  return [...board.agents].sort((a, b) => a.name.localeCompare(b.name))
}

export function useAgentBulk(board: Board): AgentBulk {
  const toast = useToast()
  const setStatus = useSetAgentStatus()
  const canUpdate = can('agent:update')
  const canCreate = can('task:create')
  const [bulkOpen, setBulkOpen] = useState<'pause' | 'resume' | null>(null)
  const [includeGeneral, setIncludeGeneral] = useState(true)
  const [bulkError, setBulkError] = useState<string | null>(null)

  // The agents a bulk action is still changing, each chip showing its own spinner as its change
  // lands, and the dialog's own flag for the whole run: one mutation hook cannot say either.
  const [changing, setChanging] = useState<ReadonlySet<string>>(new Set())
  const [bulkRunning, setBulkRunning] = useState(false)

  const runBulk = async (action: 'pause' | 'resume') => {
    setBulkError(null)
    // Only agents not already in the state asked for, so the count below is of real changes.
    const targets = bulkTargets(sortedAgents(board), action, includeGeneral)
    if (targets.length === 0) {
      setBulkOpen(null)
      toast.info(bulkMessage(action, 0, 0))
      return
    }
    setBulkRunning(true)
    setChanging(new Set(targets.map((agent) => agent.id)))
    const results = await Promise.allSettled(
      targets.map(async (agent) => {
        try {
          await setStatus.mutateAsync({ id: agent.id, action })
        } finally {
          setChanging((current) => {
            const rest = new Set(current)
            rest.delete(agent.id)
            return rest
          })
        }
      }),
    )
    const failed = results.filter((result) => result.status === 'rejected').length
    setBulkRunning(false)
    setBulkOpen(null)
    toast.info(bulkMessage(action, results.length - failed, failed))
  }

  const actions = canUpdate ? (
    <span className="orc-agents-bulk">
      <button type="button" className="link" onClick={() => setBulkOpen('pause')}>
        Pause all
      </button>
      <button type="button" className="link" onClick={() => setBulkOpen('resume')}>
        Resume all
      </button>
    </span>
  ) : null

  const dialogs = (
    <>
      <ConfirmDialog
        open={bulkOpen === 'pause'}
        onClose={() => setBulkOpen(null)}
        onConfirm={() => runBulk('pause')}
        eyebrow="Pause all"
        title="Pause every agent?"
        description="Queued work waits until an agent is resumed. Work already running is not stopped."
        confirmLabel="Pause all"
        tone="danger"
        loading={bulkRunning}
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
        loading={bulkRunning}
        error={bulkError}
      />
    </>
  )

  return { canUpdate, changing, actions, dialogs }
}

export function AgentsStrip({
  board,
  onOpenGoal,
  bulk,
}: {
  board: Board
  onOpenGoal: (goalId: string) => void
  /** The page's own bulk controller, when its actions sit in a section header instead. */
  bulk?: AgentBulk
}) {
  const own = useAgentBulk(board)
  const controller = bulk ?? own
  const sorted = sortedAgents(board)
  const busy = sorted.filter((agent) => BUSY.has(nodeStatus(agent))).length

  return (
    <div className="orc-agents">
      {!bulk && (
        <div className="orc-agents-head">
          <h3 className="orc-panel-title">
            Agents <span className="orc-panel-count tabular">{formatCount(sorted.length)}</span>
          </h3>
          <span className="caption muted">{busy > 0 ? `${formatCount(busy)} busy` : 'All idle'}</span>
          {own.actions}
        </div>
      )}

      <ul className="orc-agents-list" aria-label="Agents">
        {sorted.map((agent) => (
          <AgentRow key={agent.id} agent={agent} board={board} changing={controller.changing.has(agent.id)} onOpenGoal={onOpenGoal} />
        ))}
      </ul>

      {!bulk && own.dialogs}
    </div>
  )
}

function AgentRow({
  agent,
  board,
  changing,
  onOpenGoal,
}: {
  agent: BoardAgent
  board: Board
  /** A bulk action is changing this agent right now. */
  changing: boolean
  onOpenGoal: (goalId: string) => void
}) {
  const status = nodeStatus(agent)
  const work = currentTaskFor(board, agent.id)

  return (
    <li className="orc-agent-row" data-status={status}>
      <span className="orc-agent-avatar">
        <AgentAvatar name={agent.name} category={agent.category} fallback={agent.fallback} size="sm" />
        <span className="orc-agent-dot" data-status={status} aria-hidden="true" />
      </span>
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
              {truncateWords(work.task.title, 40)}
            </button>
          ) : (
            <span>{status === 'paused' || status === 'retired' ? 'Nothing running' : nodeStatusLabel(status)}</span>
          )}
          {agent.queued > 0 && <span>{formatCount(agent.queued)} queued</span>}
        </p>
      </div>
      {/* Its own request and its own spinner, whatever the others are doing. It hides itself for a
          person who may not change agents and for a retired agent. Revealed on hover or focus with
          a mouse; always shown on a touch screen and for a paused agent. */}
      <AgentStatusButton agent={agent} variant="quiet" className="orc-agent-toggle" busy={changing} />
    </li>
  )
}
