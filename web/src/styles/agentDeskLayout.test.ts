// @find: tests for agent desk layout at phone width, take over controls no sideways scroll, touch targets 44px, hand back button wraps
// @what: Checks agent-desk.css keeps the desk and its take over controls to one column no wider than the screen, lets the hand-back button wrap, and gives touch screens 44px targets.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const css = readFileSync(join(__dirname, 'polish/agent-desk.css'), 'utf8')

/** The body of the first rule whose selector list is exactly `selector`, or ''. */
function rule(selector: string, source = css): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/\s+/g, '\\s+')
  return new RegExp(`(?:^|\\n)\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(source)?.[1] ?? ''
}

describe('agent desk layout at phone width', () => {
  it('keeps the take over controls to one column that may shrink, so a long agent name cannot push the desk sideways', () => {
    expect(rule('.takeover')).toContain('grid-template-columns: minmax(0, 1fr);')
    expect(rule('.agent-desk')).toContain('grid-template-columns: minmax(0, 1fr);')
    expect(rule('.agent-desk-grid,\n.agent-desk-single')).toContain('grid-template-columns: minmax(0, 1fr);')
  })

  it('lets the hand-back button wrap inside the screen', () => {
    const button = rule('.takeover-banner .takeover-handback-open')
    expect(button).toContain('white-space: normal;')
    expect(button).toContain('max-width: 100%;')
  })

  it('gives the keys, the hide option and the trace link 44px targets on touch screens', () => {
    const coarse = /@media \(pointer: coarse\) \{([\s\S]*?)\n\}/.exec(css)?.[1] ?? ''
    for (const selector of ['.takeover-key', '.takeover-hide', '.agent-desk-trace']) {
      expect(rule(selector, coarse), `${selector} on touch screens`).toContain('min-height: 44px;')
    }
  })
})
