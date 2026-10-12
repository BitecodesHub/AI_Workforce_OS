// @find: tests for swipe approvals styles, undo button touch target, grab handle touch-action, deck scrolls on phones
// @what: Reads swipe-approvals.css and checks the rules a phone depends on: the Undo button is a 44 px target, only the grab handle stops the page from scrolling, and a long action label may wrap.
// @flow: Runs under vitest; reads web/src/styles/polish/swipe-approvals.css from disk
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const CSS = readFileSync(join(__dirname, 'swipe-approvals.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')

/** The declarations of the first rule whose selector is exactly `selector`, as property to value. */
function rule(selector: string): Record<string, string> {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = new RegExp(`(^|\\})\\s*${escaped}\\s*\\{([^}]*)\\}`, 'm').exec(CSS)
  if (!match) throw new Error(`No rule for ${selector}`)
  const declarations: Record<string, string> = {}
  for (const part of match[2]!.split(';')) {
    const [property, ...value] = part.split(':')
    if (property && value.length) declarations[property.trim()] = value.join(':').trim()
  }
  return declarations
}

describe('swipe-approvals.css', () => {
  it('makes Undo, the most time-critical control, at least 44 px tall', () => {
    expect(Number.parseInt(rule('.deck-undo-button')['min-height'] ?? '0', 10)).toBeGreaterThanOrEqual(44)
  })

  it('stops the page scrolling only on the grab handle; the rest of the card scrolls up and down', () => {
    expect(rule('.deck-card')['touch-action']).toBe('pan-y')
    expect(rule('.deck-handle')['touch-action']).toBe('none')
    expect(rule('.deck-grab')['touch-action']).toBeUndefined()
    const blocking = [...CSS.matchAll(/([^{}]+)\{[^}]*touch-action:\s*none/g)].map((found) => found[1]!.trim())
    expect(blocking).toEqual(['.deck-handle'])
  })

  it('lets a long action label wrap rather than overflow three buttons across a phone', () => {
    expect(rule('.deck-action')['white-space']).toBe('normal')
  })
})
