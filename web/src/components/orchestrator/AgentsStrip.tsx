import { useState } from 'react'
import { Button, StatusTag } from '../ui'
import { formatCount } from '../../lib/format'
import type { BoardAgent } from '../../lib/queries'
import { usePauseAgent, useResumeAgent } from '../../lib/queries'
import { can } from '../../lib/session'
import { describeApiError } from '../../lib/api'
import { useToast } from '../../lib/toast'

/*
 * Every agent, at a glance, with the one control an orchestrator actually needs in the moment:
 * hold an agent's work, or let it carry on.
 */

export function AgentsStrip({ agents }: { agents: BoardAgent[] }) {
  const canUpdate = can('agent:update')
  const sorted = [...agents].sort((a, b) => a.name.localeCompare(b.name))

  return (
    <div>
      <div className="orc-agents-strip">
        {sorted.map((agent) => (
          <AgentChip key={agent.id} agent={agent} canUpdate={canUpdate} />
        ))}
      </div>
      {canUpdate && (
        <p className="caption muted" style={{ marginTop: 'var(--space-3)' }}>
          A paused agent keeps its queued work until you resume it.
        </p>
      )}
    </div>
  )
}

function AgentChip({ agent, canUpdate }: { agent: BoardAgent; canUpdate: boolean }) {
  const toast = useToast()
  const [error, setError] = useState<string | null>(null)
  const pause = usePauseAgent(agent.id)
  const resume = useResumeAgent(agent.id)
  const paused = agent.status === 'paused'
  const busy = pause.isPending || resume.isPending

  const toggle = async () => {
    setError(null)
    try {
      if (paused) {
        await resume.mutateAsync()
        toast.success(`${agent.name} resumed`)
      } else {
        await pause.mutateAsync()
        toast.success(`${agent.name} paused`)
      }
    } catch (thrown) {
      setError(describeApiError(thrown))
    }
  }

  return (
    <div className="orc-agent-chip">
      <div className="row" style={{ gap: 'var(--space-2)', justifyContent: 'space-between' }}>
        <span className="caption" style={{ fontWeight: 'var(--weight-strong)' }} title={agent.name}>
          {agent.name}
        </span>
        <StatusTag kind="agent" status={agent.status} />
      </div>
      <p className="caption muted" style={{ margin: '2px 0 var(--space-2)' }}>
        {agent.runningRunIds.length > 0
          ? `Working on ${formatCount(agent.runningRunIds.length)}`
          : agent.queued > 0
            ? `${formatCount(agent.queued)} queued`
            : 'Nothing queued'}
      </p>
      {canUpdate && agent.status !== 'retired' && (
        <Button variant="outline" onClick={() => void toggle()} loading={busy}>
          {paused ? 'Resume' : 'Pause'}
        </Button>
      )}
      {error && (
        <p className="caption" style={{ color: 'var(--danger)', marginTop: 'var(--space-1)' }}>
          {error}
        </p>
      )}
    </div>
  )
}
