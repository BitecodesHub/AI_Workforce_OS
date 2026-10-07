import { useState } from 'react'
import { can } from '../../lib/session'
import { passageLink } from './chatModel'
import type { SourcePassage } from './chatModel'

/*
 * The passages from the workspace's documents behind an answer, quoted: which document, which page
 * and section, the text itself, and a way to open where it came from. One list for the card that
 * answers a question about documents and for the sources an agent's answer was written from, so
 * "[2]" in either means the same thing: the second passage here.
 */

/** The id of the list item holding passage `index` (counting from 1), for a citation to move focus to. */
export function passageItemId(prefix: string, index: number): string {
  return `${prefix}-passage-${index}`
}

export function PassageList({
  passages,
  idPrefix,
  limit,
  activeIndex,
  compact = false,
}: {
  passages: readonly SourcePassage[]
  /** Makes each item's id, so a citation elsewhere can scroll to it and focus it. */
  idPrefix: string
  /** How many to show before "Show more"; every one when left out. */
  limit?: number
  /** The passage a citation just opened, counting from 1, marked so the eye finds it. */
  activeIndex?: number | null
  /** Title and link on one line, the passage clamped to two lines: for the list under an answer. */
  compact?: boolean
}) {
  const [showAll, setShowAll] = useState(false)
  const mayReadKnowledge = can('knowledge:read')
  const cap = limit ?? passages.length
  // A cited passage hidden behind "Show more" is shown rather than silently not there.
  const expanded = showAll || (activeIndex ?? 0) > cap
  const shown = expanded ? passages : passages.slice(0, cap)
  const rest = passages.length - shown.length

  return (
    <>
      <ol className={`chat-passages${compact ? ' chat-passages-compact' : ''}`}>
        {shown.map((passage, index) => {
          const link = passageLink(passage, mayReadKnowledge)
          const active = activeIndex === index + 1
          return (
            <li
              key={passage.chunkId ?? index}
              id={passageItemId(idPrefix, index + 1)}
              className="chat-passage"
              tabIndex={-1}
              {...(active ? { style: { borderColor: 'var(--blue)' } } : {})}
            >
              <div className="chat-passage-head row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', marginBottom: 'var(--space-2)' }}>
                <span className="section-heading chat-passage-title" style={{ fontSize: 'var(--text-caption)' }}>
                  [{index + 1}] {passage.documentTitle}
                </span>
                {passage.pageNumber != null && <span className="caption">About page {passage.pageNumber}</span>}
                {passage.heading && <span className="caption">{passage.heading}</span>}
                {link && (
                  <a
                    className="link caption"
                    href={link.href}
                    {...(link.external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
                  >
                    {link.label}
                  </a>
                )}
              </div>
              <blockquote style={{ margin: 0 }}>
                <p className="chat-passage-text">{passage.content}</p>
              </blockquote>
            </li>
          )
        })}
      </ol>
      {rest > 0 && (
        <button type="button" className="link" onClick={() => setShowAll(true)}>
          Show {rest} more
        </button>
      )}
    </>
  )
}
