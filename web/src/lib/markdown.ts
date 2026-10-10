// @find: markdown, parse markdown, safe markdown, render message, agent answer formatting, tables, lists, code blocks, links, no html injection, MdBlock
// @what: A small safe Markdown parser that returns plain data trees (never HTML) for agent answers and chat messages.
// @flow: Rendered by components/ui/Markdown.tsx
/*
 * A small, safe subset of Markdown: enough for an agent's answer or a person's message to read
 * well, with no way for either to inject HTML.
 *
 * parseMarkdown never produces HTML - only the plain data trees below - so the renderer
 * (components/ui/Markdown.tsx) builds React elements directly from data. A hostile string, such
 * as a link with a javascript: URL or a literal <script> tag, can at most come back as a text
 * node: there is no path from input text to a DOM attribute or an injected element.
 *
 * Supported: paragraphs with soft line breaks, headings (# to ######), unordered and ordered
 * lists (nested, loose with blank lines between items, numbered from any start, and items that run
 * on over indented lines), block quotes, fenced code with an optional language, horizontal rules,
 * GitHub pipe tables (also straight after a sentence), and inline code, bold, italic, links and
 * bare URLs. Italic needs a space or punctuation either side of its marker, so tool and file names
 * such as send_email_draft or *.csv stay as written.
 *
 * Citations: an answer written from numbered passages cites them as [1], [2, 3]. When the caller
 * says how many passages there are, a number that names one of them becomes a `citation` node, for
 * a button that opens it. A number with no passage behind it, one in a code span, a link or a
 * code block, and every [n] when no count is given, stays the plain text it was written as.
 */

export type MdInline =
  | { type: 'text'; text: string }
  | { type: 'break' }
  | { type: 'code'; text: string }
  | { type: 'bold'; children: MdInline[] }
  | { type: 'italic'; children: MdInline[] }
  | { type: 'link'; href: string; children: MdInline[] }
  /** `index` counts from 1, the way the passages were numbered for the agent. */
  | { type: 'citation'; index: number }

export type MdListItem = { children: MdBlock[] }

export type MdAlign = 'left' | 'center' | 'right' | null

export type MdBlock =
  | { type: 'paragraph'; children: MdInline[] }
  | { type: 'heading'; level: number; children: MdInline[] }
  /** `start` is set only for an ordered list that does not begin at 1. */
  | { type: 'list'; ordered: boolean; start?: number; items: MdListItem[] }
  | { type: 'blockquote'; children: MdBlock[] }
  | { type: 'code'; language: string | null; text: string }
  | { type: 'rule' }
  | { type: 'table'; header: MdInline[][]; align: MdAlign[]; rows: MdInline[][][] }

/** Above this size, the source renders as one plain paragraph rather than being parsed. */
const MAX_LENGTH = 60_000

/** The only link schemes the renderer may turn into a clickable `<a>`. Anything else stays text. */
const SAFE_LINK = /^(https?:|mailto:)/i

export type MarkdownOptions = {
  /** How many numbered passages the text may cite; [n] is only a citation for n from 1 to this. */
  citations?: number
}

// @find: parse markdown, markdown to blocks, safe rendering
export function parseMarkdown(src: string, options: MarkdownOptions = {}): MdBlock[] {
  if (!src) return []
  if (src.length > MAX_LENGTH) return [{ type: 'paragraph', children: rawInline(src) }]
  const lines = src.replace(/\r\n?/g, '\n').split('\n')
  return parseBlocks(lines, Math.max(0, Math.floor(options.citations ?? 0)))
}

/** The size-fallback paragraph keeps line breaks, but parses no other markup. */
function rawInline(src: string): MdInline[] {
  const nodes: MdInline[] = []
  src.split('\n').forEach((part, index) => {
    if (index > 0) nodes.push({ type: 'break' })
    if (part) nodes.push({ type: 'text', text: part })
  })
  return nodes
}

function isBlank(line: string): boolean {
  return line.trim() === ''
}

