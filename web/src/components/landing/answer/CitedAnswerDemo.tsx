// @find: cited answer demo, citations, sources, answer with sources, retrieval, RAG, grounding, knowledge base demo, decline unsupported question, Project_Proposal.pdf, CitedAnswerDemo, #cited
// @what: The landing page demo that replays retrieval, writes an answer word by word with citation markers, and declines an unsupported question.
// @flow: Used by DemoStage; data from citedAnswerModel
import { Fragment, useEffect, useReducer } from 'react'
import type { Dispatch, ReactElement } from 'react'
import { Button, Tag } from '../../ui'
import { DemoFrame } from '../shared/DemoFrame'
import type { DemoVoice } from '../shared/voice'
import { Icon } from '../shared/Icon'
import { useLandingMotion } from '../shared/LandingRoot'
import { useInView } from '../../../hooks/useInView'
import { useSequence } from '../../../hooks/useSequence'
import type { SequenceStep } from '../../../hooks/useSequence'
import {
  ANSWER,
  DECLINE,
  DOCUMENT_NAME,
  HIT_PAGES,
  PAGE_COUNT,
  PASSAGES,
  QUESTIONS,
  QUESTION_ORDER,
  sliceWords,
  wordCount,
} from './citedAnswerModel'
import type { CitationNumber, PageNumber, QuestionId, Segment } from './citedAnswerModel'

/*
 * Cited answers, simulated.
 *
 * Retrieval is replayed, not run: the pages are scanned, two passages come back, and the answer is
 * written out word by word with a marker on each claim. Hovering, focusing or pressing a marker
 * shows the passage and the page behind it. Asked something the document cannot support, the
 * agent says so and cites nothing.
 */

/* ---- State ------------------------------------------------------------------------------------- */

/** The demo's phases: idle, retrieving (scanning the pages), answering (revealing words), answered and declined. */
type Phase = 'idle' | 'retrieving' | 'answering' | 'answered' | 'declined'

/** Demo state: phase, the question asked, how many words of the answer are revealed, the pages retrieval hit, the citation previewed (active) and the one pinned by a press, whether autoplay has happened, the live announcement, and a counter of questions asked (which restarts the page scan animation). */
type State = {
  phase: Phase
  question: QuestionId | null
  words: number
  hits: ReadonlyArray<PageNumber>
  active: CitationNumber | null
  pinned: CitationNumber | null
  autoplayed: boolean
  announcement: string
  /** Counts questions asked, so the page scan restarts on every new question. */
  asked: number
}

/** Events of the reducer: ASK, HITS, DECLINE, ANSWERING, WORDS, ANSWERED, CLEAR, and PREVIEW, UNPREVIEW and PIN for citation markers. */
type Action =
  | { type: 'ASK'; question: QuestionId }
  | { type: 'HITS' }
  | { type: 'DECLINE' }
  | { type: 'ANSWERING' }
  | { type: 'WORDS'; words: number }
  | { type: 'ANSWERED' }
  | { type: 'CLEAR' }
  | { type: 'PREVIEW'; n: CitationNumber }
  | { type: 'UNPREVIEW'; n: CitationNumber }
  | { type: 'PIN'; n: CitationNumber }

/** Word count of the whole answer, and the timings in milliseconds: when hits appear (1100), when the answer starts (1400) and the delay per word (22). */
const TOTAL_WORDS = wordCount(ANSWER)

const TIMING = { hits: 1100, answer: 1400, word: 22 } as const

/** Screen reader announcements for searching, a declined question and a finished answer. */
const ANNOUNCE = {
  searching: 'Searching the workspace documents.',
  declined: 'No document supports an answer. The agent did not guess.',
  answered: `Answer ready. It cites two passages from ${DOCUMENT_NAME}, pages ${PASSAGES[1].page} and ${PASSAGES[2].page}.`,
} as const

/** The starting state: nothing asked. */
const IDLE: State = {
  phase: 'idle',
  question: null,
  words: 0,
  hits: [],
  active: null,
  pinned: null,
  autoplayed: false,
  announcement: '',
  asked: 0,
}

