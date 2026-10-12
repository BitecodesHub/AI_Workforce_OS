// @find: step run sheet, open a step's run, run trace from the canvas, approve from the canvas, workflow step trace, StepRunSheet
// @what: The side sheet a workflow step opens in the live view and the replay: the task's status, its pending approval and its run trace, reusing the run components.
// @flow: Opened by routes/WorkflowRun.tsx; uses RunTraceCompact and InlineApproval, which read the run through lib/queries
import { PermissionState, StatusTag } from '../ui'
import { Sheet } from '../ui/Sheet'
import { InlineApproval } from '../run/InlineApproval'
import { RunTraceCompact } from '../run/RunTraceCompact'
import { useMediaQuery } from '../../hooks/useMediaQuery'
import { can } from '../../lib/session'
import { isGoalSettled } from '../../lib/workflowReplay'
import type { TimelineTask } from '../../lib/workflowQueries'

// @find: why a step has no run, skipped step, cancelled step, step not started copy
/**
 * Why a step has no run to show, in words that fit what happened to it: skipped or cancelled
 * steps never start, a step of a finished goal did not start, and a step still to come starts
 * once the steps that hand over to it have finished (straight away when nothing does).
 */
export function noRunSentence(task: Pick<TimelineTask, 'status' | 'dependsOn' | 'explicitDependencies' | 'position'>, goalStatus: string): string {
  const status = task.status.toLowerCase()
  if (status === 'skipped') return 'This step was skipped because a step it depends on did not complete.'
  if (status === 'cancelled') return 'This step was stopped before it started.'
  if (status === 'failed') return 'This step failed before its run started.'
  if (isGoalSettled(goalStatus)) return 'This step did not start before the work finished.'
  const waitsForNothing = task.explicitDependencies ? task.dependsOn.length === 0 : task.position === 0 && task.dependsOn.length === 0
  return waitsForNothing
    ? 'This step has not started yet. It starts as soon as there is room to run it.'
    : 'This step has not started yet. It starts once the steps that hand over to it have finished.'
}

// @find: step run sheet component
/**
 * One step's run. The trace and the approval need run:read; a role without it sees why instead.
 * A step without a run says why (noRunSentence), using the goal's status. Non-modal from 1024
 * pixels up, so the canvas stays usable beside it; modal and full width on a phone.
 */
export function StepRunSheet({ task, goalStatus, onClose }: { task: TimelineTask; goalStatus: string; onClose: () => void }) {
  const atLeast1024 = useMediaQuery('(min-width: 1024px)')
  const atLeast768 = useMediaQuery('(min-width: 768px)')
  const width: 'sm' | 'md' | 'full' = atLeast1024 ? 'md' : atLeast768 ? 'sm' : 'full'
  const agentName = task.agentName ?? 'The agent'
  const runId = task.runId
  return (
    <Sheet
      open
      onClose={onClose}
      side="right"
      modal={!atLeast1024}
      width={width}
      eyebrow={`Step ${task.position + 1}`}
      title={task.title}
      footer={
        runId && can('run:read') ? (
          <a className="link" href={`/runs/${runId}`}>
            Open the full run
          </a>
        ) : undefined
      }
    >
      <div className="stack" style={{ gap: 'var(--space-5)' }}>
        <p>
          <StatusTag kind="task" status={task.status} withDot /> <span className="muted">{agentName}</span>
        </p>
        {!runId ? (
          <p className="muted">{noRunSentence(task, goalStatus)}</p>
        ) : !can('run:read') ? (
          <PermissionState permission="run:read" what="A step's run" />
        ) : (
          <>
            {task.status === 'waiting_approval' && <InlineApproval runId={runId} agentName={agentName} compact />}
            <RunTraceCompact runId={runId} headingLevel="h3" />
          </>
        )}
      </div>
    </Sheet>
  )
}
