import { describe, expect, it } from 'vitest'
import { parseMarkdown } from './markdown'
import type { MdBlock, MdInline } from './markdown'

/** Concatenates the plain text a tree of inline nodes would read as, ignoring formatting. */
function flatten(nodes: MdInline[]): string {
  return nodes
    .map((node) => {
      if (node.type === 'text' || node.type === 'code') return node.text
      if (node.type === 'break') return '\n'
      return flatten(node.children)
    })
    .join('')
}

/** Every link node anywhere in a block tree, for asserting none exists (or checking the one that does). */
function links(blocks: MdBlock[]): Array<{ href: string; text: string }> {
  const found: Array<{ href: string; text: string }> = []
  const walkInline = (nodes: MdInline[]) => {
    for (const node of nodes) {
      if (node.type === 'link') found.push({ href: node.href, text: flatten(node.children) })
      if (node.type === 'link' || node.type === 'bold' || node.type === 'italic') walkInline(node.children)
    }
  }
  const walkBlock = (list: MdBlock[]) => {
    for (const block of list) {
      if (block.type === 'paragraph' || block.type === 'heading') walkInline(block.children)
      if (block.type === 'blockquote') walkBlock(block.children)
      if (block.type === 'list') for (const item of block.items) walkBlock(item.children)
      if (block.type === 'table') {
        block.header.forEach(walkInline)
        block.rows.forEach((row) => row.forEach(walkInline))
      }
    }
  }
  walkBlock(blocks)
  return found
}

describe('block types', () => {
  it('reads a paragraph with a soft line break', () => {
    expect(parseMarkdown('Line one\nLine two')).toEqual([
      {
        type: 'paragraph',
        children: [{ type: 'text', text: 'Line one' }, { type: 'break' }, { type: 'text', text: 'Line two' }],
      },
    ])
  })

  it('reads headings from one hash to six', () => {
    expect(parseMarkdown('# Title')).toEqual([{ type: 'heading', level: 1, children: [{ type: 'text', text: 'Title' }] }])
    expect(parseMarkdown('###### Deep')).toEqual([
      { type: 'heading', level: 6, children: [{ type: 'text', text: 'Deep' }] },
    ])
  })

  it('reads a horizontal rule on its own', () => {
    expect(parseMarkdown('---')).toEqual([{ type: 'rule' }])
  })

  it('reads fenced code with a language', () => {
    expect(parseMarkdown('```ts\nconst x = 1\nconst y = 2\n```')).toEqual([
      { type: 'code', language: 'ts', text: 'const x = 1\nconst y = 2' },
    ])
  })

  it('reads fenced code with no language', () => {
    expect(parseMarkdown('```\nplain\n```')).toEqual([{ type: 'code', language: null, text: 'plain' }])
  })

  it('reads a block quote', () => {
    expect(parseMarkdown('> Quoted text')).toEqual([
      { type: 'blockquote', children: [{ type: 'paragraph', children: [{ type: 'text', text: 'Quoted text' }] }] },
    ])
  })

  it('reads an ordered list', () => {
    expect(parseMarkdown('1. First\n2. Second')).toEqual([
      {
        type: 'list',
        ordered: true,
        items: [
          { children: [{ type: 'paragraph', children: [{ type: 'text', text: 'First' }] }] },
          { children: [{ type: 'paragraph', children: [{ type: 'text', text: 'Second' }] }] },
        ],
      },
    ])
  })

  it('nests an unordered list one level deep', () => {
    const blocks = parseMarkdown('- Item 1\n  - Nested 1\n- Item 2')
    expect(blocks).toHaveLength(1)
    const list = blocks[0]!
    if (list.type !== 'list') throw new Error('expected a list')
    expect(list.ordered).toBe(false)
    expect(list.items).toHaveLength(2)
    expect(list.items[0]!.children).toEqual([
      { type: 'paragraph', children: [{ type: 'text', text: 'Item 1' }] },
      {
        type: 'list',
        ordered: false,
        items: [{ children: [{ type: 'paragraph', children: [{ type: 'text', text: 'Nested 1' }] }] }],
      },
    ])
    expect(list.items[1]!.children).toEqual([{ type: 'paragraph', children: [{ type: 'text', text: 'Item 2' }] }])
  })

  it('reads a GitHub pipe table', () => {
    const blocks = parseMarkdown('| A | B |\n| --- | ---: |\n| 1 | 2 |')
    expect(blocks).toHaveLength(1)
    const table = blocks[0]!
    if (table.type !== 'table') throw new Error('expected a table')
    expect(table.header.map(flatten)).toEqual(['A', 'B'])
    expect(table.align).toEqual([null, 'right'])
    expect(table.rows.map((row) => row.map(flatten))).toEqual([['1', '2']])
  })
})

