import { describe, expect, it } from 'vitest'
import { parseMarkdown } from './markdown'
import type { MdBlock, MdInline } from './markdown'

/** Concatenates the plain text a tree of inline nodes would read as, ignoring formatting. */
function flatten(nodes: MdInline[]): string {
  return nodes
    .map((node) => {
      if (node.type === 'text' || node.type === 'code') return node.text
      if (node.type === 'break') return '\n'
      if (node.type === 'citation') return `[${node.index}]`
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

describe('model-shaped answers', () => {
  const paragraph = (text: string): MdBlock => ({ type: 'paragraph', children: [{ type: 'text', text }] })

  it('keeps a loose numbered list together, starting at its own first number', () => {
    const blocks = parseMarkdown('3. **Check the roster**\n   for gaps\n\n4. **Send it**\n\n5. Done')
    expect(blocks).toHaveLength(1)
    const list = blocks[0]!
    if (list.type !== 'list') throw new Error('expected a list')
    expect(list.ordered).toBe(true)
    expect(list.start).toBe(3)
    expect(list.items).toHaveLength(3)
    expect(list.items[0]!.children).toEqual([
      {
        type: 'paragraph',
        children: [
          { type: 'bold', children: [{ type: 'text', text: 'Check the roster' }] },
          { type: 'break' },
          { type: 'text', text: 'for gaps' },
        ],
      },
    ])
    expect(list.items[2]!.children).toEqual([paragraph('Done')])
  })

  it('leaves the start off a list that counts from 1', () => {
    const list = parseMarkdown('1. One\n\n2. Two')[0]!
    if (list.type !== 'list') throw new Error('expected a list')
    expect(list.items).toHaveLength(2)
    expect('start' in list).toBe(false)
  })

  it('adds an indented paragraph after a blank line to the item above it', () => {
    const blocks = parseMarkdown('1. Step one\n\n   More about step one\n2. Step two')
    expect(blocks).toHaveLength(1)
    const list = blocks[0]!
    if (list.type !== 'list') throw new Error('expected a list')
    expect(list.items).toHaveLength(2)
    expect(list.items[0]!.children).toEqual([paragraph('Step one'), paragraph('More about step one')])
    expect(list.items[1]!.children).toEqual([paragraph('Step two')])
  })

  it('ends a list at a line that is not indented', () => {
    const blocks = parseMarkdown('- One\n- Two\n\nAfter the list')
    expect(blocks.map((block) => block.type)).toEqual(['list', 'paragraph'])
  })

  it('reads a table straight after a sentence', () => {
    const blocks = parseMarkdown('Here are the results:\n| Team | Hours |\n|---|---:|\n| Support | 12 |')
    expect(blocks.map((block) => block.type)).toEqual(['paragraph', 'table'])
    const table = blocks[1]!
    if (table.type !== 'table') throw new Error('expected a table')
    expect(table.header.map(flatten)).toEqual(['Team', 'Hours'])
    expect(table.rows.map((row) => row.map(flatten))).toEqual([['Support', '12']])
  })

  it('keeps a pipe in a sentence as text when no separator row follows', () => {
    expect(parseMarkdown('Choose this | or that\nthen carry on')).toEqual([
      {
        type: 'paragraph',
        children: [{ type: 'text', text: 'Choose this | or that' }, { type: 'break' }, { type: 'text', text: 'then carry on' }],
      },
    ])
  })

  it('leaves tool and file names with underscores as written', () => {
    expect(parseMarkdown('Use gmail__send_email or send_email_draft today.')).toEqual([
      paragraph('Use gmail__send_email or send_email_draft today.'),
    ])
  })

  it('needs a flanking space or punctuation for star italics too', () => {
    expect(parseMarkdown('Attach *.csv and *.pdf files')).toEqual([paragraph('Attach *.csv and *.pdf files')])
    expect(parseMarkdown('2 * 3 * 4')).toEqual([paragraph('2 * 3 * 4')])
    expect(parseMarkdown('a (_note_) here')).toEqual([
      {
        type: 'paragraph',
        children: [
          { type: 'text', text: 'a (' },
          { type: 'italic', children: [{ type: 'text', text: 'note' }] },
          { type: 'text', text: ') here' },
        ],
      },
    ])
  })

  it('leaves a full stop after a bare URL out of the link', () => {
    const blocks = parseMarkdown('See https://example.org/docs. Then https://example.org, too')
    expect(links(blocks)).toEqual([
      { href: 'https://example.org/docs', text: 'https://example.org/docs' },
      { href: 'https://example.org', text: 'https://example.org' },
    ])
    const first = blocks[0]!
    if (first.type !== 'paragraph') throw new Error('expected a paragraph')
    expect(flatten(first.children)).toBe('See https://example.org/docs. Then https://example.org, too')
  })
})

/** Every citation anywhere in a block tree, in reading order, and how many nodes sit inside a link. */
function citations(blocks: MdBlock[]): { indexes: number[]; insideLinks: number } {
  const indexes: number[] = []
  let insideLinks = 0
  const walkInline = (nodes: MdInline[], inLink: boolean) => {
    for (const node of nodes) {
      if (node.type === 'citation') {
        indexes.push(node.index)
        if (inLink) insideLinks += 1
      }
      if (node.type === 'bold' || node.type === 'italic') walkInline(node.children, inLink)
      if (node.type === 'link') walkInline(node.children, true)
    }
  }
  const walkBlock = (list: MdBlock[]) => {
    for (const block of list) {
      if (block.type === 'paragraph' || block.type === 'heading') walkInline(block.children, false)
      if (block.type === 'blockquote') walkBlock(block.children)
      if (block.type === 'list') for (const item of block.items) walkBlock(item.children)
      if (block.type === 'table') {
        block.header.forEach((cell) => walkInline(cell, false))
        block.rows.forEach((row) => row.forEach((cell) => walkInline(cell, false)))
      }
    }
  }
  walkBlock(blocks)
  return { indexes, insideLinks }
}

describe('citations', () => {
  const answer = 'Leave accrues monthly [1] and carers may take ten days [2].'

  it('turns [n] into a citation when that passage exists, keeping the text around it', () => {
    const blocks = parseMarkdown(answer, { citations: 2 })
    expect(citations(blocks).indexes).toEqual([1, 2])
    const paragraph = blocks[0]! as Extract<MdBlock, { type: 'paragraph' }>
    expect(flatten(paragraph.children)).toBe(answer)
    expect(paragraph.children.filter((node) => node.type === 'citation')).toEqual([
      { type: 'citation', index: 1 },
      { type: 'citation', index: 2 },
    ])
  })

  it('leaves [n] as text when no count is given, so nothing else changes', () => {
    expect(citations(parseMarkdown(answer)).indexes).toEqual([])
    expect(citations(parseMarkdown(answer, { citations: 0 })).indexes).toEqual([])
    expect(flatten((parseMarkdown(answer)[0]! as Extract<MdBlock, { type: 'paragraph' }>).children)).toBe(answer)
  })

  it('only cites a passage that exists: [3] with two passages stays text', () => {
    const blocks = parseMarkdown('See [2] and [3] and [0].', { citations: 2 })
    expect(citations(blocks).indexes).toEqual([2])
    expect(flatten((blocks[0]! as Extract<MdBlock, { type: 'paragraph' }>).children)).toBe('See [2] and [3] and [0].')
  })

  it('reads a list of numbers as one citation each, and keeps a list with a missing one as text', () => {
    expect(citations(parseMarkdown('Both agree [1, 2].', { citations: 2 })).indexes).toEqual([1, 2])
    expect(citations(parseMarkdown('Both agree [1,2].', { citations: 2 })).indexes).toEqual([1, 2])
    expect(citations(parseMarkdown('Both agree [1, 5].', { citations: 2 })).indexes).toEqual([])
  })

  it('never cites inside a code span or a fenced code block', () => {
    const blocks = parseMarkdown('Use `items[1]` and `[2]` here.\n\n```\nrow[1] = [2]\n```', { citations: 2 })
    expect(citations(blocks).indexes).toEqual([])
    expect(flatten((blocks[0]! as Extract<MdBlock, { type: 'paragraph' }>).children)).toBe('Use items[1] and [2] here.')
    expect(blocks[1]).toEqual({ type: 'code', language: null, text: 'row[1] = [2]' })
  })

  it('never puts a citation inside a link, and a link that looks like [n](url) stays a link', () => {
    // A label with brackets in it is not a link to this parser; whatever it is read as, no
    // citation ends up inside a link, which would be a button inside an anchor.
    const inLabel = parseMarkdown('Read [a policy [1](https://example.org/policy) first.', { citations: 2 })
    expect(citations(inLabel).insideLinks).toBe(0)

    const linkedNumber = parseMarkdown('Source [1](https://example.org/one) and [2].', { citations: 2 })
    expect(links(linkedNumber)).toEqual([{ href: 'https://example.org/one', text: '1' }])
    expect(citations(linkedNumber).indexes).toEqual([2])
  })

  it('leaves a bare URL whole even when it ends in [n]', () => {
    const blocks = parseMarkdown('Open https://example.org/a[1] now', { citations: 2 })
    expect(citations(blocks).indexes).toEqual([])
    expect(links(blocks)).toEqual([{ href: 'https://example.org/a[1]', text: 'https://example.org/a[1]' }])
  })

  it('cites inside bold, italic, headings, lists, quotes and tables', () => {
    const source = [
      '**Five days [1]** or *so [2]*',
      '',
      '## Leave [1]',
      '',
      '- Carers [2]',
      '',
      '> Quoted [1]',
      '',
      '| Topic | Source |',
      '| --- | --- |',
      '| Leave | [2] |',
    ].join('\n')
    expect(citations(parseMarkdown(source, { citations: 2 })).indexes).toEqual([1, 2, 1, 2, 1, 2])
  })

  it('does not mistake an ordinary bracketed word or a spaced number for a citation', () => {
    expect(citations(parseMarkdown('A [note] and [ 1 ] and [1a].', { citations: 2 })).indexes).toEqual([])
  })

  it('renders oversized text as plain paragraphs without citations', () => {
    const big = `[1] ${'x'.repeat(61_000)}`
    expect(citations(parseMarkdown(big, { citations: 2 })).indexes).toEqual([])
  })
})