function headingMatch(line: string): { level: number; text: string } | null {
  const match = /^(#{1,6})\s+(.*)$/.exec(line)
  return match ? { level: match[1]!.length, text: match[2]!.trim() } : null
}

function ruleMatch(line: string): boolean {
  return /^(-{3,}|\*{3,}|_{3,})\s*$/.test(line.trim())
}

function fenceMatch(line: string): { language: string | null } | null {
  const match = /^```\s*([\w-]*)\s*$/.exec(line.trim())
  return match ? { language: match[1] ? match[1] : null } : null
}

/**
 * A list marker line. `contentIndent` is the column the item's own text starts at, which a line
 * further down must reach to belong to the same item. More than four spaces after the marker
 * count as one, the way CommonMark reads them.
 */
type ListMarker = { indent: number; text: string; contentIndent: number }

function markerIndent(indent: string, marker: string, gap: string): number {
  return indent.length + marker.length + (gap.length > 4 ? 1 : gap.length)
}

function bulletMatch(line: string): ListMarker | null {
  const match = /^(\s*)([-*])(\s+)(.*)$/.exec(line)
  return match
    ? { indent: match[1]!.length, text: match[4]!, contentIndent: markerIndent(match[1]!, match[2]!, match[3]!) }
    : null
}

/** An ordered list marker, with the item's own number. */
function orderedMatch(line: string): (ListMarker & { number: number }) | null {
  const match = /^(\s*)(\d+)\.(\s+)(.*)$/.exec(line)
  return match
    ? {
        indent: match[1]!.length,
        text: match[4]!,
        contentIndent: markerIndent(match[1]!, `${match[2]!}.`, match[3]!),
        number: Number(match[2]),
      }
    : null
}

function indentOf(line: string): number {
  return /^\s*/.exec(line)![0].length
}

function quoteMatch(line: string): string | null {
  const match = /^>\s?(.*)$/.exec(line)
  return match ? match[1]! : null
}

/** Splits a pipe-table row into trimmed cells, honouring a leading/trailing pipe and `\|`. */
function tableRowCells(line: string): string[] {
  let trimmed = line.trim()
  if (trimmed.startsWith('|')) trimmed = trimmed.slice(1)
  if (trimmed.endsWith('|')) trimmed = trimmed.slice(0, -1)
  const cells: string[] = []
  let current = ''
  for (let i = 0; i < trimmed.length; i++) {
    const char = trimmed[i]!
    if (char === '\\' && trimmed[i + 1] === '|') {
      current += '|'
      i++
      continue
    }
    if (char === '|') {
      cells.push(current.trim())
      current = ''
      continue
    }
    current += char
  }
  cells.push(current.trim())
  return cells
}

function isTableSeparator(line: string): boolean {
  const cells = tableRowCells(line)
  return cells.length > 0 && cells.every((cell) => /^:?-{1,}:?$/.test(cell))
}

/**
 * Whether `lines[i]` opens a table: a line with a pipe, then a separator row with the same number
 * of cells. A table can follow a sentence directly, the way GitHub reads it, so a paragraph stops
 * here too.
 */
function tableStartsAt(lines: string[], i: number): boolean {
  const line = lines[i]
  const next = lines[i + 1]
  if (line === undefined || next === undefined || !line.includes('|') || !isTableSeparator(next)) return false
  return tableRowCells(line).length === tableRowCells(next).length
}

function cellAlign(cell: string): MdAlign {
  const left = cell.startsWith(':')
  const right = cell.endsWith(':')
  if (left && right) return 'center'
  if (right) return 'right'
  if (left) return 'left'
  return null
}

function parseBlocks(lines: string[], citations: number): MdBlock[] {
  const blocks: MdBlock[] = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]!

    if (isBlank(line)) {
      i++
      continue
    }

    const fence = fenceMatch(line)
    if (fence) {
      const codeLines: string[] = []
      i++
      while (i < lines.length && !/^```\s*$/.test(lines[i]!.trim())) {
        codeLines.push(lines[i]!)
        i++
      }
      i++ // the closing fence, or simply the end of input
      blocks.push({ type: 'code', language: fence.language, text: codeLines.join('\n') })
      continue
    }

    if (ruleMatch(line)) {
      blocks.push({ type: 'rule' })
      i++
      continue
    }

    const heading = headingMatch(line)
    if (heading) {
      blocks.push({ type: 'heading', level: heading.level, children: parseInline(heading.text, citations) })
      i++
      continue
    }

    const quote = quoteMatch(line)
    if (quote !== null) {
      const quoteLines = [quote]
      i++
      while (i < lines.length) {
        const next = quoteMatch(lines[i]!)
        if (next === null) break
        quoteLines.push(next)
        i++
      }
      blocks.push({ type: 'blockquote', children: parseBlocks(quoteLines, citations) })
      continue
    }

    if (line.includes('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1]!)) {
      const header = tableRowCells(line).map((cell) => parseInline(cell, citations))
      const align = tableRowCells(lines[i + 1]!).map(cellAlign)
      i += 2
      const rows: MdInline[][][] = []
      while (i < lines.length && !isBlank(lines[i]!) && lines[i]!.includes('|')) {
        rows.push(tableRowCells(lines[i]!).map((cell) => parseInline(cell, citations)))
        i++
      }
      blocks.push({ type: 'table', header, align, rows })
      continue
    }

    const bullet = bulletMatch(line)
    const ordered = orderedMatch(line)
    if (bullet || ordered) {
      const isOrdered = Boolean(ordered)
      const markerOf = (text: string): ListMarker | null => (isOrdered ? orderedMatch(text) : bulletMatch(text))
      const baseIndent = (isOrdered ? ordered : bullet)!.indent
      const items: MdListItem[] = []
      while (i < lines.length) {
        const current = markerOf(lines[i]!)
        if (!current || current.indent !== baseIndent) break
        i++
        const itemLines = readListItem(lines, i, baseIndent, current.contentIndent)
        i += itemLines.consumed
        items.push({ children: parseBlocks([current.text, ...itemLines.lines], citations) })

        // A loose list: blank lines, then the same kind of marker at the same indent, carry on
        // the same list rather than starting a new one numbered from 1 again.
        let next = i
        while (next < lines.length && isBlank(lines[next]!)) next++
        const following = next < lines.length ? markerOf(lines[next]!) : null
        if (next > i && following && following.indent === baseIndent) i = next
      }
      const start = isOrdered ? ordered!.number : 1
      blocks.push({ type: 'list', ordered: isOrdered, ...(start !== 1 ? { start } : {}), items })
      continue
    }

    // A paragraph: consecutive lines that start none of the blocks above.
    const paraLines = [line]
    i++
    while (
      i < lines.length &&
      !isBlank(lines[i]!) &&
      !headingMatch(lines[i]!) &&
      !ruleMatch(lines[i]!) &&
      !fenceMatch(lines[i]!) &&
      quoteMatch(lines[i]!) === null &&
      !bulletMatch(lines[i]!) &&
      !orderedMatch(lines[i]!) &&
      !tableStartsAt(lines, i)
    ) {
      paraLines.push(lines[i]!)
      i++
    }
    const children: MdInline[] = []
    paraLines.forEach((paraLine, index) => {
      if (index > 0) children.push({ type: 'break' })
      children.push(...parseInline(paraLine, citations))
    })
    blocks.push({ type: 'paragraph', children })
  }

  return blocks
}

