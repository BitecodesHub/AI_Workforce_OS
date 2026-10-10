// @find: goal sheet, goal drawer, goal details, goal steps, retry goal, stop goal, approve inline, answer question, copy goal id, side panel, GoalSheet, Orchestrator
// @what: The side sheet showing one goal with its steps, approvals and questions, and retry or stop actions.
// @flow: Opened from Board cards; uses StepList, InlineApproval, QuestionCard and the retry or stop goal mutations
import { useEffect, useRef, useState } from 'react'
import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent } from 'react'
import { Button, ConfirmDialog, StatusTag, Tag, Time } from '../ui'
import { CopyButton } from '../ui/CopyButton'
import { Sheet } from '../ui/Sheet'
import { InlineApproval } from '../run/InlineApproval'
import { QuestionCard } from '../run/QuestionCard'
import { useMediaQuery } from '../../hooks/useMediaQuery'
import { describeApiError } from '../../lib/api'
import { formatMoney, shortId } from '../../lib/format'
import { canRetryGoal, canStopGoal } from '../../lib/goals'
import { goalSourceLabel } from '../../lib/labels'
import { useCancelGoal, useGoal, useRetryGoal } from '../../lib/queries'
import type { Board, BoardGoal, BoardTask, Goal } from '../../lib/queries'
import { can, profile } from '../../lib/session'
import { useToast } from '../../lib/toast'
import { StepList } from './StepList'
import { requesterLabel } from './shared'
import { useMemberDirectory } from './useMemberDirectory'

const NAV_KEYS = new Set(['ArrowUp', 'ArrowDown', 'k', 'j'])

function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return target.isContentEditable || target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT'
}

/*
 * A goal's whole story in a sheet beside the board (B2.6): the open question or approval at the
 * top so it never sends a person hunting for it, then the chain of steps, then what can be done
 * about the goal as a whole. `GoalDrawer` (a modal, one card only) is gone; this replaces it.
 */

function toBoardTask(task: Goal['tasks'][number]): BoardTask {
  return { ...task, runStatus: null, stepCount: null, cost: null }
}

function toBoardGoal(goal: Goal): BoardGoal {
  return { ...goal, tasks: goal.tasks.map(toBoardTask) }
}

/** The retry step and the agent it belongs to - the same figures Tasks.tsx shows in its own confirmation. */
function retryStepInfo(goal: BoardGoal, board: Board): { step: number; agentName: string } {
  const tasks = [...goal.tasks].sort((a, b) => a.position - b.position)
  const retryTask = tasks.find((task) => ['failed', 'cancelled', 'skipped'].includes(task.status.toLowerCase()))
  const agent = retryTask?.agentId ? board.agents.find((candidate) => candidate.id === retryTask.agentId) : undefined
  return { step: (retryTask?.position ?? 0) + 1, agentName: agent?.name ?? 'The agent' }
}

function sourceLink(goal: BoardGoal) {
  if (goal.source === 'chat' && goal.conversationId) {
    return (
      <a className="link" href={`/chat?c=${encodeURIComponent(goal.conversationId)}&goal=${encodeURIComponent(goal.id)}`}>
        Open the conversation
      </a>
    )
  }
  if (goal.source === 'schedule') {
    return (
      <a className="link" href="/schedules">
        Open Schedules
      </a>
    )
  }
  return null
}

