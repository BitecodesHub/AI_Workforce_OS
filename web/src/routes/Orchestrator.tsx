import { useState } from 'react'
import { Button, Card, ConfirmDialog, EmptyState, PageHeader, StatRow, StatTile, Time } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { AgentsStrip } from '../components/orchestrator/AgentsStrip'
import { OrchestratorBoard } from '../components/orchestrator/Board'
import { FlowMap } from '../components/orchestrator/FlowMap'
import { Swimlanes } from '../components/orchestrator/Swimlanes'
import { describeApiError } from '../lib/api'
import { formatCount, formatMoney } from '../lib/format'
import { useBoard, useStopAll } from '../lib/queries'
import type { Board } from '../lib/queries'
import { can } from '../lib/session'
import { useToast } from '../lib/toast'
import { useNow } from '../lib/useNow'

/*
 * Live coordination: which agents are running, for whom, and in what order.
 *
 * A flow map for the system view, a per-agent timeline for the last couple of hours, and a board
 * of every goal in flight or just finished, all reading from one poll so nothing on the page ever
 * disagrees with anything else on it.
 */

export function Orchestrator() {
  const boardQuery = useBoard()
  const canStopAll = can('run:cancel')
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [stopError, setStopError] = useState<string | null>(null)
  const stopAll = useStopAll()
  const toast = useToast()

  const confirmStopAll = async () => {
    setStopError(null)
    try {
      const result = await stopAll.mutateAsync()
      setConfirmOpen(false)
      toast.info(
        `Cancelled ${formatCount(result.runsCancelled)} runs and ${formatCount(result.tasksCancelled)} tasks, and withdrew ${formatCount(result.approvalsWithdrawn)} approvals.`,
      )
    } catch (thrown) {
      setStopError(describeApiError(thrown))
    }
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Live coordination"
        title="Orchestrator"
        description="Every agent at work right now, who asked for it, and what happens next."
        action={
          <>
            <a className="button button-outline" href="/schedules">
              Schedules
            </a>
            {canStopAll && (
              <Button variant="danger" onClick={() => setConfirmOpen(true)} disabled={!boardQuery.data}>
                Stop everything
              </Button>
            )}
          </>
        }
        meta={
          boardQuery.data && (
            <span className="orc-live">
              <span className="orc-live-dot" aria-hidden="true" />
              Updated <Time iso={boardQuery.data.generatedAt} />
            </span>
          )
        }
      />

      {canStopAll && (
        <ConfirmDialog
          open={confirmOpen}
          onClose={() => setConfirmOpen(false)}
          onConfirm={confirmStopAll}
          eyebrow="Stop everything"
          title="Stop all agent work?"
          description="Every running and queued task in this workspace is cancelled, and any approval still waiting on a decision is withdrawn. This cannot be undone."
          confirmLabel="Stop everything"
          tone="danger"
          loading={stopAll.isPending}
          error={stopError}
        />
      )}

      <QueryState
        query={boardQuery}
        permission="run:read"
        what="the orchestrator board"
        isEmpty={isBoardEmpty}
        empty={<OrchestratorEmpty />}
        rows={6}
      >
        {(board) => <OrchestratorBody board={board} />}
      </QueryState>
    </div>
  )
}

function isBoardEmpty(board: Board): boolean {
  return board.goals.length === 0 && board.queue.length === 0 && board.timeline.length === 0
}

function OrchestratorEmpty() {
  return (
    <div style={{ marginTop: 'var(--space-6)' }}>
      <Card>
        <EmptyState
          icon={<EmptyIcon kind="task" />}
          title="Nothing is moving right now"
          body="No agent is working, nothing is waiting for a decision, and nothing finished in the last two hours. Start a conversation or set up a schedule to see the workforce in motion here."
          action={
            <div className="row" style={{ gap: 'var(--space-3)' }}>
              {can('chat:use') && (
                <a className="button button-primary" href="/chat">
                  Open Chat
                </a>
              )}
              <a className="button button-outline" href="/schedules">
                Set up a schedule
              </a>
            </div>
          }
        />
      </Card>
    </div>
  )
}

function OrchestratorBody({ board }: { board: Board }) {
  const now = useNow(1_000)
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(null)

  return (
    <div className="stack" style={{ gap: 'var(--space-7)' }}>
      <StatRow>
        <StatTile label="Working now" value={formatCount(board.stats.running)} />
        <StatTile
          label="Waiting for approval"
          value={formatCount(board.stats.waitingApproval)}
          href="/approvals"
        />
        <StatTile label="Queued" value={formatCount(board.stats.queued)} />
        <StatTile label="Held" value={formatCount(board.stats.held)} note="Agent paused" />
        <StatTile label="Done today" value={formatCount(board.stats.completedToday)} />
        {/* A fraction of a cent reads as a broken figure at tile size, so it is summarised and
            the exact amount given underneath. */}
        {board.stats.spendToday > 0 && board.stats.spendToday < 0.01 ? (
          <StatTile label="Spend today" value="Under US$0.01" note={`Exactly ${formatMoney(board.stats.spendToday)}`} />
        ) : (
          <StatTile label="Spend today" value={formatMoney(board.stats.spendToday)} />
        )}
      </StatRow>

      <Card as="section">
        <h2 className="section-heading" style={{ marginBottom: 'var(--space-3)' }}>
          The workforce right now
        </h2>
        <FlowMap board={board} selectedAgentId={selectedAgentId} onSelectAgent={setSelectedAgentId} />
      </Card>

      <Card as="section">
        <h2 className="section-heading" style={{ marginBottom: 'var(--space-3)' }}>
          Last two hours
        </h2>
        <Swimlanes board={board} now={now} />
      </Card>

      <section>
        <h2 className="section-heading" style={{ marginBottom: 'var(--space-3)' }}>
          Goals in flight
        </h2>
        <OrchestratorBoard board={board} selectedAgentId={selectedAgentId} onSelectAgent={setSelectedAgentId} />
      </section>

      <Card as="section">
        <h2 className="section-heading" style={{ marginBottom: 'var(--space-3)' }}>
          Agents
        </h2>
        <AgentsStrip agents={board.agents} />
      </Card>
    </div>
  )
}
