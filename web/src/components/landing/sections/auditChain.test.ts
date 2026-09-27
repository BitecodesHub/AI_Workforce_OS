import { createElement } from 'react'
import axe from 'axe-core'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ENTRIES, GENESIS, fnv1a32, record, tamper, verify } from './auditChain'
import { AuditChainDemo } from './AuditChainDemo'
import { AGENT_GRANTS, defaultGrant } from './agentGrants'
import { AgentsSection } from './AgentsSection'
import { PlatformBand } from './PlatformBand'
import { LimitsAndCta } from './LimitsAndCta'
import { LandingFooter } from './LandingFooter'

/*
 * The audit chain model, the demo built on it, and render checks for the other sections this
 * folder holds. jsdom has no matchMedia and no IntersectionObserver, so every component renders
 * its reduced-motion end state at once.
 */

const HASH = /^[0-9a-f]{8}$/

describe('auditChain', () => {
  it('computes FNV-1a deterministically as 8 lowercase hex characters', () => {
    // Published FNV-1a 32-bit test vectors.
    expect(fnv1a32('')).toBe('811c9dc5')
    expect(fnv1a32('a')).toBe('e40c292c')
    expect(fnv1a32('foobar')).toBe('bf9cf968')
    for (const input of ['', 'a', 'seq|1042', 'A manager approved gmail.send_message']) {
      expect(fnv1a32(input)).toMatch(HASH)
      expect(fnv1a32(input)).toBe(fnv1a32(input))
    }
  })

  it('records a chain that verifies untampered', () => {
    const recorded = record(ENTRIES)
    expect(recorded).toHaveLength(4)
    expect(recorded.map((entry) => entry.seq)).toEqual([1041, 1042, 1043, 1044])
    expect(recorded[0]?.prev).toBe(GENESIS)
    recorded.forEach((entry, index) => {
      expect(entry.hash).toMatch(HASH)
      if (index > 0) expect(entry.prev).toBe(recorded[index - 1]?.hash)
    })
    const results = verify(recorded, ENTRIES)
    expect(results.every((result) => !result.altered && !result.prevMismatch)).toBe(true)
  })

  it('flags entry 2 as altered and entry 3 as the only broken link after tampering', () => {
    const recorded = record(ENTRIES)
    const tampered = tamper(ENTRIES)
    expect(tampered[1]?.text).toBe('A manager rejected gmail.send_message')
    expect(ENTRIES[1]?.text).toBe('A manager approved gmail.send_message')

    const results = verify(recorded, tampered)
    expect(results.map((result) => result.altered)).toEqual([false, true, false, false])
    expect(results.map((result) => result.prevMismatch)).toEqual([false, false, true, false])
    expect(results[1]?.recomputed).not.toBe(recorded[1]?.hash)
    expect(recorded[2]?.prev).toBe(recorded[1]?.hash)
  })
})

describe('AuditChainDemo', () => {
  function status(): HTMLElement {
    return screen.getByRole('status')
  }

  it('starts silent and verified, then exposes the edit and recovers', () => {
    render(createElement(AuditChainDemo))
    expect(screen.getByText('Simulated')).toBeInTheDocument()
    expect(status().textContent).toBe('')
    expect(screen.queryByText('Chain broken here')).not.toBeInTheDocument()
    expect(screen.getByText('seq 1042')).toBeInTheDocument()
    expect(screen.queryByText(new RegExp(`${String.fromCharCode(35)}1042`))).not.toBeInTheDocument()

    const toggle = screen.getByRole('button', { name: 'Alter entry 2' })
    expect(toggle).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(toggle)

    expect(toggle).toHaveAttribute('aria-pressed', 'true')
    expect(status()).toHaveTextContent(
      /^Entry 3 records the previous hash [0-9a-f]{8}, but entry 2 now hashes to [0-9a-f]{8}\. The alteration is detectable\.$/,
    )
    const entries = screen.getAllByRole('listitem')
    expect(within(entries[1] as HTMLElement).getByText('Altered')).toBeInTheDocument()
    expect(within(entries[2] as HTMLElement).getByText('Chain broken here')).toBeInTheDocument()
    expect(entries[1]?.querySelector('del')).toHaveTextContent('approved')
    expect(entries[1]?.querySelector('ins')).toHaveTextContent('rejected')
    expect(screen.getByText(/It does not by itself stop someone/)).toHaveAttribute('data-shown', 'true')

    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-pressed', 'false')
    expect(status()).toHaveTextContent('Every entry records the hash of the entry before it. The chain verifies.')
    expect(screen.queryByText('Chain broken here')).not.toBeInTheDocument()
  })
})

describe('sections', () => {
  it('gives every agent a default grant, preferring a gated one', () => {
    expect(defaultGrant('hr')).toBe('gmail')
    expect(defaultGrant('eng')).toBe('slack')
    expect(defaultGrant('research')).toBe('drive')
    expect(defaultGrant('support')).toBe('gmail')
    for (const grants of Object.values(AGENT_GRANTS)) {
      for (const grant of grants) {
        expect(grant.sentence).not.toContain('!')
        expect(grant.sentence.endsWith('.')).toBe(true)
      }
    }
  })

  it('renders four agent tiles whose tool chips move and select with the arrow keys', () => {
    render(createElement(AgentsSection))
    expect(screen.getAllByRole('article')).toHaveLength(4)
    const hrTools = screen.getByRole('radiogroup', { name: 'HR tools' })
    const gmail = within(hrTools).getByRole('radio', { name: 'gmail' })
    const calendar = within(hrTools).getByRole('radio', { name: 'calendar' })
    expect(gmail).toHaveAttribute('aria-checked', 'true')
    expect(gmail).toHaveAttribute('tabindex', '0')
    expect(calendar).toHaveAttribute('tabindex', '-1')

    gmail.focus()
    fireEvent.keyDown(gmail, { key: 'ArrowRight' })
    expect(calendar).toHaveAttribute('aria-checked', 'true')
    expect(calendar).toHaveFocus()
    expect(screen.getByText(/calendar\.delete_event, waits for a person/)).toBeInTheDocument()
  })

  it('renders the platform counts with their final values for assistive technology', () => {
    const { container } = render(createElement(PlatformBand))
    const hidden = [...container.querySelectorAll('.lp-platform-stats .visually-hidden')].map((node) => node.textContent)
    expect(hidden).toEqual(['8', '7', '6', '46', '5'])
    expect(screen.getByText('Sandbox')).toBeInTheDocument()
    expect(container.querySelectorAll('.lp-platform [tabindex], .lp-platform a, .lp-platform button')).toHaveLength(0)
  })

  it('states the four limits beside real call-to-action links', () => {
    render(createElement(LimitsAndCta))
    expect(screen.getByRole('heading', { level: 2, name: /Stated plainly/ })).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(4)
    expect(screen.getByRole('link', { name: 'Try a demo account' })).toHaveAttribute('href', '/sign-in')
    expect(screen.getByRole('link', { name: 'Create a workspace' })).toHaveAttribute('href', '/create-workspace')
  })

  it('closes with the footer links', () => {
    render(createElement(LandingFooter))
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/sign-in')
    expect(screen.getByRole('link', { name: 'Create a workspace' })).toHaveAttribute('href', '/create-workspace')
  })

  it('has no axe violations in any section', async () => {
    const options = { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } }
    for (const component of [AgentsSection, AuditChainDemo, PlatformBand, LimitsAndCta, LandingFooter]) {
      const { container, unmount } = render(createElement(component))
      expect((await axe.run(container, options)).violations).toEqual([])
      unmount()
    }
  })
})