// @find: goal sheet component, goal details drawer, retry or stop goal
export function GoalSheet({
  goalId,
  board,
  orderedGoalIds,
  onClose,
  onNavigate,
  focus,
}: {
  goalId: string
  board: Board
  orderedGoalIds: string[]
  onClose: () => void
  onNavigate: (goalId: string) => void
  focus?: 'question' | 'approval' | null
}) {
  const toast = useToast()
  const directory = useMemberDirectory()
  const me = profile()?.userId ?? null
  const nameOf = (userId: string) => directory.members[userId]?.displayName ?? shortId(userId)

  const fromBoard = board.goals.find((candidate) => candidate.id === goalId)
  const fallbackQuery = useGoal(goalId, { enabled: !fromBoard })
  const goal = fromBoard ?? (fallbackQuery.data ? toBoardGoal(fallbackQuery.data) : undefined)

  const atLeast1024 = useMediaQuery('(min-width: 1024px)')
  const atLeast768 = useMediaQuery('(min-width: 768px)')
  const modal = !atLeast1024
  const width: 'sm' | 'md' | 'full' = atLeast1024 ? 'md' : atLeast768 ? 'sm' : 'full'

  const topCardRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!focus || !goal) return
    const raf = requestAnimationFrame(() => {
      topCardRef.current?.querySelector<HTMLElement>('input, button, textarea, select, a[href]')?.focus()
    })
    return () => cancelAnimationFrame(raf)
  }, [focus, goal])

  // Scoped to this sheet's own content, unlike a window-level listener: the board underneath keeps
  // its own j/k meaning (moving between cards) when a non-modal sheet leaves it reachable too.
  const onContentKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (!NAV_KEYS.has(event.key) || isTypingTarget(event.target)) return
    const index = orderedGoalIds.indexOf(goalId)
    if ((event.key === 'ArrowUp' || event.key === 'k') && index > 0) {
      event.preventDefault()
      onNavigate(orderedGoalIds[index - 1]!)
    } else if ((event.key === 'ArrowDown' || event.key === 'j') && index !== -1 && index < orderedGoalIds.length - 1) {
      event.preventDefault()
      onNavigate(orderedGoalIds[index + 1]!)
    }
  }

  const cancelGoal = useCancelGoal(goalId)
  const retryGoal = useRetryGoal()
  const [cancelOpen, setCancelOpen] = useState(false)
  const [cancelError, setCancelError] = useState<string | null>(null)
  const [retryOpen, setRetryOpen] = useState(false)
  const [retryError, setRetryError] = useState<string | null>(null)

  const returnFocusTo = typeof document !== 'undefined' ? document.querySelector<HTMLElement>(`[data-card-id="${goalId}"]`) : null

  if (!goal) {
    return (
      <Sheet open onClose={onClose} side="right" modal={modal} width={width} eyebrow="Goal" title="Goal" returnFocusTo={returnFocusTo}>
        <p>This goal is not on the board any more.</p>
        <a className="link" href={`/tasks?goal=${goalId}`}>
          Open it in Tasks
        </a>
      </Sheet>
    )
  }

  const pendingQuestion = board.questions.find((question) => question.goalId === goal.id && question.status === 'pending')
  const pendingApproval = board.approvals.find((approval) => approval.goalId === goal.id)
  const agent = pendingQuestion ? board.agents.find((candidate) => candidate.id === pendingQuestion.agentId) : undefined
  const approvalAgent = pendingApproval ? board.agents.find((candidate) => candidate.id === pendingApproval.agentId) : undefined

  const sourceEntry = goalSourceLabel(goal.source)
  const totalCost = goal.tasks.reduce((sum, task) => sum + (task.cost ?? 0), 0)
  const requester = requesterLabel(goal, directory, me)
  // The requester can stop their own goal, as they can retry it; anyone else needs task:cancel.
  const canCancel = canStopGoal(goal, me, can)
  const canRetry = canRetryGoal(goal, me, can)
  const { step: retryStep, agentName: retryAgent } = retryStepInfo(goal, board)
  // A goal with one task is just a task: the goal layer is only worth naming when there are several.
  const several = goal.tasks.length > 1

  const confirmCancel = async () => {
    setCancelError(null)
    try {
      await cancelGoal.mutateAsync()
      toast.success(several ? 'Goal cancelled' : 'Task cancelled')
      setCancelOpen(false)
    } catch (thrown) {
      setCancelError(describeApiError(thrown))
    }
  }

  const confirmRetry = async () => {
    setRetryError(null)
    try {
      await retryGoal.mutateAsync(goal.id)
      toast.success('Trying again.')
      setRetryOpen(false)
    } catch (thrown) {
      setRetryError(describeApiError(thrown))
    }
  }

  return (
    <Sheet
      open
      onClose={onClose}
      side="right"
      modal={modal}
      width={width}
      eyebrow={several ? 'Goal' : 'Task'}
      title={goal.title}
      returnFocusTo={returnFocusTo}
      footer={
        <>
          {canCancel && (
            <Button variant="outline" onClick={() => setCancelOpen(true)}>
              {several ? 'Cancel goal' : 'Cancel task'}
            </Button>
          )}
          {canRetry && (
            <Button variant="outline" onClick={() => setRetryOpen(true)}>
              {several ? `Try again from step ${retryStep}` : 'Try again'}
            </Button>
          )}
          {sourceLink(goal)}
          <a className="link" href={`/tasks?goal=${goal.id}`}>
            Open in Tasks
          </a>
        </>
      }
    >
      <div className="stack" style={{ gap: 'var(--space-5)' }} onKeyDown={onContentKeyDown}>
        <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between' }}>
          <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap', alignItems: 'center' }}>
            <StatusTag kind="goal" status={goal.status} withDot />
            <Tag tone={sourceEntry.tone}>{sourceEntry.label}</Tag>
            {totalCost > 0 && <span className="caption tabular">{formatMoney(totalCost)}</span>}
          </div>
          <CopyButton
            variant="text"
            label="Copy link"
            text={() => `${window.location.origin}/orchestrator?goal=${goal.id}`}
          />
        </div>

        <p className="caption muted">
          Asked for by {requester} · <Time iso={goal.createdAt} />
        </p>

        {(pendingQuestion || pendingApproval) && (
          <div ref={topCardRef}>
            {pendingQuestion ? (
              <QuestionCard
                question={pendingQuestion}
                agentName={agent?.name ?? 'The agent'}
                agentCategory={agent?.category}
                agentFallback={agent?.fallback}
                via="orchestrator"
                compact
                headingLevel="h3"
                goalTitle={goal.title}
                me={me}
                nameOf={nameOf}
              />
            ) : pendingApproval ? (
              <InlineApproval runId={pendingApproval.runId} agentName={approvalAgent?.name ?? 'The agent'} canDecide={pendingApproval.canDecide} compact />
            ) : null}
          </div>
        )}

        <Description goal={goal} />

        <div>
          <h3 className="section-heading" style={{ marginBottom: 'var(--space-3)' }}>
            Steps
          </h3>
          <StepList
            goal={goal}
            board={board}
            onGoToQuestion={() => {
              topCardRef.current?.scrollIntoView({ block: 'start' })
              topCardRef.current?.querySelector<HTMLElement>('input, button, textarea, select, a[href]')?.focus()
            }}
          />
        </div>
      </div>

      <ConfirmDialog
        open={cancelOpen}
        onClose={() => setCancelOpen(false)}
        onConfirm={confirmCancel}
        eyebrow="Cancel goal"
        title="Cancel this goal?"
        description="Its unfinished tasks are cancelled and will not run again. A run still in progress is stopped, and any approval it is waiting on is withdrawn."
        confirmLabel="Cancel goal"
        tone="danger"
        loading={cancelGoal.isPending}
        error={cancelError}
      />

      <ConfirmDialog
        open={retryOpen}
        onClose={() => setRetryOpen(false)}
        onConfirm={confirmRetry}
        eyebrow="Retry"
        title={`Try again from step ${retryStep}?`}
        description={`Step ${retryStep} (${retryAgent}) starts again from the beginning. Anything it already did before it stopped, such as a sent email, may happen again. Check its trace first.`}
        confirmLabel="Try again"
        tone="primary"
        loading={retryGoal.isPending}
        error={retryError}
      />
    </Sheet>
  )
}

function Description({ goal }: { goal: BoardGoal }) {
  const [expanded, setExpanded] = useState(false)
  const text = goal.description?.trim()
  if (!text || text === goal.title.trim()) return null
  const long = text.length > 280
  const clampStyle = { '--orc-clamp-lines': '4' } as CSSProperties
  return (
    <div>
      <div className={!expanded && long ? 'orc-clamp' : undefined} style={!expanded && long ? clampStyle : undefined}>
        <p>{text}</p>
      </div>
      {long && !expanded && (
        <button type="button" className="link" onClick={() => setExpanded(true)}>
          Show more
        </button>
      )}
    </div>
  )
}