/**
 * The lines after an item's marker line that still belong to it, with the item's indent taken
 * off: a nested list (any marker indented past the item's own), a line that runs straight on from
 * the one above it with some indent, and, after blank lines, anything indented to the item's text
 * or further. A blank line kept inside an item separates its paragraphs. `consumed` counts the
 * source lines read, blank ones included.
 */
function readListItem(
  lines: string[],
  from: number,
  baseIndent: number,
  contentIndent: number,
): { lines: string[]; consumed: number } {
  const taken: string[] = []
  let i = from
  while (i < lines.length) {
    const line = lines[i]!
    if (isBlank(line)) {
      let next = i
      while (next < lines.length && isBlank(lines[next]!)) next++
      if (next >= lines.length) break
      const following = lines[next]!
      const nestedMarker = Boolean(bulletMatch(following) ?? orderedMatch(following)) && indentOf(following) > baseIndent
      if (indentOf(following) < contentIndent && !nestedMarker) break
      for (let blank = i; blank < next; blank++) taken.push('')
      i = next
      continue
    }
    const indent = indentOf(line)
    if (indent <= baseIndent) break
    taken.push(line.slice(Math.min(indent, contentIndent)))
    i++
  }
  return { lines: taken, consumed: i - from }
}

/** Punctuation that ends a sentence rather than a bare URL: "See https://example.org." */
const URL_TRAILING_PUNCTUATION = /[.,;:!?]+$/

