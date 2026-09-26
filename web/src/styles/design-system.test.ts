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

  it('keeps radii to the two families the brief allows', () => {
    const radii = [...TOKENS.matchAll(/--radius-[\w-]+:\s*(\d+)px/g)].map((match) => Number(match[1]))
    expect(radii.length).toBeGreaterThan(5)
    for (const radius of radii) {
      const chrome = radius >= 15 && radius <= 22
      const control = radius >= 4 && radius <= 12
      const pill = radius === 999
      expect(chrome || control || pill, `radius ${radius}px belongs to neither family`).toBe(true)
    }
  })

  it('uses the spacing scale from the brief', () => {
    for (const step of [4, 6, 8, 12, 16, 22, 32, 44]) {
      expect(TOKENS).toContain(`${step}px`)
    }
  })
})

describe('components', () => {
  it('draws every border from --line', () => {
    const borders = [...COMPONENTS.matchAll(/border(?:-\w+)?:\s*1px solid ([^;]+);/g)].map((m) => m[1]!.trim())
    expect(borders.length).toBeGreaterThan(5)
    for (const border of borders) {
      const allowed =
        border.includes('var(--line)') ||
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
    const screens = TSX_FILES.filter((file) => file.includes('/routes/'))
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
