// @find: tests for questions, answer draft, validateDraft, draftToInput, closesIn, questionError
// @what: Unit tests for agent question answers.
import { describe, expect, it } from 'vitest'
import { ApiError } from './api'
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
} from './questions'
import type { AnswerDraft } from './questions'
import type { RunQuestion } from './queries'

const QUESTION: RunQuestion = {
  id: 'q1',
  runId: 'r1',
  taskId: null,
  goalId: 'g1',
  conversationId: null,
  agentId: 'a1',
  goalTitle: 'Plan the team offsite',
  status: 'pending',
  questions: [
    {
      id: 'i1',
      header: 'Date range',
      question: 'Which period should the report cover?',
      multiSelect: false,
      options: [
        { label: 'Last quarter', description: 'July to September 2026.', recommended: true },
        { label: 'Year to date', description: 'January to today.', recommended: false },
      ],
    },
    {
      id: 'i2',
      header: 'Contents',
      question: 'What should it include?',
      multiSelect: true,
      options: [
        { label: 'A summary', description: '' },
        { label: 'Next steps', description: '' },
      ],
    },
  ],
  answer: null,
  answeredBy: null,
  answeredVia: null,
  answeredAt: null,
  requestedBy: 'u1',
  createdAt: '2026-09-28T00:00:00Z',
  expiresAt: '2026-09-29T00:00:00Z',
  closedReason: null,
  canAnswer: true,
  extendable: false,
  runStatus: 'waiting_input',
}

describe('emptyDraft', () => {
  it('starts every question with nothing selected', () => {
    expect(emptyDraft(QUESTION)).toEqual({
      i1: { selected: [], otherOn: false, other: '' },
      i2: { selected: [], otherOn: false, other: '' },
    })
  })
})

describe('toggleOption', () => {
  it('replaces the selection for a single-choice question', () => {
    let draft = emptyDraft(QUESTION)
    draft = toggleOption(draft, 'i1', 'Last quarter', false)
    expect(draft.i1).toEqual({ selected: ['Last quarter'], otherOn: false, other: '' })
    draft = toggleOption(draft, 'i1', 'Year to date', false)
    expect(draft.i1!.selected).toEqual(['Year to date'])
  })

  it('turns off Other when a single-choice option is picked', () => {
    let draft = emptyDraft(QUESTION)
    draft = toggleOther(draft, 'i1', false)
    draft = toggleOption(draft, 'i1', 'Last quarter', false)
    expect(draft.i1).toEqual({ selected: ['Last quarter'], otherOn: false, other: '' })
  })

  it('adds and removes options for a multi-select question', () => {
    let draft = emptyDraft(QUESTION)
    draft = toggleOption(draft, 'i2', 'A summary', true)
    draft = toggleOption(draft, 'i2', 'Next steps', true)
    expect(draft.i2!.selected).toEqual(['A summary', 'Next steps'])
    draft = toggleOption(draft, 'i2', 'A summary', true)
    expect(draft.i2!.selected).toEqual(['Next steps'])
  })
})

describe('toggleOther and setOther', () => {
  it('clears the single-choice selection when Other turns on, and the text when it turns off', () => {
    let draft = emptyDraft(QUESTION)
    draft = toggleOption(draft, 'i1', 'Last quarter', false)
    draft = toggleOther(draft, 'i1', false)
    expect(draft.i1).toEqual({ selected: [], otherOn: true, other: '' })
    draft = setOther(draft, 'i1', 'Whatever works best')
    expect(draft.i1!.other).toBe('Whatever works best')
    draft = toggleOther(draft, 'i1', false)
    expect(draft.i1).toEqual({ selected: [], otherOn: false, other: '' })
  })

  it('keeps a multi-select question’s picked options when Other also turns on', () => {
    let draft = emptyDraft(QUESTION)
    draft = toggleOption(draft, 'i2', 'A summary', true)
    draft = toggleOther(draft, 'i2', true)
    expect(draft.i2!.selected).toEqual(['A summary'])
    expect(draft.i2!.otherOn).toBe(true)
  })
})

