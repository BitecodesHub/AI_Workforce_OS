import { useEffect, useId, useRef, useState } from 'react'
import type { FormEvent, KeyboardEvent } from 'react'
import { Button, Card, EmptyState, Eyebrow, Notice, PageHeader, Textarea, Time } from '../components/ui'
import { EmptyIcon, PermissionState } from '../components/ui/QueryState'
import { useReducedMotion } from '../hooks/useReducedMotion'
import { ApiError, describeApiError } from '../lib/api'
import { formatCount, nameList } from '../lib/format'
import { useSearch, useSources } from '../lib/queries'
import type { Passage, Source } from '../lib/queries'
import { can } from '../lib/session'

/*
 * Search over the workspace's documents.
 *
 * The screen is called Chat, but nothing here writes an answer: the service finds the passages
 * that best match the question and returns them with where each came from. Every word on the
 * screen says that, so nobody mistakes a quoted passage for something an agent concluded.
 */

type Message =
  | { id: string; role: 'question'; text: string; timestamp: string }
  | { id: string; role: 'results'; timestamp: string; passages: Passage[] }

const MAX_QUESTION_LENGTH = 1000

const countOf = (count: number, noun: string) => `${formatCount(count)} ${noun}${count === 1 ? '' : 's'}`

const PAGE_EYEBROW = 'Search your documents'
const PAGE_DESCRIPTION =
  "Finds the passages in this workspace's documents that best match your question, and shows where each came from."

export function Chat() {
  const canUseChat = can('chat:use')
  // The search itself is guarded by knowledge:query, which a custom role can lack even with chat:use.
  const canQuery = can('knowledge:query')

  if (!canUseChat || !canQuery) {
    return (
      <div className="page">
        <PageHeader eyebrow={PAGE_EYEBROW} title="Chat" description={PAGE_DESCRIPTION} />
        {canUseChat ? (
          <PermissionState permission="knowledge:query" what="searching documents" />
        ) : (
          <PermissionState permission="chat:use" what="chat" />
        )}
      </div>
    )
  }

  return <DocumentSearch />
}

