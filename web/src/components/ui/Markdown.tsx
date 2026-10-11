// @find: markdown, render markdown, chat message formatting, citations, source links, code block, safe rendering, Markdown component
// @what: Renders the safe parsed markdown tree as React elements, with optional clickable citations.
// @flow: Used by Chat and run output; parses with lib/markdown.
import type { CSSProperties, ReactNode } from 'react'
import { createContext, useContext, useMemo } from 'react'
import type { MdAlign, MdBlock, MdInline, MdListItem } from '../../lib/markdown'
import { parseMarkdown } from '../../lib/markdown'
import { CopyButton } from './CopyButton'

/*
 * Turns the safe tree lib/markdown.ts parses into React elements, never HTML.
 *
 * parseMarkdown already refuses an unsafe link scheme and never produces a node for raw HTML in
 * the source, so there is nothing here to sanitise: a hostile string comes in as a `text` node,
 * same as any other, and text nodes are never interpreted as markup.
 *
 * A citation such as [2] is a button only when the caller says what to do with one (`citations`);
 * without that it is the text it was written as.
 */

/** What a click on a citation does. Null where the text has no passages behind it to open. */
const CitationContext = createContext<((index: number) => void) | null>(null)

/** A button that looks like the link it stands in for, in the text's own size, never a grey box. */
const CITATION_STYLE: CSSProperties = {
  background: 'none',
  border: 'none',
  padding: 0,
  font: 'inherit',
  color: 'var(--blue)',
  fontWeight: 'var(--weight-medium)',
  cursor: 'pointer',
}

/** A citation marker such as [2]: a link-styled button that calls the context handler with the passage number, or plain text when no handler was supplied. */
function Citation({ index }: { index: number }) {
  const open = useContext(CitationContext)
  if (!open) return <>[{index}]</>
  return (
    <button type="button" className="md-citation" style={CITATION_STYLE} aria-label={`Open source ${index}`} onClick={() => open(index)}>
      [{index}]
    </button>
  )
}

/** Text alignment style for a table cell, or undefined for the default alignment. */
function alignStyle(align: MdAlign): { textAlign: 'left' | 'center' | 'right' } | undefined {
  return align ? { textAlign: align } : undefined
}

/** Maps inline nodes (text, line break, code, bold, italic, link, citation) to React nodes. Links open in a new tab with noopener and noreferrer; text is never interpreted as markup. */
function renderInline(nodes: MdInline[]): ReactNode[] {
  return nodes.map((node, index) => {
    switch (node.type) {
      case 'text':
        return node.text
      case 'break':
        return <br key={index} />
      case 'code':
        return (
          <code key={index} className="md-inline-code">
            {node.text}
          </code>
        )
      case 'bold':
        return <strong key={index}>{renderInline(node.children)}</strong>
      case 'italic':
        return <em key={index}>{renderInline(node.children)}</em>
      case 'link':
        return (
          <a key={index} href={node.href} target="_blank" rel="noopener noreferrer">
            {renderInline(node.children)}
          </a>
        )
      case 'citation':
        return <Citation key={index} index={node.index} />
      default:
        return null
    }
  })
}

/** The items of a list, each rendering its child blocks (so nested lists and paragraphs work). */
function ListItems({ items }: { items: MdListItem[] }) {
  return (
    <>
      {items.map((item, index) => (
        <li key={index}>
          <Blocks blocks={item.children} />
        </li>
      ))}
    </>
  )
}

/** A fenced code block with an optional language label and a Copy code button (CopyButton). */
function CodeBlock({ language, text }: { language: string | null; text: string }) {
  return (
    <figure className="md-code">
      <div className="md-code-head">
        {language && <span className="md-code-lang">{language}</span>}
        <CopyButton text={text} label="Copy code" variant="text" />
      </div>
      <pre>
        <code>{text}</code>
      </pre>
    </figure>
  )
}

/** A table in a focusable, scrollable region so wide tables do not break the layout and keyboard users can scroll them; column alignment follows the markdown. */
function TableBlock({ header, align, rows }: { header: MdInline[][]; align: MdAlign[]; rows: MdInline[][][] }) {
  return (
    <div className="md-table-scroll" tabIndex={0} role="region" aria-label="Table">
      <table>
        <thead>
          <tr>
            {header.map((cell, index) => (
              <th key={index} style={alignStyle(align[index] ?? null)}>
                {renderInline(cell)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {row.map((cell, cellIndex) => (
                <td key={cellIndex} style={alignStyle(align[cellIndex] ?? null)}>
                  {renderInline(cell)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Renders one block node: paragraph, heading, list, blockquote, code, rule or table. Headings step down (levels 1 and 2 become h4, deeper become h5) so a message never outranks the page's own headings. An ordered list keeps its start number. */
function Block({ block }: { block: MdBlock }) {
  switch (block.type) {
    case 'paragraph':
      return <p>{renderInline(block.children)}</p>
    case 'heading': {
      // `#` and `##` step down to `h4`; anything deeper steps down to `h5`, so a message body
      // never outranks the heading structure of the page it sits in. Two literal tags, rather
      // than one picked by a variable, so no component identity is created during render.
      const content = renderInline(block.children)
      return block.level <= 2 ? (
        <h4 className="md-heading">{content}</h4>
      ) : (
        <h5 className="md-heading">{content}</h5>
      )
    }
    case 'list': {
      const items = <ListItems items={block.items} />
      // A list that picks up at step 3 says so, rather than counting from 1 again.
      return block.ordered ? <ol {...(block.start !== undefined ? { start: block.start } : {})}>{items}</ol> : <ul>{items}</ul>
    }
    case 'blockquote':
      return (
        <blockquote>
          <Blocks blocks={block.children} />
        </blockquote>
      )
    case 'code':
      return <CodeBlock language={block.language} text={block.text} />
    case 'rule':
      return <hr />
    case 'table':
      return <TableBlock header={block.header} align={block.align} rows={block.rows} />
    default:
      return null
  }
}

/** Renders a list of blocks; the index is a stable enough key because the tree is rebuilt whole from the source text. */
function Blocks({ blocks }: { blocks: MdBlock[] }) {
  // Blocks carry no id of their own, and the tree is rebuilt whole from the source string on
  // every change rather than reordered in place, so the index is a stable enough key here.
  return (
    <>
      {blocks.map((block, index) => (
        <Block key={index} block={block} />
      ))}
    </>
  )
}

// @find: markdown renderer, citations, code blocks
/**
 * Renders a markdown string as React elements through the safe parser (parseMarkdown in lib/markdown).
 * Props: text, className and citations ({ count, onOpen }): the count tells the parser how many numbered
 * passages the text may cite, and onOpen is provided to Citation markers through a context. Without citations
 * a [n] stays literal text. The parse is memoised on the text and count. Used for chat answers, run output and
 * task results.
 */
export function Markdown({
  text,
  className,
  citations,
}: {
  text: string
  className?: string
  /** How many numbered passages the text may cite, and what opening one does. */
  citations?: { count: number; onOpen: (index: number) => void } | undefined
}) {
  const count = citations?.count ?? 0
  const blocks = useMemo(() => parseMarkdown(text, { citations: count }), [text, count])
  return (
    <CitationContext.Provider value={count > 0 ? (citations?.onOpen ?? null) : null}>
      <div className={`md ${className ?? ''}`.trim()}>
        <Blocks blocks={blocks} />
      </div>
    </CitationContext.Provider>
  )
}