describe('validateDraft', () => {
  it('reports every unanswered question', () => {
    const errors = validateDraft(QUESTION, emptyDraft(QUESTION))
    expect(Object.keys(errors).sort()).toEqual(['i1', 'i2'])
  })

  it('passes once every question has exactly what it needs', () => {
    let draft = emptyDraft(QUESTION)
    draft = toggleOption(draft, 'i1', 'Last quarter', false)
    draft = toggleOption(draft, 'i2', 'A summary', true)
    expect(validateDraft(QUESTION, draft)).toEqual({})
  })

  it('rejects a single-choice question with more than one thing selected', () => {
    const draft: AnswerDraft = {
      i1: { selected: ['Last quarter', 'Year to date'], otherOn: false, other: '' },
      i2: { selected: ['A summary'], otherOn: false, other: '' },
    }
    expect(Object.keys(validateDraft(QUESTION, draft))).toEqual(['i1'])
  })

  it('accepts Other alone for either kind of question', () => {
    let draft = emptyDraft(QUESTION)
    draft = toggleOther(draft, 'i1', false)
    draft = setOther(draft, 'i1', 'My own answer')
    draft = toggleOther(draft, 'i2', true)
    draft = setOther(draft, 'i2', 'My own answer')
    expect(validateDraft(QUESTION, draft)).toEqual({})
  })
})

describe('draftToInput', () => {
  it('builds the wire shape, with a null other where none was written', () => {
    let draft = emptyDraft(QUESTION)
    draft = toggleOption(draft, 'i1', 'Last quarter', false)
    draft = toggleOption(draft, 'i2', 'A summary', true)
    expect(draftToInput(QUESTION, draft, 'chat')).toEqual({
      id: 'q1',
      answers: [
        { questionId: 'i1', selected: ['Last quarter'], other: null },
        { questionId: 'i2', selected: ['A summary'], other: null },
      ],
      via: 'chat',
    })
  })

  it('trims and includes the Other text only while Other is on', () => {
    let draft = emptyDraft(QUESTION)
    draft = toggleOther(draft, 'i1', false)
    draft = setOther(draft, 'i1', '  Whatever works best  ')
    draft = toggleOption(draft, 'i2', 'Next steps', true)
    const input = draftToInput(QUESTION, draft, 'orchestrator')
    expect(input.answers[0]).toEqual({ questionId: 'i1', selected: [], other: 'Whatever works best' })
  })
})

describe('answerSummary', () => {
  it('reads what was skipped', () => {
    expect(
      answerSummary({ ...QUESTION, answer: { answers: [], note: null, skipped: true } }),
    ).toBe('used its own judgement')
  })

  it('joins the selected options and any Other text', () => {
    const answered: RunQuestion = {
      ...QUESTION,
      answer: {
        answers: [
          { questionId: 'i1', selected: ['Last quarter'], other: null },
          { questionId: 'i2', selected: ['A summary'], other: 'Keep it short' },
        ],
        note: null,
        skipped: false,
      },
    }
    expect(answerSummary(answered)).toBe('Last quarter; A summary, Keep it short')
  })

  it('falls back to the note when nothing was selected', () => {
    const answered: RunQuestion = { ...QUESTION, answer: { answers: [], note: 'Just use your judgement', skipped: false } }
    expect(answerSummary(answered)).toBe('Just use your judgement')
  })

  it('reads empty without an answer at all', () => {
    expect(answerSummary(QUESTION)).toBe('')
  })
})

describe('closesIn', () => {
  const now = Date.parse('2026-09-28T00:00:00Z')

  it('reads hours and minutes', () => {
    expect(closesIn('2026-09-28T23:00:00Z', now)).toBe('Closes in 23 h')
    expect(closesIn('2026-09-28T00:12:00Z', now)).toBe('Closes in 12 min')
  })

  it('reads closing now once the deadline has all but arrived, or passed', () => {
    expect(closesIn('2026-09-28T00:00:20Z', now)).toBe('Closing now')
    expect(closesIn('2026-09-27T23:00:00Z', now)).toBe('Closing now')
  })
})

describe('questionError', () => {
  it('maps the known question codes', () => {
    expect(questionError(new ApiError(409, 'question_already_answered', 'x', false, {}))).toBe(
      'Someone already answered this question.',
    )
    expect(questionError(new ApiError(409, 'question_closed', 'x', false, {}))).toBe('This question is no longer open.')
    expect(questionError(new ApiError(403, 'permission_denied', 'x', false, {}))).toBe(
      'Only the person who asked for this work, or someone who can cancel work, can answer it.',
    )
  })

  it('reads the field text for a validation failure', () => {
    const error = new ApiError(422, 'validation_failed', 'x', false, { answers: 'answer every question' })
    expect(questionError(error)).toBe('answer every question')
  })

  it('falls back to the generic sentence for anything else', () => {
    expect(questionError(new Error('boom'))).toBe('Something went wrong. Try again.')
  })
})
