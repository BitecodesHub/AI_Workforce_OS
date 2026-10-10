// @find: agent question, clarifying question, answer question, choose option, Other answer, answered count, question closed, run waiting for answer, Approvals page questions
// @what: Form for an agent's clarifying question, answered in Chat, Orchestrator or Approvals.
// @flow: Used by QuestionMessage and WaitingForAnswer.
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { useEffect, useId, useRef, useState } from 'react'
import { Button, Eyebrow, Input, Notice, Tag } from '../ui'
import { AgentAvatar } from '../ui/AgentAvatar'
import { ApiError } from '../../lib/api'
import { formatAgo, formatTimeIn } from '../../lib/format'
import {
  answerSummary,
  closesIn,
  draftToInput,
  emptyDraft,
  questionError,
  setOther,
  toggleOption,
  toggleOther,
  validateDraft,
  type AnswerDraft,
} from '../../lib/questions'
import { isRunActive, useAnswerQuestion, useExtendQuestion } from '../../lib/queries'
import type { AnsweredVia, AnswerQuestionResult, QuestionItem, RunQuestion } from '../../lib/queries'
import { useNow } from '../../lib/useNow'

/*
 * An agent's clarifying question, answered from Chat, the Orchestrator sheet, the Approvals page
 * or a run's own trace. One component renders every state the question can be in - open and
 * answerable, submitting, closed (answered, skipped, expired, withdrawn), or open but not
 * something this viewer can answer - because a person switching between those screens should
 * recognise the same card wherever it appears.
 *
 * The card is otherwise a plain controlled form: `question` is the caller's live data (from
 * useRunQuestions or useQuestions), and the only state kept here is the in-progress draft and the
 * result of this card's own submit, which is shown ahead of a slower cache refetch so the closed
 * state appears at once rather than a beat later.
 */

const SUMMARY_CHAT = 'Answer every question, or reply in your own words.'
const SUMMARY_OTHER = 'Answer every question, or use Other to write your own answer.'

const VIA_LABEL: Record<AnsweredVia, string> = {
  chat: 'Chat',
  orchestrator: 'the Orchestrator',
  run: 'a run page',
  approvals: 'Approvals',
}

const isClosedRace = (error: unknown): boolean =>
  error instanceof ApiError && (error.code === 'question_already_answered' || error.code === 'question_closed')

/** An answered entry, for the footer's "1 of 2 answered" count: some choice has actually been made. */
function isEntryAnswered(draft: AnswerDraft, item: QuestionItem): boolean {
  const entry = draft[item.id]
  if (!entry) return false
  return entry.selected.length > 0 || (entry.otherOn && entry.other.trim() !== '')
}

/** The name of a person by id, falling back to a generic phrase when either is unavailable. */
function nameFor(id: string | null, nameOf: ((userId: string) => string) | undefined, fallback: string): string {
  if (!id) return fallback
  return nameOf?.(id) ?? fallback
}

type OptionRowProps = {
  groupName: string
  label: string
  description: string
  recommended: boolean
  multi: boolean
  checked: boolean
  disabled: boolean
  descriptionId: string
  errorId: string | undefined
  onToggle?: () => void
}

function OptionRow({
  groupName,
  label,
  description,
  recommended,
  multi,
  checked,
  disabled,
  descriptionId,
  errorId,
  onToggle,
}: OptionRowProps) {
  const describedBy = [descriptionId, errorId].filter(Boolean).join(' ') || undefined
  return (
    <label className="question-option">
      <input
        type={multi ? 'checkbox' : 'radio'}
        name={groupName}
        checked={checked}
        disabled={disabled}
        aria-describedby={describedBy}
        aria-invalid={errorId ? true : undefined}
        onChange={onToggle ? () => onToggle() : undefined}
        readOnly={!onToggle}
      />
      <span className="question-option-label">
        {label} {recommended && <Tag tone="blue">Recommended</Tag>}
        <span className="question-option-description" id={descriptionId}>
          {description}
        </span>
      </span>
    </label>
  )
}

