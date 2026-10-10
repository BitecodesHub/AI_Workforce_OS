// @find: cited answer model, citations data, answer passages, document name, page count, questions, decline message, sliceWords, wordCount, plainText, Project_Proposal.pdf
// @what: Data and helpers for the cited-answer demo: the questions, answer segments, passages and decline text.
// @flow: Used by CitedAnswerDemo
/*
 * The cited-answer demo, as data.
 *
 * The answer is written from the two passages below and nothing else, and each claim carries the
 * marker of the passage behind it. Both quotes are verbatim from Project_Proposal.pdf, an
 * eight-page document, at the pages given. The second question has no support in that document,
 * so the honest answer is to decline rather than to guess.
 */

export const DOCUMENT_NAME = 'Project_Proposal.pdf'

export const PAGE_COUNT = 8

export type QuestionId = 'send' | 'jupiter'

export const QUESTION_ORDER: ReadonlyArray<QuestionId> = ['send', 'jupiter']

export const QUESTIONS: Record<QuestionId, string> = {
  send: 'What happens when an agent wants to send an email?',
  jupiter: 'How many moons does Jupiter have?',
}

export type CitationNumber = 1 | 2

export type PageNumber = 4 | 6

export type Segment = { kind: 'text'; text: string } | { kind: 'cite'; n: CitationNumber }

export const ANSWER: ReadonlyArray<Segment> = [
  {
    kind: 'text',
    text:
      'It does not send it straight away. Outbound actions go to an approval queue, where a person ' +
      'reviews them before they run ',
  },
  { kind: 'cite', n: 1 },
  { kind: 'text', text: '. The proposal gives that review to a manager or the CEO ' },
  { kind: 'cite', n: 2 },
  { kind: 'text', text: '.' },
]

export const PASSAGES: Record<CitationNumber, { page: PageNumber; quote: string }> = {
  1: {
    page: 6,
    quote: 'An approval queue where humans review outbound and high-impact actions before they run',
  },
  2: {
    page: 4,
    quote:
      'Approve agent actions - Manager / CEO - Review outbound or high-impact actions in an approval ' +
      'queue before they execute.',
  },
}

/** The pages retrieval returns for the answerable question, in rank order. */
export const HIT_PAGES: ReadonlyArray<PageNumber> = [PASSAGES[1].page, PASSAGES[2].page]

export const DECLINE = 'No document in this workspace supports an answer to that question.'

const WORD = /\S+/g

/** The answer's text with the citation markers left out. */
// @find: plainText, answer as plain text
export function plainText(segments: ReadonlyArray<Segment>): string {
  return segments.map((segment) => (segment.kind === 'text' ? segment.text : '')).join('')
}

/**
 * The number of words the reveal steps through. Citation markers are not words: each one appears
 * together with the word it follows.
 */
// @find: wordCount, answer word count
export function wordCount(segments: ReadonlyArray<Segment>): number {
  let count = 0
  for (const segment of segments) {
    if (segment.kind === 'text') count += segment.text.match(WORD)?.length ?? 0
  }
  return count
}

/**
 * The first n words of the answer, with their original spacing. A citation marker is whole or
 * absent, never split, and it appears as soon as every word before it has.
 */
// @find: sliceWords, reveal answer word by word
export function sliceWords(segments: ReadonlyArray<Segment>, n: number): Segment[] {
  const out: Segment[] = []
  let left = Math.max(0, Math.floor(n))
  for (const segment of segments) {
    if (segment.kind === 'cite') {
      out.push(segment)
      continue
    }
    const words = [...segment.text.matchAll(WORD)]
    if (words.length <= left) {
      out.push(segment)
      left -= words.length
      continue
    }
    const last = left > 0 ? words[left - 1] : undefined
    if (last) out.push({ kind: 'text', text: segment.text.slice(0, last.index + last[0].length) })
    break
  }
  return out
}