/** Transitions of the demo. ASK starts retrieving and resets everything else; HITS, ANSWERING, WORDS, ANSWERED and DECLINE apply only in the phase they belong to (so a late timer after Clear is ignored); CLEAR returns to idle but keeps the autoplayed flag; PREVIEW and UNPREVIEW follow hover and focus on a marker (an unpreview falls back to the pinned one); PIN toggles the pinned citation. */
function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'ASK':
      return {
        ...IDLE,
        phase: 'retrieving',
        question: action.question,
        autoplayed: true,
        announcement: ANNOUNCE.searching,
        asked: state.asked + 1,
      }
    case 'HITS':
      return state.phase === 'retrieving' ? { ...state, hits: HIT_PAGES } : state
    case 'DECLINE':
      return state.phase === 'retrieving'
        ? { ...state, phase: 'declined', hits: [], announcement: ANNOUNCE.declined }
        : state
    case 'ANSWERING':
      return state.phase === 'retrieving' ? { ...state, phase: 'answering', words: 0 } : state
    case 'WORDS':
      return state.phase === 'answering' ? { ...state, words: action.words } : state
    case 'ANSWERED':
      return state.phase === 'answering'
        ? { ...state, phase: 'answered', words: TOTAL_WORDS, announcement: ANNOUNCE.answered }
        : state
    case 'CLEAR':
      return { ...IDLE, autoplayed: true, asked: state.asked }
    case 'PREVIEW':
      return { ...state, active: action.n }
    case 'UNPREVIEW':
      return state.active === action.n ? { ...state, active: state.pinned } : state
    case 'PIN': {
      const pinned = state.pinned === action.n ? null : action.n
      return { ...state, pinned, active: action.n }
    }
  }
}

/** Under reduced motion the demo opens on the answered question, every word shown. */
/** Initial state: idle normally; with reduced motion the demo opens on the answered first question with every word and both hits shown. */
function init(reduced: boolean): State {
  if (!reduced) return IDLE
  return {
    ...IDLE,
    phase: 'answered',
    question: 'send',
    words: TOTAL_WORDS,
    hits: HIT_PAGES,
    autoplayed: true,
  }
}

/** Timed steps for a question (played with useSequence): ASK at 0. The unsupported question (jupiter) declines after 1100 ms. The supported one shows the hits at 1100 ms, starts answering at 1400 ms, adds one word every 22 ms, and finishes after the last word. */
function askSteps(question: QuestionId, dispatch: Dispatch<Action>): SequenceStep[] {
  const steps: SequenceStep[] = [{ at: 0, run: () => dispatch({ type: 'ASK', question }) }]
  if (question === 'jupiter') {
    steps.push({ at: TIMING.hits, run: () => dispatch({ type: 'DECLINE' }) })
    return steps
  }
  steps.push({ at: TIMING.hits, run: () => dispatch({ type: 'HITS' }) })
  steps.push({ at: TIMING.answer, run: () => dispatch({ type: 'ANSWERING' }) })
  for (let words = 1; words <= TOTAL_WORDS; words += 1) {
    steps.push({ at: TIMING.answer + words * TIMING.word, run: () => dispatch({ type: 'WORDS', words }) })
  }
  steps.push({ at: TIMING.answer + TOTAL_WORDS * TIMING.word, run: () => dispatch({ type: 'ANSWERED' }) })
  return steps
}

/** The page numbers of the document and the citation numbers, used to draw the page grid and the passages. */
const PAGES: ReadonlyArray<number> = Array.from({ length: PAGE_COUNT }, (_, index) => index + 1)

const CITATIONS: ReadonlyArray<CitationNumber> = [1, 2]

/* ---- Parts ---------------------------------------------------------------------------------------- */

/** Props of a citation marker: its number, whether it is previewed or pinned, and the reducer dispatch. */
type MarkerProps = {
  n: CitationNumber
  active: boolean
  pinned: boolean
  dispatch: Dispatch<Action>
}

/** A numbered citation button in the answer. Hover or focus previews the passage and page behind it, leaving removes the preview, and a press pins it (aria-pressed). It controls the passage list item and its label names the source and page. */
function CitationMarker({ n, active, pinned, dispatch }: MarkerProps): ReactElement {
  const preview = () => dispatch({ type: 'PREVIEW', n })
  const unpreview = () => dispatch({ type: 'UNPREVIEW', n })
  return (
    <button
      type="button"
      className="lp-cite-marker"
      aria-label={`Source ${n}: ${DOCUMENT_NAME}, page ${PASSAGES[n].page}`}
      aria-controls={`cite-passage-${n}`}
      aria-pressed={pinned}
      data-active={active ? 'true' : 'false'}
      onMouseEnter={preview}
      onFocus={preview}
      onMouseLeave={unpreview}
      onBlur={unpreview}
      onClick={() => dispatch({ type: 'PIN', n })}
    >
      <span className="lp-cite-marker-pill">{n}</span>
    </button>
  )
}

