// @find: tests for design system, tokens only, no raw hex colours, border rules, radius rules, font weights, landing styles rules, polish styles rules, chat layout at phone width
// @what: Tests that enforce the design rules across all stylesheets.
// @flow: Reads the css files under src/styles
import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import * as ts from 'typescript'

const SRC = join(__dirname, '..')
const TOKENS = readFileSync(join(SRC, 'styles/tokens.css'), 'utf8')
const COMPONENTS = readFileSync(join(SRC, 'styles/components.css'), 'utf8')

function walk(dir: string, extensions: string[]): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) return walk(full, extensions)
    return extensions.some((ext) => entry.endsWith(ext)) ? [full] : []
  })
}

function jsxTextNodes(file: string, source: string): string[] {
  const parsed = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
  const texts: string[] = []
  const visit = (node: ts.Node) => {
    if (ts.isJsxText(node)) texts.push(node.text)
    ts.forEachChild(node, visit)
  }
  visit(parsed)
  return texts
}

const TSX_FILES = walk(SRC, ['.tsx'])

describe('tokens', () => {
  it('defines every colour from the brief, unchanged', () => {
    const required = {
      '--blue': '#2458e5',
      '--blue-dark': '#1947c8',
      '--blue-light': '#eef3ff',
      '--ink': '#202735',
      '--muted': '#637083',
      '--line': '#e5e9ef',
      '--green': '#147b65',
      '--paper': '#f8fafc',
    }
    for (const [token, value] of Object.entries(required)) {
      expect(TOKENS, `${token} must be ${value}`).toContain(`${token}: ${value}`)
    }
  })

  it('keeps radii to one scale, every other radius an alias onto it', () => {
    const literal = [...TOKENS.matchAll(/--radius-([\w-]+):\s*(\d+)px/g)].map((match) => Number(match[2]))
    expect(literal.sort((a, b) => a - b)).toEqual([6, 10, 16, 20, 999])
    const aliases = [...TOKENS.matchAll(/--radius-([\w-]+):\s*([^;]+);/g)].filter((match) => !/^\d+px$/.test(match[2]!.trim()))
    expect(aliases.length).toBeGreaterThan(5)
    for (const alias of aliases) {
      expect(alias[2]!.trim(), `--radius-${alias[1]} must alias the scale`).toMatch(/^var\(--radius-(sm|md|lg|xl|pill)\)$/)
    }
  })

  it('defines the motion and depth tokens every transition and float draws from', () => {
    for (const token of ['--duration-fast: 120ms', '--duration-base: 180ms', '--duration-slow: 260ms', '--ease-standard:']) {
      expect(TOKENS).toContain(token)
    }
    for (const token of ['--shadow-sm:', '--shadow-md:', '--shadow-lg:', '--focus-outline:']) {
      expect(TOKENS).toContain(token)
    }
  })

  it('uses the spacing scale from the brief', () => {
    for (const step of [4, 6, 8, 12, 16, 22, 32, 44]) {
      expect(TOKENS).toContain(`${step}px`)
    }
  })

  it('sets everything in Inter, self-hosted, with mono drawing the same family', () => {
    expect(TOKENS).toMatch(/--font-body:\s*'Inter Variable'/)
    expect(TOKENS).toContain('--font-mono: var(--font-body);')
    const main = readFileSync(join(SRC, 'main.tsx'), 'utf8')
    expect(main).toContain("import '@fontsource-variable/inter")
    // No font CDN, and nothing left of the typeface Inter replaced.
    const index = readFileSync(join(SRC, '..', 'index.html'), 'utf8')
    expect(index).not.toMatch(/fonts\.(googleapis|gstatic)\.com|apercu/i)
    expect(TOKENS).not.toMatch(/Aper[cç]u/)
  })

  it('keeps every weight token at 600 or lighter', () => {
    const weights = [...TOKENS.matchAll(/--weight-[\w-]+:\s*(\d+)/g)].map((m) => Number(m[1]))
    expect(weights.length).toBeGreaterThanOrEqual(4)
    for (const weight of weights) expect(weight, `weight ${weight} is heavier than the system allows`).toBeLessThanOrEqual(600)
  })
})

