import { Notice } from '../ui'
import { QuestionCard } from './QuestionCard'
import { shortId } from '../../lib/format'
import { useAgentNames, useMemberNames, useRunQuestions } from '../../lib/queries'
import type { Run } from '../../lib/queries'
import { can, profile } from '../../lib/session'

/**
 * A run held for a person's answer: the question itself, or, wherever a form would duplicate one
 * already on screen, a short notice pointing at it.
 *
 * `form` mode (the default) is for the only two places that show the answer form: RunDetail and
 * the Orchestrator's RunSheet. Everywhere else that mentions a waiting run - RunTraceCompact
 * inside Chat's progress card and the Orchestrator's step list - passes `notice`, since each of
 * those screens already shows the question card once elsewhere; a second form would duplicate
 * its element ids and confuse the person answering it.
 */
export function WaitingForAnswer({
  run,
  mode = 'form',
  onGoToQuestion,
}: {
  run: Run
  mode?: 'form' | 'notice'
  onGoToQuestion?: ((questionId: string) => void) | undefined
}) {
  const active = run.status === 'waiting_input'
  const questionsQuery = useRunQuestions(run.id, { active })
  const agents = useAgentNames()
  const agentName = agents[run.agentId]?.name ?? 'The agent'
  const members = useMemberNames({ enabled: can('member:read') })
  const nameOf = (userId: string) => members[userId]?.displayName ?? shortId(userId)
  const me = profile()?.userId ?? null

  const pending = questionsQuery.data?.find((question) => question.status === 'pending')

  if (!pending) return <Notice tone="warning">This run is waiting for an answer.</Notice>

  if (mode === 'notice') {
    return (
      <Notice tone="warning">
        <span>
          {agentName} is waiting for an answer.{' '}
          {onGoToQuestion && (
            <button type="button" className="link" onClick={() => onGoToQuestion(pending.id)}>
              Go to question
            </button>
          )}
        </span>
      </Notice>
    )
  }

  return (
    <QuestionCard
      question={pending}
      agentName={agentName}
      agentCategory={agents[run.agentId]?.category}
      agentFallback={agents[run.agentId]?.fallback}
      via="run"
      me={me}
      nameOf={nameOf}
    />
  )
}