/** The inline forms in the order they are tried at each position. A link is tried before a bare [n]. */
const INLINE_FORMS =
  /(?<code>`[^`\n]+`)|(?<bold>\*\*[^*\n]+\*\*)|(?<link>\[[^\]\n]*\]\([^)\n]*\))|(?<italicStar>(?<![\w])\*(?=\S)[^*\n]+?(?<=\S)\*(?![\w]))|(?<italicUnderscore>(?<![\w])_(?=\S)[^_\n]+?(?<=\S)_(?![\w]))|(?<url>https?:\/\/[^\s<>()]+)/

/** [1] or [2, 3]: one or more passage numbers in square brackets. Added to the forms only where it may cite. */
const CITATION_FORM = '|(?<citation>\\[\\d+(?:\\s*,\\s*\\d+)*\\])'

/**
 * Inline code, bold, italic, links, bare URLs and - where `citations` says there are passages to
 * cite - [n], left to right. The expression is built fresh on every call (rather than shared at
 * module scope as a global one) because this function recurses into a matched span's own text, and
 * two `exec` loops sharing one global regex would clobber each other's `lastIndex`.
 *
 * `citations` is 0 inside a link's own label: a link is already a button, and one inside another
 * is neither valid nor reachable.
 */
function parseInline(text: string, citations: number): MdInline[] {
  // Italic must be flanked: no letter, digit or underscore just outside either marker, and no
  // space just inside. So snake_case names (send_email_draft, gmail__send_email), globs (*.csv
  // and *.pdf) and arithmetic (2 * 3 * 4) all stay plain text.
  const pattern = new RegExp(INLINE_FORMS.source + (citations > 0 ? CITATION_FORM : ''), 'g')

  const nodes: MdInline[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null

  while ((match = pattern.exec(text))) {
    if (match.index > lastIndex) nodes.push({ type: 'text', text: text.slice(lastIndex, match.index) })
    const groups = match.groups as Record<string, string | undefined>
    let consumed = match[0].length

    if (groups.code) {
      nodes.push({ type: 'code', text: groups.code.slice(1, -1) })
    } else if (groups.bold) {
      nodes.push({ type: 'bold', children: parseInline(groups.bold.slice(2, -2), citations) })
    } else if (groups.link) {
      const parts = /^\[([^\]]*)\]\(([^)]*)\)$/.exec(groups.link)
      const label = parts?.[1] ?? ''
      const href = (parts?.[2] ?? '').trim()
      if (href && SAFE_LINK.test(href)) {
        nodes.push({ type: 'link', href, children: parseInline(label, 0) })
      } else {
        // An unsafe or malformed scheme (javascript:, data:, or anything not http(s)/mailto)
        // never becomes a link: the raw text is kept, verbatim, as plain text.
        nodes.push({ type: 'text', text: groups.link })
      }
    } else if (groups.italicStar) {
      nodes.push({ type: 'italic', children: parseInline(groups.italicStar.slice(1, -1), citations) })
    } else if (groups.italicUnderscore) {
      nodes.push({ type: 'italic', children: parseInline(groups.italicUnderscore.slice(1, -1), citations) })
    } else if (groups.citation) {
      const numbers = groups.citation
        .slice(1, -1)
        .split(',')
        .map((part) => Number(part.trim()))
      if (numbers.every((number) => number >= 1 && number <= citations)) {
        numbers.forEach((number, position) => {
          if (position > 0) nodes.push({ type: 'text', text: ' ' })
          nodes.push({ type: 'citation', index: number })
        })
      } else {
        // A number that names no passage was not a citation, whatever else the agent meant by it.
        nodes.push({ type: 'text', text: groups.citation })
      }
    } else if (groups.url) {
      // The sentence's own full stop or comma is left as text after the link, not linked.
      const url = groups.url.replace(URL_TRAILING_PUNCTUATION, '')
      consumed = url.length
      nodes.push({ type: 'link', href: url, children: [{ type: 'text', text: url }] })
    }

    lastIndex = match.index + consumed
    pattern.lastIndex = lastIndex
  }

  if (lastIndex < text.length) nodes.push({ type: 'text', text: text.slice(lastIndex) })
  return nodes
}
