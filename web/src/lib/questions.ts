// @find: agent questions, answer question, question card, options, Other free text, validate answer, closes in, question error, answer draft, ask the user
// @what: Builds and validates an answer to an agent question (options, Other, free text) for the shared QuestionCard.
// @flow: Used by QuestionCard in Chat, the Orchestrator sheet, Approvals page and run traces; submits via queries.ts answer mutation
import { ApiError, describeApiError } from './api'
import type { AnsweredVia, AnswerQuestionInput, QuestionAnswerItem, RunQuestion } from './queries'

/*
 * Building and validating an answer to an agent's question, shared by QuestionCard wherever it
 * appears (Chat, the Orchestrator sheet, the Approvals page, a run's own trace). The draft is kept
 * per question item id, so a multi-question card tracks each question's own selection, its own
 * "Other" state and its own free text independently.
 */

export type AnswerDraft = Record<string, { selected: string[]; otherOn: boolean; other: string }>

// @find: empty draft, new answer
/** A blank draft, one entry per question item, ready for a freshly opened card. */
export function emptyDraft(question: RunQuestion): AnswerDraft {
  const draft: AnswerDraft = {}
  for (const item of question.questions) draft[item.id] = { selected: [], otherOn: false, other: '' }
  return draft
}

function entryOf(draft: AnswerDraft, questionId: string) {
  return draft[questionId] ?? { selected: [], otherOn: false, other: '' }
}

// @find: toggle option, pick answer choice
/**
 * Toggles one option. A single-choice question replaces the selection outright and turns "Other"
 * off, since it takes exactly one answer; a multi-select question adds or removes the option
 * alongside whatever else, including "Other", is already chosen.
 */
export function toggleOption(draft: AnswerDraft, questionId: string, label: string, multi: boolean): AnswerDraft {
  const entry = entryOf(draft, questionId)
  if (!multi) {
    return { ...draft, [questionId]: { selected: [label], otherOn: false, other: entry.other } }
  }
  const selected = entry.selected.includes(label)
    ? entry.selected.filter((value) => value !== label)
    : [...entry.selected, label]
  return { ...draft, [questionId]: { ...entry, selected } }
}

// @find: toggle other, free text option
/**
 * Toggles the "Other" control. Turning it on for a single-choice question clears any selected
 * option, since the two are mutually exclusive there; turning it off clears the free text.
 */
export function toggleOther(draft: AnswerDraft, questionId: string, multi: boolean): AnswerDraft {
  const entry = entryOf(draft, questionId)
  const otherOn = !entry.otherOn
  return {
    ...draft,
    [questionId]: {
      selected: otherOn && !multi ? [] : entry.selected,
      otherOn,
      other: otherOn ? entry.other : '',
    },
  }
}

export function setOther(draft: AnswerDraft, questionId: string, text: string): AnswerDraft {
  const entry = entryOf(draft, questionId)
  return { ...draft, [questionId]: { ...entry, other: text } }
}

// @find: validate answer, required question
/** Question id to error text, for every question the draft does not yet answer validly. */
export function validateDraft(question: RunQuestion, draft: AnswerDraft): Record<string, string> {
  const errors: Record<string, string> = {}
  for (const item of question.questions) {
    const entry = entryOf(draft, item.id)
    const otherFilled = entry.otherOn && entry.other.trim() !== ''
    const count = entry.selected.length + (otherFilled ? 1 : 0)
    if (item.multiSelect) {
      if (count < 1) errors[item.id] = 'Choose at least one option, or use Other to write your own answer.'
    } else if (count !== 1) {
      errors[item.id] = 'Choose one option, or use Other to write your own answer.'
    }
  }
  return errors
}

// @find: answer input, submit answer payload
/** The draft in the shape the answer endpoint takes. Does not set `note` or `skipped`. */
export function draftToInput(question: RunQuestion, draft: AnswerDraft, via: AnsweredVia): AnswerQuestionInput {
  const answers: QuestionAnswerItem[] = question.questions.map((item) => {
    const entry = entryOf(draft, item.id)
    const other = entry.otherOn ? entry.other.trim() : ''
    return { questionId: item.id, selected: entry.selected, other: other || null }
  })
  return { id: question.id, answers, via }
}

/** What to say a question was answered with, for its closed state ("You answered: Last quarter"). */
export function answerSummary(question: RunQuestion): string {
  const answer = question.answer
  if (!answer) return ''
  if (answer.skipped) return 'used its own judgement'
  const parts = answer.answers
    .map((item) => {
      const values = item.other ? [...item.selected, item.other] : item.selected
      return values.join(', ')
    })
    .filter((value) => value !== '')
  if (parts.length) return parts.join('; ')
  return answer.note?.trim() || ''
}

// @find: question closes in, expiry countdown
/** "Closes in 23 h", "Closes in 12 min", or "Closing now" once the deadline has all but arrived. */
export function closesIn(expiresAt: string, now: number): string {
  const target = Date.parse(expiresAt)
  if (Number.isNaN(target)) return 'Closing now'
  const ms = target - now
  const minutes = Math.round(ms / 60_000)
  if (minutes < 1) return 'Closing now'
  if (minutes < 60) return `Closes in ${minutes} min`
  return `Closes in ${Math.round(minutes / 60)} h`
}

// @find: question error, could not answer
/** One sentence for a failed answer or extend attempt. */
export function questionError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'question_already_answered') return 'Someone already answered this question.'
    if (error.code === 'question_closed') return 'This question is no longer open.'
    if (error.isPermissionDenied) {
      return 'Only the person who asked for this work, or someone who can cancel work, can answer it.'
    }
    if (error.code === 'validation_failed') {
      const [first] = Object.values(error.fields)
      if (first) return first
    }
  }
  return describeApiError(error)
}