describe('type weights', () => {
  const STYLE_FILES = walk(join(SRC, 'styles'), ['.css']).map((file) => ({ file, source: readFileSync(file, 'utf8') }))

  it('takes every font weight from a token, never a raw number or keyword', () => {
    for (const { file, source } of STYLE_FILES) {
      for (const m of source.matchAll(/font-weight:\s*([^;]+);/g)) {
        expect(m[1]!.trim(), `${file} sets font-weight "${m[1]}"`).toMatch(/^var\(--weight-[a-z-]+\)$/)
      }
    }
  })
})

describe('components', () => {
  it('draws every border from --line (or --line-strong, for form controls)', () => {
    const borders = [...COMPONENTS.matchAll(/border(?:-\w+)?:\s*1px solid ([^;]+);/g)].map((m) => m[1]!.trim())
    expect(borders.length).toBeGreaterThan(5)
    for (const border of borders) {
      const allowed =
        (border.includes('var(--line)') || border.includes('var(--line-strong)')) ||
        border.includes('rgba(210, 221, 238') ||
        border === 'transparent'
      expect(allowed, `border "${border}" is not --line`).toBe(true)
    }
  })

  it('gives the navbar no bottom border and no background', () => {
    const navbar = COMPONENTS.slice(COMPONENTS.indexOf('.navbar {'), COMPONENTS.indexOf('.navbar-glass'))
    expect(navbar).toContain('background: transparent')
    expect(navbar).toContain('border-bottom: none')
    expect(navbar).toContain('box-shadow: none')
    expect(navbar).toContain('height: var(--navbar-height)')
  })

  it('never puts a shadow on a card at rest', () => {
    const card = COMPONENTS.slice(COMPONENTS.indexOf('.card {'), COMPONENTS.indexOf('.card-interactive'))
    expect(card).not.toContain('box-shadow:')
  })

  it('keeps focus visible', () => {
    expect(TOKENS).toContain(':focus-visible')
    expect(TOKENS).not.toMatch(/outline:\s*none\s*;[^}]*}\s*$/m)
  })

  it('puts one ring on a focused input, never two', () => {
    const input = COMPONENTS.slice(COMPONENTS.indexOf('.input:focus'), COMPONENTS.indexOf('.input::placeholder'))
    const rings = [...input.matchAll(/box-shadow:/g)]
    expect(rings).toHaveLength(1)
  })
})

