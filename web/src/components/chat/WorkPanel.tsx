// @find: work panel, context panel, wide screen panel, active goals, work in progress, chat side panel
// @what: Wide-screen side panel showing the work in progress.
// @flow: Used by the Chat page; renders WorkStrip.
import { Eyebrow, IconButton, Tag } from '../ui'
import { Collapsible, useCollapsed } from '../ui/Collapsible'
import { AgentAvatar } from '../ui/AgentAvatar'
import type { Agent, BoardGoal, RunQuestion } from '../../lib/queries'
import { activeGoals } from './chatModel'
import { WorkStrip } from './WorkStrip'

/*
 * The wide-screen (1440px+) context panel beside the thread (D-16, B1.1). It repeats the dock's own
 * work strip at full size, lists open questions as links rather than a second answer form, and
 * names who is in the conversation.
 */

function CloseIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
      <path d="M2 2l10 10M12 2L2 12" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}

// @find: WorkPanel, work panel, work panel, context panel, wide screen panel, active goals
export function WorkPanel({
  goals,
  questions,
  participants,
  agentNames,
  me,
  onStop,
  onGoTo,
  onReview,
  onClose,
}: {
  goals: BoardGoal[]
  questions: RunQuestion[]
  participants: Agent[]
  agentNames: Record<string, Agent>
  me: string | null
  onStop: (goalId: string) => void
  onGoTo: (elementId: string) => void
  onReview: (goalId: string) => void
  onClose: () => void
}) {
  const [workingOpen, setWorkingOpen] = useCollapsed('chat.panel.working', true)
  const [questionsOpen, setQuestionsOpen] = useCollapsed('chat.panel.questions', true)
  const [participantsOpen, setParticipantsOpen] = useCollapsed('chat.panel.participants', true)

  const working = activeGoals(goals)
  const pendingQuestions = questions.filter((q) => q.status === 'pending')

  return (
    <aside id="chat-work-panel" className="chat-work-panel" aria-label="Work in this conversation">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Eyebrow>In this conversation</Eyebrow>
        <IconButton label="Hide work panel" onClick={onClose}>
          <CloseIcon />
        </IconButton>
      </div>

      <Collapsible title="Working now" open={workingOpen} onToggle={setWorkingOpen} headingLevel="h3">
        {working.length === 0 ? (
          <p className="caption muted">Nothing is running right now.</p>
        ) : (
          <WorkStrip
            goals={working}
            questions={questions}
            agentNames={agentNames}
            me={me}
            compact={false}
            onStop={onStop}
            onGoTo={onGoTo}
            onReview={onReview}
          />
        )}
      </Collapsible>

      <Collapsible
        title="Questions"
        summary={pendingQuestions.length > 0 ? String(pendingQuestions.length) : undefined}
        open={questionsOpen}
        onToggle={setQuestionsOpen}
        headingLevel="h3"
      >
        {pendingQuestions.length === 0 ? (
          <p className="caption muted">No open questions.</p>
        ) : (
          <ul className="chat-panel-questions">
            {pendingQuestions.map((question) => (
              <li key={question.id} className="chat-panel-question">
                <Tag>{question.questions[0]?.header ?? 'Question'}</Tag>
                <p className="caption">{question.questions[0]?.question}</p>
                <button type="button" className="link" onClick={() => onGoTo(`question-${question.id}`)}>
                  Go to question
                </button>
              </li>
            ))}
          </ul>
        )}
      </Collapsible>

      <Collapsible title="Participants" open={participantsOpen} onToggle={setParticipantsOpen} headingLevel="h3">
        {participants.length === 0 ? (
          <p className="caption muted">No agent has taken part yet.</p>
        ) : (
          <ul className="chat-panel-participants">
            {participants.map((agent) => (
              <li key={agent.id} className="row" style={{ gap: 'var(--space-2)', alignItems: 'center' }}>
                <AgentAvatar name={agent.name} category={agent.category} fallback={agent.fallback ?? false} size="sm" />
                <span>{agent.name}</span>
                {agent.fallback && <Tag tone="neutral">Default</Tag>}
              </li>
            ))}
          </ul>
        )}
      </Collapsible>
    </aside>
  )
}
