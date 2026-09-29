import type { ReactNode } from 'react'
import { useMemo } from 'react'
import type { MdAlign, MdBlock, MdInline, MdListItem } from '../../lib/markdown'
import { parseMarkdown } from '../../lib/markdown'
import { CopyButton } from './CopyButton'

/*
 * Turns the safe tree lib/markdown.ts parses into React elements, never HTML.
 *
 * parseMarkdown already refuses an unsafe link scheme and never produces a node for raw HTML in
 * the source, so there is nothing here to sanitise: a hostile string comes in as a `text` node,
 * same as any other, and text nodes are never interpreted as markup.
 */

function alignStyle(align: MdAlign): { textAlign: 'left' | 'center' | 'right' } | undefined {
  return align ? { textAlign: align } : undefined
}

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
      default:
        return null
    }
  })
}

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
      return block.ordered ? <ol>{items}</ol> : <ul>{items}</ul>
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

export function Markdown({ text, className }: { text: string; className?: string }) {
  const blocks = useMemo(() => parseMarkdown(text), [text])
  return (
    <div className={`md ${className ?? ''}`.trim()}>
      <Blocks blocks={blocks} />
    </div>
  )
}
