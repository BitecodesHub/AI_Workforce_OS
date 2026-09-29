import { useState } from 'react'
import { Button, ConfirmDialog } from '../ui'
import { formatElapsed } from '../../lib/format'
import { useRunSteps, type Agent, type BoardGoal, type BoardTask, type RunQuestion } from '../../lib/queries'
import { can } from '../../lib/session'
import { useNow } from '../../lib/useNow'
import { canStopGoal, currentTaskIndex, liveStepText } from './chatModel'

/*
 * One line per active goal, in the dock (B1.6) or the Work panel (B1.2). A goal that is running
 * shows its live step and elapsed time; one that is parked for a person points straight at what
 * needs deciding instead.
 */

function currentTaskOf(goal: BoardGoal): BoardTask | undefined {
  const index = currentTaskIndex(goal.tasks)
  return index === -1 ? undefined : goal.tasks[index]
}

function WorkStripLine({
  goal,
  agentNames,
  me,
  question,
  onStop,
  onGoTo,
}: {
  goal: BoardGoal
  agentNames: Record<string, Agent>
  me: string | null
  question: RunQuestion | undefined
  onStop: (goalId: string) => void
  onGoTo: (elementId: string) => void
}) {
  const now = useNow(1_000)
  const task = currentTaskOf(goal)
  const [confirmStop, setConfirmStop] = useState(false)
  const active = task?.status === 'running'
  const stepsQuery = useRunSteps(task?.runId ?? '', { active })
  const steps = stepsQuery.data
  const latestStep = steps?.[steps.length - 1]
  const agent = task?.agentId ? agentNames[task.agentId] : undefined
  const name = agent?.name ?? 'An agent'
  const stoppable = canStopGoal(goal, me, can)

  if (!task) return null

  if (task.status === 'waiting_input') {
    return (
      <div className="chat-workstrip-line" data-question>
        <span>{name} asked a question</span>
        {question ? (
          <button type="button" className="link" onClick={() => onGoTo(`question-${question.id}`)}>
            Go to question
          </button>
        ) : (
          <span className="caption muted">Waiting for an answer</span>
        )}
      </div>
    )
  }

  if (task.status === 'waiting_approval') {
    return (
      <div className="chat-workstrip-line">
        <span>{name} is waiting for approval</span>
        <button type="button" className="link" onClick={() => onGoTo(`progress-${goal.id}`)}>
          Review
        </button>
      </div>
    )
  }

  return (
    <div className="chat-workstrip-line">
      <span className="chat-pulse-soft" aria-hidden="true" />
      <span>{name} is working</span>
      <span className="caption muted">{liveStepText(latestStep)}</span>
      <span className="caption tabular">{formatElapsed(goal.createdAt, null, now)}</span>
      {stoppable && (
        <>
          <Button variant="quiet" onClick={() => setConfirmStop(true)}>
            Stop
          </Button>
          <ConfirmDialog
            open={confirmStop}
            onClose={() => setConfirmStop(false)}
            onConfirm={() => {
              setConfirmStop(false)
              onStop(goal.id)
            }}
            eyebrow="Stop this work"
            title="Stop this work?"
            description="Nothing further runs for this request. A step already under way is marked stopped."
            confirmLabel="Stop"
            tone="danger"
          />
        </>
      )}
    </div>
  )
}

export function WorkStrip({
  goals,
  questions,
  agentNames,
  me,
  compact,
  onStop,
  onGoTo,
}: {
  goals: BoardGoal[]
  questions: RunQuestion[]
  agentNames: Record<string, Agent>
  me: string | null
  compact: boolean
  onStop: (goalId: string) => void
  onGoTo: (elementId: string) => void
}) {
  const [expanded, setExpanded] = useState(false)

  if (goals.length === 0) return null

  const questionFor = (goalId: string) => questions.find((q) => q.status === 'pending' && q.goalId === goalId)
  const hasQuestion = goals.some((g) => currentTaskOf(g)?.status === 'waiting_input')

  if (compact && !expanded) {
    return (
      <div className="chat-workstrip" data-question={hasQuestion || undefined}>
        <button type="button" className="chat-workstrip-line chat-workstrip-disclosure" aria-expanded={false} onClick={() => setExpanded(true)}>
          {goals.length === 1 ? '1 piece of work running' : `${goals.length} pieces of work running`} · Show
        </button>
      </div>
    )
  }

  const shown = goals.slice(0, 2)
  const rest = goals.length - shown.length

  return (
    <div className="chat-workstrip" data-question={hasQuestion || undefined}>
      {compact && (
        <button type="button" className="chat-workstrip-line chat-workstrip-disclosure" aria-expanded={true} onClick={() => setExpanded(false)}>
          Hide
        </button>
      )}
      {shown.map((goal) => (
        <WorkStripLine key={goal.id} goal={goal} agentNames={agentNames} me={me} question={questionFor(goal.id)} onStop={onStop} onGoTo={onGoTo} />
      ))}
      {rest > 0 && <p className="caption muted">and {rest} more</p>}
    </div>
  )
}