/** One question's fieldset, interactive: native inputs, an always-last Other control, and its own error. */
function QuestionBlock({
  question,
  item,
  draft,
  error,
  disabled,
  onToggleOption,
  onToggleOther,
  onSetOther,
}: {
  question: RunQuestion
  item: QuestionItem
  draft: AnswerDraft
  error: string | undefined
  disabled: boolean
  onToggleOption: (label: string) => void
  onToggleOther: () => void
  onSetOther: (text: string) => void
}) {
  const entry = draft[item.id] ?? { selected: [], otherOn: false, other: '' }
  const groupName = `${question.id}-${item.id}`
  const errorId = useId()
  const otherControlRef = useRef<HTMLInputElement>(null)
  // The shared `Input` component does not forward a ref to its own <input>, so the field it
  // renders is found through its wrapper instead of a ref passed straight to it.
  const otherFieldRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (entry.otherOn) otherFieldRef.current?.querySelector('input')?.focus()
  }, [entry.otherOn])

  return (
    <fieldset className="question-block">
      <legend>
        <Tag>{item.header}</Tag> {item.question}
      </legend>
      {item.multiSelect && <p className="caption muted">Choose all that apply</p>}
      {item.options.map((option, optionIndex) => (
        <OptionRow
          key={option.label}
          groupName={groupName}
          label={option.label}
          description={option.description}
          recommended={option.recommended ?? false}
          multi={item.multiSelect}
          checked={entry.selected.includes(option.label)}
          disabled={disabled}
          descriptionId={`${groupName}-${optionIndex}-desc`}
          errorId={error && optionIndex === 0 ? errorId : undefined}
          onToggle={() => onToggleOption(option.label)}
        />
      ))}
      <label className="question-option">
        <input
          ref={otherControlRef}
          type={item.multiSelect ? 'checkbox' : 'radio'}
          name={groupName}
          checked={entry.otherOn}
          disabled={disabled}
          onChange={() => onToggleOther()}
        />
        <span className="question-option-label">Other</span>
      </label>
      {entry.otherOn && (
        <div ref={otherFieldRef} style={{ marginTop: 'var(--space-2)' }}>
          <Input
            label="Your answer"
            value={entry.other}
            disabled={disabled}
            onChange={(event) => onSetOther(event.target.value)}
            onKeyDown={(event: ReactKeyboardEvent<HTMLInputElement>) => {
              if (event.key === 'Escape') {
                event.preventDefault()
                otherControlRef.current?.focus()
              }
            }}
          />
        </div>
      )}
      {error && (
        <p className="question-error" id={errorId}>
          {error}
        </p>
      )}
    </fieldset>
  )
}

/** The read-only fieldset shown to a viewer who cannot answer: the same rows, nothing checked or clickable. */
function ReadOnlyQuestionBlock({ question, item }: { question: RunQuestion; item: QuestionItem }) {
  const groupName = `${question.id}-${item.id}-readonly`
  return (
    <fieldset className="question-block">
      <legend>
        <Tag>{item.header}</Tag> {item.question}
      </legend>
      {item.multiSelect && <p className="caption muted">Choose all that apply</p>}
      {item.options.map((option, optionIndex) => (
        <OptionRow
          key={option.label}
          groupName={groupName}
          label={option.label}
          description={option.description}
          recommended={option.recommended ?? false}
          multi={item.multiSelect}
          checked={false}
          disabled
          descriptionId={`${groupName}-${optionIndex}-desc`}
          errorId={undefined}
        />
      ))}
      <label className="question-option">
        <input type={item.multiSelect ? 'checkbox' : 'radio'} name={groupName} checked={false} disabled readOnly />
        <span className="question-option-label">Other</span>
      </label>
    </fieldset>
  )
}