describe('screens', () => {
  it('keeps raw colour values out of components', () => {
    for (const file of TSX_FILES) {
      const source = readFileSync(file, 'utf8')
      const hexes = source.match(/#[0-9a-fA-F]{3,8}\b/g) ?? []
      expect(hexes, `${file} contains a raw colour: ${hexes.join(', ')}`).toHaveLength(0)
    }
  })

  it('opens every screen with an eyebrow', () => {
    // A test file beside its screen is not a screen; it need not mention an eyebrow to pass.
    const screens = TSX_FILES.filter((file) => file.includes('/routes/') && !/\.test\.tsx$/.test(file))
    expect(screens.length).toBeGreaterThan(0)
    for (const file of screens) {
      const source = readFileSync(file, 'utf8')
      expect(source, `${file} has no eyebrow`).toMatch(/eyebrow=|<Eyebrow>/)
    }
  })

  it('writes interface text without exclamation marks or emoji', () => {
    const emoji = /[\p{Extended_Pictographic}\uFE0F\u20E3\u{1F1E6}-\u{1F1FF}]/u
    for (const file of TSX_FILES) {
      const source = readFileSync(file, 'utf8')
      for (const text of jsxTextNodes(file, source)) {
        expect(text, `${file} uses an exclamation mark`).not.toContain('!')
        expect(emoji.test(text), `${file} uses an emoji in interface text`).toBe(false)
      }
    }
  })

  it('caps the primary navigation at six destinations', () => {
    const navbar = readFileSync(join(SRC, 'components/layout/Navbar.tsx'), 'utf8')
    const capsule = navbar.slice(navbar.indexOf('const NAV_ITEMS'), navbar.indexOf('] as const'))
    const items = [...capsule.matchAll(/\{ label: '/g)]
    expect(items.length).toBeLessThanOrEqual(6)
  })
})

/*
 * The public page has its own stylesheets, which components.css's checks never see. These hold
 * them to the same rules: borders from --line or the glass rgba, radii from the token families,
 * no raw hex, and keyframe names that cannot collide with the console's.
 */
describe('landing styles', () => {
  const LANDING_DIR = join(SRC, 'styles/landing')
  const LANDING_FILES = walk(LANDING_DIR, ['.css'])
  const landing = LANDING_FILES.map((file) => ({ file, source: readFileSync(file, 'utf8') }))
  const RADIUS = /^((0|var\(--radius-[a-z-]+\))(\s+(0|var\(--radius-[a-z-]+\))){0,3}|inherit)$/

  it('imports every landing stylesheet from index.css', () => {
    const index = readFileSync(join(LANDING_DIR, 'index.css'), 'utf8')
    const others = readdirSync(LANDING_DIR).filter((entry) => entry.endsWith('.css') && entry !== 'index.css')
    expect(others.length).toBeGreaterThan(0)
    for (const file of others) {
      expect(index, `index.css does not import ${file}`).toContain(`@import './${file}'`)
    }
  })

  it('draws every border from --line', () => {
    const borders = landing.flatMap(({ source }) =>
      [...source.matchAll(/border(?:-\w+)?:\s*1px solid ([^;]+);/g)].map((m) => m[1]!.trim()),
    )
    expect(borders.length).toBeGreaterThanOrEqual(6)
    for (const border of borders) {
      const allowed =
        (border.includes('var(--line)') || border.includes('var(--line-strong)')) ||
        border.includes('rgba(210, 221, 238') ||
        border === 'transparent'
      expect(allowed, `border "${border}" is not --line`).toBe(true)
    }
  })

  it('takes every radius from the token families', () => {
    for (const { file, source } of landing) {
      for (const m of source.matchAll(/border-radius:\s*([^;]+);/g)) {
        const value = m[1]!.trim()
        expect(value, `${file} uses border-radius "${value}"`).toMatch(RADIUS)
      }
    }
  })

  it('keeps raw colour values out', () => {
    for (const { file, source } of landing) {
      const hexes = source.match(/#[0-9a-fA-F]{3,8}\b/g) ?? []
      expect(hexes, `${file} contains a raw colour: ${hexes.join(', ')}`).toHaveLength(0)
    }
  })

  it('names every keyframe with the lp- prefix', () => {
    for (const { file, source } of landing) {
      for (const m of source.matchAll(/@keyframes\s+([^\s{]+)/g)) {
        expect(m[1]!, `${file} declares keyframes "${m[1]}"`).toMatch(/^lp-/)
      }
    }
  })
})

/* Screen polish stylesheets refine components.css and follow exactly its rules. */
describe('polish styles', () => {
  const POLISH_DIR = join(SRC, 'styles/polish')
  const files = walk(POLISH_DIR, ['.css']).map((file) => ({ file, source: readFileSync(file, 'utf8') }))
  const RADIUS = /^((0|var\(--radius-[a-z-]+\))(\s+(0|var\(--radius-[a-z-]+\))){0,3}|inherit)$/

  it('imports every polish stylesheet from index.css', () => {
    const index = readFileSync(join(POLISH_DIR, 'index.css'), 'utf8')
    for (const entry of readdirSync(POLISH_DIR).filter((name) => name.endsWith('.css') && name !== 'index.css')) {
      expect(index, `index.css does not import ${entry}`).toContain(`@import './${entry}'`)
    }
  })

  it('draws borders from --line, radii from tokens, and no raw hex', () => {
    for (const { file, source } of files) {
      for (const m of source.matchAll(/border(?:-\w+)?:\s*1px solid ([^;]+);/g)) {
        const border = m[1]!.trim()
        expect(
          (border.includes('var(--line)') || border.includes('var(--line-strong)')) || border.includes('rgba(210, 221, 238') || border === 'transparent',
          `${file} border "${border}" is not --line`,
        ).toBe(true)
      }
      for (const m of source.matchAll(/border-radius:\s*([^;]+);/g)) {
        expect(m[1]!.trim(), `${file} uses border-radius "${m[1]}"`).toMatch(RADIUS)
      }
      const hexes = source.match(/#[0-9a-fA-F]{3,8}\b/g) ?? []
      expect(hexes, `${file} contains a raw colour: ${hexes.join(', ')}`).toHaveLength(0)
    }
  })
})


describe('chat layout at phone width', () => {
  it('keeps the chat panel to one column no wider than the screen, so a long code line scrolls in its own box', () => {
    const chat = readFileSync(join(SRC, 'styles/polish/chat.css'), 'utf8')
    const rule = /\.chat-main \{[^}]*display: grid;[^}]*\}/.exec(chat)?.[0] ?? ''
    expect(rule).toContain('grid-template-columns: minmax(0, 1fr);')
  })
})