function DocumentSearch() {
  const [question, setQuestion] = useState('')
  const [history, setHistory] = useState<Message[]>([])
  const [isSearching, setIsSearching] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [announcement, setAnnouncement] = useState('')
  const search = useSearch()
  const sourcesQuery = useSources({ enabled: can('knowledge:read') })
  const reduceMotion = useReducedMotion()
  const listRef = useRef<HTMLDivElement>(null)
  const fieldRef = useRef<HTMLDivElement>(null)
  const seenCount = useRef(0)
  const hintId = useId()

  // Brings the newest card into view when one arrives, never on first render: landing on the
  // page must not scroll it.
  useEffect(() => {
    const grew = history.length > seenCount.current
    seenCount.current = history.length
    if (!grew || history.length === 0) return
    const newest = listRef.current?.lastElementChild
    newest?.scrollIntoView?.({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
  }, [history, reduceMotion])

  function focusField() {
    fieldRef.current?.querySelector('textarea')?.focus()
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const currentQuestion = question.trim()
    if (!currentQuestion || isSearching) return

    const asked: Message = {
      id: `question-${Date.now()}`,
      role: 'question',
      text: currentQuestion,
      timestamp: new Date().toISOString(),
    }
    setHistory((previous) => [...previous, asked])
    setQuestion('')
    setFailure(null)
    setAnnouncement('')
    setIsSearching(true)

    try {
      const result = await search.mutateAsync(currentQuestion)
      setHistory((previous) => [
        ...previous,
        { id: `results-${Date.now()}`, role: 'results', timestamp: new Date().toISOString(), passages: result.passages },
      ])
      setAnnouncement(
        result.passages.length > 0 ? `Found ${countOf(result.passages.length, 'matching passage')}.` : 'No matching passages.',
      )
    } catch (err) {
      // Nothing was found and nothing was answered: take the question back off the page and put
      // it in the box again, so it can be sent again without retyping it.
      setHistory((previous) => previous.filter((message) => message.id !== asked.id))
      setQuestion(currentQuestion)
      setFailure(err instanceof ApiError ? describeApiError(err) : 'The search could not be completed. Try again.')
    } finally {
      setIsSearching(false)
      focusField()
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    // Enter searches and Shift+Enter starts a new line. Enter that confirms an IME composition
    // (Japanese, Chinese, Korean input) belongs to the composition, not to the form.
    if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return
    event.preventDefault()
    event.currentTarget.form?.requestSubmit()
  }

  return (
    <div className="page">
      <PageHeader eyebrow={PAGE_EYEBROW} title="Chat" description={PAGE_DESCRIPTION} />

      <Notice tone="info">Results are passages quoted from your documents, not a written answer.</Notice>

      <p className="visually-hidden" role="status">
        {announcement}
      </p>

      <div style={{ marginTop: 'var(--space-6)' }}>
        {history.length === 0 ? (
          <Card as="section">
            <SearchIntro sources={sourcesQuery.data} />
          </Card>
        ) : (
          <div ref={listRef} className="stack" style={{ gap: 'var(--space-5)' }}>
            {history.map((message) => (
              <Card key={message.id} as="article">
                {message.role === 'question' ? (
                  <>
                    <Eyebrow>You asked</Eyebrow>
                    <p>{message.text}</p>
                  </>
                ) : (
                  <SearchResults passages={message.passages} />
                )}
                <p className="caption" style={{ marginTop: 'var(--space-3)', textAlign: 'right' }}>
                  <Time iso={message.timestamp} />
                </p>
              </Card>
            ))}
          </div>
        )}
      </div>

      {failure && (
        <div style={{ marginTop: 'var(--space-6)' }}>
          <Notice tone="warning" live>
            {failure}
          </Notice>
        </div>
      )}

      <form onSubmit={handleSubmit} className="stack" style={{ gap: 'var(--space-3)', marginTop: 'var(--space-6)' }}>
        <div ref={fieldRef}>
          <Textarea
            label="Your question"
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Ask about a policy, a client or a process"
            rows={3}
            maxLength={MAX_QUESTION_LENGTH}
            readOnly={isSearching}
            aria-busy={isSearching || undefined}
            aria-describedby={hintId}
          />
        </div>
        <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)', flexWrap: 'wrap' }}>
          <p className="caption" id={hintId}>
            Enter to search, Shift+Enter for a new line.
          </p>
          <Button type="submit" loading={isSearching} disabled={question.trim().length === 0}>
            Search
          </Button>
        </div>
      </form>
    </div>
  )
}

/** What there is to search, before the first question. */
function SearchIntro({ sources }: { sources: Source[] | undefined }) {
  const icon = <EmptyIcon kind="search" />

  // Sources could not be read (still loading, or the role cannot list them): no claim about them.
  if (!sources) {
    return (
      <EmptyState
        icon={icon}
        title="Ask a question about your documents"
        body="Results are the passages that best match it, each with the document it came from."
      />
    )
  }

  const withDocuments = sources.filter((source) => source.documentCount > 0)
  const documents = withDocuments.reduce((sum, source) => sum + source.documentCount, 0)

  if (documents === 0) {
    const canAdd = can('knowledge:source_manage')
    return (
      <EmptyState
        icon={icon}
        title="There are no documents to search yet"
        body={
          canAdd
            ? 'Upload documents to a source in Knowledge, then search them here.'
            : 'Ask a manager or admin to add documents in Knowledge.'
        }
        action={
          canAdd && (
            <a className="button button-outline" href="/knowledge">
              Add documents in Knowledge
            </a>
          )
        }
      />
    )
  }

  return (
    <EmptyState
      icon={icon}
      title="Ask a question about your documents"
      body={`Searches ${countOf(documents, 'document')} in ${nameList(withDocuments.map((source) => source.name))}.`}
      action={
        <a className="link" href="/knowledge">
          See sources
        </a>
      }
    />
  )
}

function SearchResults({ passages }: { passages: Passage[] }) {
  if (passages.length === 0) {
    return (
      <>
        <Eyebrow>Search results</Eyebrow>
        <p>No document in this workspace matches that question.</p>
        <p className="caption" style={{ marginTop: 'var(--space-2)' }}>
          Try other words, or check in Knowledge that the document you expect has been indexed.
        </p>
      </>
    )
  }

  return (
    <>
      <Eyebrow>Search results</Eyebrow>
      <p style={{ marginBottom: 'var(--space-5)' }}>
        {passages.length === 1 ? 'This passage matches best.' : 'These passages match best.'}
      </p>
      <ol className="stack" style={{ gap: 'var(--space-3)', margin: 0, padding: 0, listStyle: 'none' }}>
        {passages.map((passage) => (
          <li
            key={passage.chunkId}
            style={{
              border: '1px solid var(--line)',
              borderRadius: 'var(--radius-control-lg)',
              padding: 'var(--space-4)',
            }}
          >
            <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', marginBottom: 'var(--space-2)' }}>
              <span className="section-heading" style={{ fontSize: '12px' }}>
                {passage.documentTitle}
              </span>
              {/* Estimated from where the passage sits in the text, so it can be one page out. */}
              {passage.pageNumber != null && <span className="caption">About page {passage.pageNumber}</span>}
              {passage.heading && <span className="caption">{passage.heading}</span>}
            </div>
            <blockquote style={{ margin: 0 }}>
              <p>{passage.content}</p>
            </blockquote>
          </li>
        ))}
      </ol>
    </>
  )
}