/** "For: <goal, or Direct run> · Asked of <name>", with links, shown for every via except chat. */
function ContextLine({
  question,
  goalTitle,
  links,
  me,
  nameOf,
}: {
  question: RunQuestion
  goalTitle: string | null | undefined
  links: { conversation?: string; run?: string } | undefined
  me: string | null | undefined
  nameOf: ((userId: string) => string) | undefined
}) {
  const forLabel = (goalTitle !== undefined ? goalTitle : question.goalTitle) ?? 'Direct run'
  const requestedName = nameFor(question.requestedBy, nameOf, 'the person who asked')
  const conversationHref =
    links?.conversation ?? (question.conversationId ? `/chat?c=${question.conversationId}#question-${question.id}` : undefined)
  const runHref = links?.run ?? `/runs/${question.runId}`
  const answeringForSomeoneElse = question.canAnswer && Boolean(question.requestedBy) && question.requestedBy !== me

  return (
    <p className="caption muted">
      For: {forLabel} · Asked of {requestedName}
      {conversationHref && (
        <>
          {' · '}
          <a className="link" href={conversationHref}>
            Open the conversation
          </a>
        </>
      )}
      {' · '}
      <a className="link" href={runHref}>
        Open run
      </a>
      {answeringForSomeoneElse && <> You are answering for {requestedName}.</>}
    </p>
  )
}

/** The header chips shared by the open and closed views: one Tag per question, in order. */
function HeaderChips({ question }: { question: RunQuestion }) {
  return (
    <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap', marginBottom: 'var(--space-3)' }}>
      {question.questions.map((item) => (
        <Tag key={item.id}>{item.header}</Tag>
      ))}
    </div>
  )
}

/** What the closed card says, for every way a question stops being open. */
function ClosedBody({
  question,
  agentName,
  me,
  nameOf,
}: {
  question: RunQuestion
  agentName: string
  me: string | null | undefined
  nameOf: ((userId: string) => string) | undefined
}) {
  const now = useNow(60_000)
  const runContinuing = question.runStatus === 'running' || question.runStatus === 'waiting_input'

  if (question.status === 'cancelled') {
    return (
      <p>
        This question was withdrawn
        {question.closedReason ? `: ${question.closedReason}` : ' because the work was stopped.'}
      </p>
    )
  }

  if (question.status === 'expired') {
    const closingTime = formatTimeIn(Date.parse(question.expiresAt))
    const active = isRunActive({ status: question.runStatus })
    return (
      <p>
        No answer arrived by {closingTime}, so this question closed. {agentName}{' '}
        {active ? 'is finishing with what it has.' : 'finished with what it had.'}
      </p>
    )
  }

  if (question.status === 'answered') {
    const answer = question.answer
    if (answer?.skipped) return <p>You asked {agentName} to use its judgement.</p>
    const summary = answer ? answerSummary(question) : ''
    const answeredByMe = me != null && question.answeredBy === me
    const lead = answeredByMe
      ? 'You answered'
      : `${nameFor(question.answeredBy, nameOf, 'Someone')} answered in ${VIA_LABEL[question.answeredVia ?? 'orchestrator']} ${formatAgo(now - Date.parse(question.answeredAt ?? ''))}`
    return (
      <>
        <p>
          {lead}: {summary}
        </p>
        {answer?.note && <p className="caption muted">{answer.note}</p>}
        {runContinuing && <p className="caption muted">{agentName} is continuing.</p>}
      </>
    )
  }

  // Pending, but not something this viewer can answer.
  return (
    <>
      <div className="stack" style={{ gap: 'var(--space-4)' }}>
        {question.questions.map((item) => (
          <ReadOnlyQuestionBlock key={item.id} question={question} item={item} />
        ))}
      </div>
      <p style={{ marginTop: 'var(--space-4)' }}>
        Waiting for {nameFor(question.requestedBy, nameOf, 'the person who asked')} to answer. Anyone who can cancel
        work can also answer it.
      </p>
    </>
  )
}