describe('inline formatting', () => {
  it('reads code, bold and both italic markers', () => {
    expect(parseMarkdown('`code`')).toEqual([{ type: 'paragraph', children: [{ type: 'code', text: 'code' }] }])
    expect(parseMarkdown('**bold**')).toEqual([
      { type: 'paragraph', children: [{ type: 'bold', children: [{ type: 'text', text: 'bold' }] }] },
    ])
    expect(parseMarkdown('*italic*')).toEqual([
      { type: 'paragraph', children: [{ type: 'italic', children: [{ type: 'text', text: 'italic' }] }] },
    ])
    expect(parseMarkdown('_italic_')).toEqual([
      { type: 'paragraph', children: [{ type: 'italic', children: [{ type: 'text', text: 'italic' }] }] },
    ])
  })

  it('links a safe URL and leaves the label readable', () => {
    const blocks = parseMarkdown('[the docs](https://example.org/docs)')
    expect(links(blocks)).toEqual([{ href: 'https://example.org/docs', text: 'the docs' }])
  })

  it('turns a bare URL into a link of itself', () => {
    const blocks = parseMarkdown('See https://example.org for more')
    expect(links(blocks)).toEqual([{ href: 'https://example.org', text: 'https://example.org' }])
  })

  it('links a mailto address', () => {
    const blocks = parseMarkdown('[write to us](mailto:team@example.org)')
    expect(links(blocks)).toEqual([{ href: 'mailto:team@example.org', text: 'write to us' }])
  })
})

describe('safety', () => {
  it('never turns a javascript: URL into a link, and keeps the raw text readable', () => {
    const src = '[x](javascript:alert(1))'
    const blocks = parseMarkdown(src)
    expect(links(blocks)).toEqual([])
    const paragraph = blocks[0]!
    if (paragraph.type !== 'paragraph') throw new Error('expected a paragraph')
    expect(flatten(paragraph.children)).toBe(src)
  })

  it('never turns a data: URL into a link', () => {
    const blocks = parseMarkdown('[x](data:text/html,<script>alert(1)</script>)')
    expect(links(blocks)).toEqual([])
  })

  it('keeps a literal script tag as plain text', () => {
    const src = '<script>alert(1)</script>'
    const blocks = parseMarkdown(src)
    const paragraph = blocks[0]!
    if (paragraph.type !== 'paragraph') throw new Error('expected a paragraph')
    expect(flatten(paragraph.children)).toBe(src)
    expect(links(blocks)).toEqual([])
  })
})

describe('size fallback', () => {
  it('renders oversized input as one plain paragraph, with its line breaks kept but nothing else parsed', () => {
    const long = 'x'.repeat(70_000)
    const src = `# Not a heading\n${long}`
    const blocks = parseMarkdown(src)
    expect(blocks).toEqual([
      {
        type: 'paragraph',
        children: [{ type: 'text', text: '# Not a heading' }, { type: 'break' }, { type: 'text', text: long }],
      },
    ])
  })

  it('parses input at the limit normally', () => {
    const blocks = parseMarkdown('# Heading')
    expect(blocks[0]!.type).toBe('heading')
  })
})

describe('edge cases', () => {
  it('reads nothing from an empty string', () => {
    expect(parseMarkdown('')).toEqual([])
  })
})
