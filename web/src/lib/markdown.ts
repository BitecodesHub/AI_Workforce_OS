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
 * lists with one nested level, block quotes, fenced code with an optional language, horizontal
 * rules, GitHub pipe tables, and inline code, bold, italic, links and bare URLs.
 */

export type MdInline =
  | { type: 'text'; text: string }
  | { type: 'break' }
  | { type: 'code'; text: string }
  | { type: 'bold'; children: MdInline[] }
  | { type: 'italic'; children: MdInline[] }
  | { type: 'link'; href: string; children: MdInline[] }

export type MdListItem = { children: MdBlock[] }

export type MdAlign = 'left' | 'center' | 'right' | null

export type MdBlock =
  | { type: 'paragraph'; children: MdInline[] }
  | { type: 'heading'; level: number; children: MdInline[] }
  | { type: 'list'; ordered: boolean; items: MdListItem[] }
  | { type: 'blockquote'; children: MdBlock[] }
  | { type: 'code'; language: string | null; text: string }
  | { type: 'rule' }
  | { type: 'table'; header: MdInline[][]; align: MdAlign[]; rows: MdInline[][][] }

/** Above this size, the source renders as one plain paragraph rather than being parsed. */
const MAX_LENGTH = 60_000

/** The only link schemes the renderer may turn into a clickable `<a>`. Anything else stays text. */
const SAFE_LINK = /^(https?:|mailto:)/i

export function parseMarkdown(src: string): MdBlock[] {
  if (!src) return []
  if (src.length > MAX_LENGTH) return [{ type: 'paragraph', children: rawInline(src) }]
  const lines = src.replace(/\r\n?/g, '\n').split('\n')
  return parseBlocks(lines)
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

function bulletMatch(line: string): { indent: number; text: string } | null {
  const match = /^(\s*)[-*]\s+(.*)$/.exec(line)
  return match ? { indent: match[1]!.length, text: match[2]! } : null
}

function orderedMatch(line: string): { indent: number; text: string } | null {
  const match = /^(\s*)\d+\.\s+(.*)$/.exec(line)
  return match ? { indent: match[1]!.length, text: match[2]! } : null
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

function cellAlign(cell: string): MdAlign {
  const left = cell.startsWith(':')
  const right = cell.endsWith(':')
  if (left && right) return 'center'
  if (right) return 'right'
  if (left) return 'left'
  return null
}

function parseBlocks(lines: string[]): MdBlock[] {
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
      blocks.push({ type: 'heading', level: heading.level, children: parseInline(heading.text) })
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
      blocks.push({ type: 'blockquote', children: parseBlocks(quoteLines) })
      continue
    }

    if (line.includes('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1]!)) {
      const header = tableRowCells(line).map(parseInline)
      const align = tableRowCells(lines[i + 1]!).map(cellAlign)
      i += 2
      const rows: MdInline[][][] = []
      while (i < lines.length && !isBlank(lines[i]!) && lines[i]!.includes('|')) {
        rows.push(tableRowCells(lines[i]!).map(parseInline))
        i++
      }
      blocks.push({ type: 'table', header, align, rows })
      continue
    }

    const bullet = bulletMatch(line)
    const ordered = orderedMatch(line)
    if (bullet || ordered) {
      const isOrdered = Boolean(ordered)
      const baseIndent = (isOrdered ? ordered : bullet)!.indent
      const items: MdListItem[] = []
      while (i < lines.length && !isBlank(lines[i]!)) {
        const current = isOrdered ? orderedMatch(lines[i]!) : bulletMatch(lines[i]!)
        if (!current || current.indent !== baseIndent) break
        i++

        const nestedLines: string[] = []
        while (i < lines.length && !isBlank(lines[i]!)) {
          const nested = bulletMatch(lines[i]!) ?? orderedMatch(lines[i]!)
          if (!nested || nested.indent <= baseIndent) break
          nestedLines.push(lines[i]!)
          i++
        }

        const itemBlocks: MdBlock[] = [{ type: 'paragraph', children: parseInline(current.text) }]
        if (nestedLines.length) itemBlocks.push(...parseBlocks(nestedLines))
        items.push({ children: itemBlocks })
      }
      blocks.push({ type: 'list', ordered: isOrdered, items })
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
      !orderedMatch(lines[i]!)
    ) {
      paraLines.push(lines[i]!)
      i++
    }
    const children: MdInline[] = []
    paraLines.forEach((paraLine, index) => {
      if (index > 0) children.push({ type: 'break' })
      children.push(...parseInline(paraLine))
    })
    blocks.push({ type: 'paragraph', children })
  }

  return blocks
}

/**
 * Inline code, bold, italic, links and bare URLs, left to right. A regex literal is created fresh
 * on every call (rather than shared at module scope) because this function recurses into a
 * matched span's own text, and two `exec` loops sharing one global regex would clobber each
 * other's `lastIndex`.
 */
function parseInline(text: string): MdInline[] {
  const pattern =
    /(?<code>`[^`\n]+`)|(?<bold>\*\*[^*\n]+\*\*)|(?<link>\[[^\]\n]*\]\([^)\n]*\))|(?<italicStar>\*[^*\n]+\*)|(?<italicUnderscore>_[^_\n]+_)|(?<url>https?:\/\/[^\s<>()]+)/g

  const nodes: MdInline[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null

  while ((match = pattern.exec(text))) {
    if (match.index > lastIndex) nodes.push({ type: 'text', text: text.slice(lastIndex, match.index) })
    const groups = match.groups as Record<string, string | undefined>

    if (groups.code) {
      nodes.push({ type: 'code', text: groups.code.slice(1, -1) })
    } else if (groups.bold) {
      nodes.push({ type: 'bold', children: parseInline(groups.bold.slice(2, -2)) })
    } else if (groups.link) {
      const parts = /^\[([^\]]*)\]\(([^)]*)\)$/.exec(groups.link)
      const label = parts?.[1] ?? ''
      const href = (parts?.[2] ?? '').trim()
      if (href && SAFE_LINK.test(href)) {
        nodes.push({ type: 'link', href, children: parseInline(label) })
      } else {
        // An unsafe or malformed scheme (javascript:, data:, or anything not http(s)/mailto)
        // never becomes a link: the raw text is kept, verbatim, as plain text.
        nodes.push({ type: 'text', text: groups.link })
      }
    } else if (groups.italicStar) {
      nodes.push({ type: 'italic', children: parseInline(groups.italicStar.slice(1, -1)) })
    } else if (groups.italicUnderscore) {
      nodes.push({ type: 'italic', children: parseInline(groups.italicUnderscore.slice(1, -1)) })
    } else if (groups.url) {
      nodes.push({ type: 'link', href: groups.url, children: [{ type: 'text', text: groups.url }] })
    }

    lastIndex = match.index + match[0].length
    pattern.lastIndex = lastIndex
  }

  if (lastIndex < text.length) nodes.push({ type: 'text', text: text.slice(lastIndex) })
  return nodes
}