/** The (partially revealed) answer: text segments as they are and citation segments as CitationMarker buttons. */
function AnswerText({ segments, state, dispatch }: { segments: ReadonlyArray<Segment>; state: State; dispatch: Dispatch<Action> }): ReactElement {
  return (
    <p className="lp-cite-answer-text">
      {segments.map((segment, index) =>
        segment.kind === 'text' ? (
          <Fragment key={index}>{segment.text}</Fragment>
        ) : (
          <CitationMarker
            key={index}
            n={segment.n}
            active={state.active === segment.n}
            pinned={state.pinned === segment.n}
            dispatch={dispatch}
          />
        ),
      )}
    </p>
  )
}

/** The status line under the page grid: no matches (declined), the number of matching passages (technical voice shows keyword, vector and merged counts), "Searching" or nothing. */
function retrievalLine(state: State, voice: DemoVoice): string | null {
  if (state.phase === 'declined') return voice === 'plain' ? 'Nothing in the document covers this' : '0 passages matched'
  if (state.hits.length > 0) return voice === 'plain' ? 'Found 2 matching passages' : 'keyword 2 · vector 2 · merged 2 passages'
  if (state.phase === 'retrieving') return 'Searching…'
  return null
}

/* The same demo in the two voices: the plain one for the home page, the technical one for IT. */
const COPY: Record<DemoVoice, { name: string; lead: string; footnote: string; source: string; steps: readonly string[] }> = {
  technical: {
    name: 'Cited answers',
    lead: 'Hybrid keyword and vector retrieval over the workspace documents, with the document and page behind every claim.',
    footnote:
      'Illustrative: the real screen replies with a short sentence and lists passages like these below it. Retrieval is replayed here, not run.',
    source: 'answering from 1 indexed document',
    steps: [
      'Ask what happens when an agent wants to send an email.',
      'Follow each numbered citation to its page in the document.',
      'Ask about Jupiter: with nothing to cite, the agent declines.',
    ],
  },
  plain: {
    name: 'Answers with sources',
    lead: 'Ask a question and it answers from your own documents, pointing to the page behind each part of the answer.',
    footnote:
      'For illustration: in the product, the reply is a short sentence with the matching passages listed under it. The search is replayed here.',
    source: 'answering from 1 document',
    steps: [
      'Ask what happens when an assistant wants to send an email.',
      'Follow each numbered source to its page in the document.',
      'Ask about Jupiter. Nothing in the document covers it, so it says so.',
    ],
  },
}

/* ---- Demo ------------------------------------------------------------------------------------------ */

/** Props: voice, technical (default) or plain. */
export type CitedAnswerDemoProps = { voice?: DemoVoice }