// @find: QuestionCard, question card, agent question, clarifying question, answer question, choose option
export function QuestionCard({
  question,
  agentName,
  agentCategory = null,
  agentFallback = false,
  via,
  compact = false,
  headingLevel = 'h3',
  onReplyInOwnWords,
  answeringInComposer = false,
  goalTitle,
  links,
  me = null,
  nameOf,
}: {
  question: RunQuestion
  agentName: string
  agentCategory?: string | null | undefined
  agentFallback?: boolean | undefined
  via: AnsweredVia
  compact?: boolean
  headingLevel?: 'h2' | 'h3' | 'h4'
  onReplyInOwnWords?: (() => void) | undefined
  answeringInComposer?: boolean
  goalTitle?: string | null | undefined
  links?: { conversation?: string; run?: string } | undefined
  me?: string | null | undefined
  nameOf?: ((userId: string) => string) | undefined
}) {
  const headingId = useId()
  const announceId = useId()
  const now = useNow(60_000)
  const answer = useAnswerQuestion()
  const extend = useExtendQuestion()

  const [draftForId, setDraftForId] = useState(question.id)
  const [draft, setDraft] = useState<AnswerDraft>(() => emptyDraft(question))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [summaryError, setSummaryError] = useState<string | null>(null)
  const [sendError, setSendError] = useState<string | null>(null)
  const [raceClosed, setRaceClosed] = useState(false)
  const [justAnswered, setJustAnswered] = useState<AnswerQuestionResult | null>(null)
  const [announcement, setAnnouncement] = useState('')
  const closedRef = useRef<HTMLDivElement>(null)
  // Once mounted, the "arrive" animation never replays even if the card later closes and this
  // instance stays around, so no state is needed for it beyond this one-time read.
  const [wasOpenOnMount] = useState(() => question.status === 'pending' && question.canAnswer)

  // A fresh question (a new id) resets the draft. Adjusted during render, the "previous value"
  // pattern used elsewhere (0.2), rather than an effect that would mirror the prop.
  if (draftForId !== question.id) {
    setDraftForId(question.id)
    setDraft(emptyDraft(question))
    setErrors({})
    setSummaryError(null)
    setSendError(null)
    setRaceClosed(false)
    setJustAnswered(null)
  }
  // The prop has caught up with a race this card lost; stop overriding it with a generic message.
  if (raceClosed && question.status !== 'pending') setRaceClosed(false)

  useEffect(() => {
    if (justAnswered) closedRef.current?.focus()
  }, [justAnswered])

  const effectiveQuestion = justAnswered?.question ?? question
  const isOpen = !raceClosed && !justAnswered && effectiveQuestion.status === 'pending' && effectiveQuestion.canAnswer

  async function submit(input: { skipped: boolean }) {
    if (!input.skipped) {
      const validation = validateDraft(question, draft)
      if (Object.keys(validation).length > 0) {
        setErrors(validation)
        setSummaryError(via === 'chat' ? SUMMARY_CHAT : SUMMARY_OTHER)
        return
      }
    }
    setErrors({})
    setSummaryError(null)
    setSendError(null)
    try {
      const result = input.skipped
        ? await answer.mutateAsync({ id: question.id, answers: [], skipped: true, via })
        : await answer.mutateAsync(draftToInput(question, draft, via))
      setJustAnswered(result)
      setAnnouncement(`Answer sent. ${agentName} is continuing.`)
    } catch (error) {
      if (isClosedRace(error)) setRaceClosed(true)
      else setSendError(questionError(error))
    }
  }

  const heading = headingLevel
  const Heading = heading

  if (!isOpen) {
    return (
      <div
        id={`question-${question.id}`}
        className="question-card question-card-closed"
        tabIndex={-1}
        ref={closedRef}
      >
        <Eyebrow>Question</Eyebrow>
        <Heading id={headingId} className="section-heading" style={{ marginTop: 'var(--space-1)', marginBottom: 'var(--space-3)' }}>
          {agentName} asked a question
        </Heading>
        {via !== 'chat' && (
          <ContextLine question={effectiveQuestion} goalTitle={goalTitle} links={links} me={me} nameOf={nameOf} />
        )}
        {effectiveQuestion.status !== 'pending' && <HeaderChips question={effectiveQuestion} />}
        <ClosedBody question={raceClosed ? question : effectiveQuestion} agentName={agentName} me={me} nameOf={nameOf} />
        <span role="status" className="visually-hidden">
          {announcement}
        </span>
      </div>
    )
  }

  const answeredCount = question.questions.filter((item) => isEntryAnswered(draft, item)).length
  const multiple = question.questions.length > 1

  return (
    <form
      id={`question-${question.id}`}
      className={`question-card${wasOpenOnMount ? ' question-card-new' : ''}`}
      aria-labelledby={headingId}
      onSubmit={(event) => {
        event.preventDefault()
        void submit({ skipped: false })
      }}
      onKeyDown={(event) => {
        if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
          event.preventDefault()
          void submit({ skipped: false })
        }
      }}
    >
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-start', gap: 'var(--space-3)', flexWrap: 'wrap' }}>
        <div className="row" style={{ gap: 'var(--space-3)', alignItems: 'center' }}>
          {!compact && <AgentAvatar name={agentName} category={agentCategory} fallback={agentFallback} />}
          <div>
            <Eyebrow>Question</Eyebrow>
            <Heading id={headingId} className="section-heading" style={{ marginTop: 'var(--space-1)' }}>
              {agentName} needs an answer to continue
            </Heading>
            {compact && (
              <p className="caption muted" style={{ marginTop: 'var(--space-1)' }}>
                Asked {formatAgo(now - Date.parse(question.createdAt))} · {closesIn(question.expiresAt, now)}
              </p>
            )}
          </div>
        </div>
        {!compact && (
          <div className="row" style={{ gap: 'var(--space-3)', alignItems: 'center', flexWrap: 'wrap' }}>
            <span className="caption muted">
              Asked {formatAgo(now - Date.parse(question.createdAt))} · {closesIn(question.expiresAt, now)}
            </span>
            {question.extendable && (
              <Button variant="quiet" type="button" onClick={() => void extend.mutateAsync(question.id)} loading={extend.isPending}>
                Keep it open another day
              </Button>
            )}
          </div>
        )}
        {compact && question.extendable && (
          <Button variant="quiet" type="button" onClick={() => void extend.mutateAsync(question.id)} loading={extend.isPending}>
            Keep it open another day
          </Button>
        )}
      </div>

      {via !== 'chat' && (
        <div style={{ marginTop: 'var(--space-3)' }}>
          <ContextLine question={question} goalTitle={goalTitle} links={links} me={me} nameOf={nameOf} />
        </div>
      )}

      <fieldset disabled={answer.isPending} style={{ border: 'none', margin: 0, padding: 0 }}>
        <div className="stack" style={{ gap: 'var(--space-4)' }}>
          {question.questions.map((item) => (
            <QuestionBlock
              key={item.id}
              question={question}
              item={item}
              draft={draft}
              error={errors[item.id]}
              disabled={answer.isPending}
              onToggleOption={(label) => setDraft((current) => toggleOption(current, item.id, label, item.multiSelect))}
              onToggleOther={() => setDraft((current) => toggleOther(current, item.id, item.multiSelect))}
              onSetOther={(text) => setDraft((current) => setOther(current, item.id, text))}
            />
          ))}
        </div>

        {summaryError && (
          <div style={{ marginTop: 'var(--space-4)' }}>
            <Notice tone="warning" live>
              {summaryError}
            </Notice>
          </div>
        )}
        {sendError && (
          <div style={{ marginTop: 'var(--space-4)' }}>
            <Notice tone="warning" live>
              {sendError}
            </Notice>
          </div>
        )}

        <div
          className="row"
          style={{ justifyContent: 'space-between', alignItems: 'center', gap: 'var(--space-3)', flexWrap: 'wrap', marginTop: 'var(--space-5)' }}
        >
          <div className="row" style={{ gap: 'var(--space-4)', alignItems: 'center', flexWrap: 'wrap' }}>
            {multiple && (
              <span className="caption muted">
                {answeredCount} of {question.questions.length} answered
              </span>
            )}
            {onReplyInOwnWords && !answeringInComposer && (
              <button type="button" className="link" onClick={onReplyInOwnWords}>
                Reply in your own words
              </button>
            )}
            {onReplyInOwnWords && answeringInComposer && (
              <span className="caption muted">Or type your answer in the message box below.</span>
            )}
            <Button variant="quiet" type="button" onClick={() => void submit({ skipped: true })} loading={answer.isPending}>
              Let the agent decide
            </Button>
          </div>
          <Button type="submit" loading={answer.isPending}>
            {multiple ? 'Send answers' : 'Send answer'}
          </Button>
        </div>
      </fieldset>

      <span role="status" id={announceId} className="visually-hidden">
        {announcement}
      </span>
    </form>
  )
}
