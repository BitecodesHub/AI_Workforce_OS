// @find: tests for cited answer model, citation markers, sliceWords, wordCount, plainText, answer text
// @what: Tests the cited-answer data helpers: word counts and citation slicing.
// @flow: Covers citedAnswerModel.ts
import { describe, expect, it } from 'vitest'
import {
  ANSWER,
  DECLINE,
  DOCUMENT_NAME,
  HIT_PAGES,
  PAGE_COUNT,
  PASSAGES,
  plainText,
  sliceWords,
  wordCount,
} from './citedAnswerModel'
import type { Segment } from './citedAnswerModel'

describe('citedAnswerModel', () => {
  it('counts the same words as the plain text of the answer', () => {
    const text = plainText(ANSWER)
    expect(text).toBe(
      'It does not send it straight away. Outbound actions go to an approval queue, where a person ' +
        'reviews them before they run . The proposal gives that review to a manager or the CEO .',
    )
    expect(wordCount(ANSWER)).toBe(text.split(/\s+/).filter(Boolean).length)
    expect(wordCount(ANSWER)).toBe(35)
  })

  it('never splits a citation marker, and shows each one with the word it follows', () => {
    const total = wordCount(ANSWER)
    const cites = (segments: Segment[]) =>
      segments.filter((segment) => segment.kind === 'cite').map((segment) => (segment.kind === 'cite' ? segment.n : 0))
    let previousLength = 0

    for (let n = 0; n <= total; n += 1) {
      const slice = sliceWords(ANSWER, n)
      expect(wordCount(slice)).toBe(n)
      for (const segment of slice) {
        if (segment.kind === 'cite') expect([1, 2]).toContain(segment.n)
      }
      // The revealed text only ever grows.
      const length = plainText(slice).length
      expect(length).toBeGreaterThanOrEqual(previousLength)
      previousLength = length
    }

    expect(sliceWords(ANSWER, 0)).toEqual([])
    expect(cites(sliceWords(ANSWER, 21))).toEqual([])
    expect(cites(sliceWords(ANSWER, 22))).toEqual([1])
    expect(cites(sliceWords(ANSWER, 33))).toEqual([1])
    expect(cites(sliceWords(ANSWER, 34))).toEqual([1, 2])
    expect(sliceWords(ANSWER, total)).toEqual(ANSWER)
    expect(sliceWords(ANSWER, total + 10)).toEqual(ANSWER)
  })

  it('keeps passage 1 on page 6 and passage 2 on page 4, quoted verbatim', () => {
    expect(PASSAGES[1].page).toBe(6)
    expect(PASSAGES[1].quote).toBe(
      'An approval queue where humans review outbound and high-impact actions before they run',
    )
    expect(PASSAGES[2].page).toBe(4)
    expect(PASSAGES[2].quote).toBe(
      'Approve agent actions - Manager / CEO - Review outbound or high-impact actions in an approval queue before they execute.',
    )
    expect(HIT_PAGES).toEqual([6, 4])
    expect(HIT_PAGES.every((page) => page >= 1 && page <= PAGE_COUNT)).toBe(true)
  })

  it('names the document and declines without guessing', () => {
    expect(DOCUMENT_NAME).toBe('Project_Proposal.pdf')
    expect(PAGE_COUNT).toBe(8)
    expect(DECLINE).toBe('No document in this workspace supports an answer to that question.')
  })
})