// @find: CitedAnswerDemo component, cited answer demo
/** Landing demo 04: a simulated cited answer. State is a reducer (reducer, init); useSequence replays retrieval and the word-by-word reveal; it autoplays the first question once the stage is 45 percent in view and not again. Two chips ask the supported question (two passages found, answer with citation markers) or an unsupported one (declined, nothing cited). Hovering, focusing or pressing a marker highlights the page and passage behind it. Clear cancels any running sequence. Nothing is actually retrieved: the search is replayed from static data. Rendered by DemoStage inside DemoFrame, with copy for the two voices. */
export function CitedAnswerDemo({ voice = 'technical' }: CitedAnswerDemoProps = {}): ReactElement {
  const copy = COPY[voice]
  const { reduced } = useLandingMotion()
  const [state, dispatch] = useReducer(reducer, reduced, init)
  const { play, cancel } = useSequence()
  const { ref: stageRef, inView } = useInView<HTMLDivElement>({ threshold: 0.45 })

  useEffect(() => {
    if (inView && state.phase === 'idle' && !state.autoplayed) play(askSteps('send', dispatch))
  }, [inView, state.phase, state.autoplayed, play])

  const ask = (question: QuestionId) => play(askSteps(question, dispatch))

  const clear = () => {
    cancel()
    dispatch({ type: 'CLEAR' })
  }

  const answering = state.phase === 'answering'
  const showsAnswer = answering || state.phase === 'answered'
  const activePage = state.active === null ? null : PASSAGES[state.active].page
  const retrieval = retrievalLine(state, voice)

  return (
    <DemoFrame
      area="cited"
      index="04"
      name={copy.name}
      title="It answers from your documents"
      lead={copy.lead}
      footnote={copy.footnote}
      steps={copy.steps}
      showIndex={voice === 'technical'}
      status={state.announcement}
      busy={answering}
    >
      <div ref={stageRef} className="lp-cite-stage">
        <div className="lp-cite-chat">
          <div className="lp-cite-controls">
            <div className="lp-cite-ask" role="group" aria-label="Ask a question">
              {QUESTION_ORDER.map((question) => (
                <button
                  key={question}
                  type="button"
                  className="lp-chip"
                  aria-pressed={state.question === question}
                  onClick={() => ask(question)}
                >
                  {QUESTIONS[question]}
                </button>
              ))}
            </div>
            <Button variant="quiet" className="lp-cite-clear" onClick={clear}>
              Clear
            </Button>
          </div>

          <div className="lp-cite-thread">
            {state.question === null ? (
              <p className="caption lp-cite-empty">Pick a question. The answer names the page behind each claim.</p>
            ) : (
              <p key={`ask-${state.asked}`} className="lp-cite-user lp-anim-rise">
                {QUESTIONS[state.question]}
              </p>
            )}

            {(showsAnswer || state.phase === 'declined') && (
              <div className="lp-cite-answer lp-inset lp-anim-rise">
                <div className="lp-cite-answer-head">
                  <Tag tone="growth" withDot>
                    Research
                  </Tag>
                  <span className="caption">{copy.source}</span>
                </div>
                {state.phase === 'declined' ? (
                  <>
                    <p className="lp-cite-answer-text">{DECLINE}</p>
                    <div className="lp-cite-answer-foot">
                      <Tag tone="neutral">No citation</Tag>
                      <span className="caption">The agent does not guess.</span>
                    </div>
                  </>
                ) : (
                  <AnswerText segments={sliceWords(ANSWER, state.words)} state={state} dispatch={dispatch} />
                )}
              </div>
            )}
          </div>
        </div>

        <aside className="lp-cite-sources" aria-label="Sources">
          <div className="lp-cite-doc">
            <Icon name="document" size={16} />
            <span className="lp-cite-docname">{DOCUMENT_NAME}</span>
            <Tag tone="success">Indexed</Tag>
          </div>

          <div
            key={`pages-${state.asked}`}
            className="lp-cite-pages lp-scan-host"
            data-scanning={state.phase === 'retrieving' ? 'true' : 'false'}
            aria-hidden="true"
          >
            {PAGES.map((page) => {
              const hit = (state.hits as ReadonlyArray<number>).includes(page)
              return (
                <span
                  key={page}
                  className="lp-cite-page"
                  data-hit={hit ? 'true' : 'false'}
                  data-active={activePage === page ? 'true' : 'false'}
                >
                  {hit && <span className="lp-cite-ring lp-anim-rise" />}
                  <span className="lp-cite-page-number">{page}</span>
                </span>
              )
            })}
          </div>

          {retrieval !== null && <p className="lp-cite-retrieval lp-mono">{retrieval}</p>}

          {state.hits.length > 0 && (
            <ol className="lp-cite-passages">
              {CITATIONS.map((n) => (
                <li
                  key={n}
                  id={`cite-passage-${n}`}
                  className="lp-cite-passage lp-anim-rise"
                  data-active={state.active === n ? 'true' : 'false'}
                >
                  <span className="lp-cite-passage-head">
                    {DOCUMENT_NAME} · page {PASSAGES[n].page}
                  </span>
                  <blockquote className="lp-cite-quote">{PASSAGES[n].quote}</blockquote>
                </li>
              ))}
            </ol>
          )}

          {state.phase === 'declined' && (
            <p className="lp-cite-none">
              <Icon name="document" />
              Nothing to cite
            </p>
          )}
        </aside>
      </div>
    </DemoFrame>
  )
}
