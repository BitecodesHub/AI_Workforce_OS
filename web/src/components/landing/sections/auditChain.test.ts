// @find: tests for audit chain, hash chain, tamper, verify, AuditChainDemo, other sections render
// @what: Tests the audit chain model, demo and render checks for other sections.
// @flow: Covers auditChain.ts and sections
import { createElement } from 'react'
import type { ComponentType } from 'react'
import axe from 'axe-core'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ENTRIES, GENESIS, fnv1a32, record, tamper, verify } from './auditChain'
import { AuditChainDemo } from './AuditChainDemo'
import { AGENT_GRANTS, defaultGrant } from './agentGrants'
import { AgentsSection } from './AgentsSection'
import { PlatformBand } from './PlatformBand'
import { LimitsSection } from './LimitsSection'
import { FinalCta } from './FinalCta'
import { LandingFooter } from './LandingFooter'
import { HowItWorks } from './HowItWorks'
import { SafetySection } from './SafetySection'
import { Faq } from './Faq'
import { ROLE_ORDER, holdsAll } from '../roles/roleData'
import { CONNECTOR_NOTICE } from '../shared/landingFacts'
import { resetDemoAccountsForTests } from '../../../lib/demo'

/*
 * The audit chain model, the demo built on it, and render checks for the other sections this
 * folder holds. jsdom has no matchMedia and no IntersectionObserver, so every component renders
 * its reduced-motion end state at once.
 */

const HASH = /^[0-9a-f]{8}$/

/**
 * Answers the demo-accounts question the way a site with, or without, demo accounts does, or
 * leaves it unanswered, which renders the same as none and is what most checks here need.
 */
function demoAccounts(answer: 'pending' | 'none' | 'offered') {
  const body = { accounts: [{ email: 'manager@demo.aiworkforce.os', displayName: 'Maya Manager', role: 'manager', describes: '' }] }
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      answer === 'pending'
        ? new Promise<Response>(() => {})
        : Promise.resolve(answer === 'offered' ? new Response(JSON.stringify(body)) : new Response(null, { status: 404 })),
    ),
  )
}

/** Lets the demo-accounts answer arrive inside act, as the page would after its first paint. */
async function settleDemoLookup() {
  await act(() => new Promise<void>((resolve) => setTimeout(resolve, 0)))
}

beforeEach(() => {
  resetDemoAccountsForTests()
  demoAccounts('pending')
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

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
    const { container } = render(createElement(AuditChainDemo))
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
    // The chain's own entries: the demo's "Try this" guide is a list too.
    const entries = within(container.querySelector('.lp-audit-list') as HTMLElement).getAllByRole('listitem')
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
    const gmail = within(hrTools).getByRole('radio', { name: 'Gmail' })
    const calendar = within(hrTools).getByRole('radio', { name: 'Calendar' })
    expect(gmail).toHaveAttribute('aria-checked', 'true')
    expect(gmail).toHaveAttribute('tabindex', '0')
    expect(calendar).toHaveAttribute('tabindex', '-1')

    gmail.focus()
    fireEvent.keyDown(gmail, { key: 'ArrowRight' })
    expect(calendar).toHaveAttribute('aria-checked', 'true')
    expect(calendar).toHaveFocus()
    expect(screen.getByText(/Deleting one waits for approval/)).toBeInTheDocument()
  })

  it('renders the platform counts with their final values for assistive technology', () => {
    const { container } = render(createElement(PlatformBand))
    const hidden = [...container.querySelectorAll('.lp-platform-stats .visually-hidden')].map((node) => node.textContent)
    expect(hidden).toEqual(['8', '7', '19', '46', '5'])
    expect(screen.getByText('Sandbox')).toBeInTheDocument()
    // The connectors in two groups, as mcp-core's catalogue splits them.
    const live = screen.getByRole('list', { name: 'Connect live' })
    expect(within(live).getAllByRole('listitem')).toHaveLength(19)
    expect(within(live).getByText('HubSpot')).toBeInTheDocument()
    expect(within(live).getByText('Gmail')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Practice data only' })).not.toBeInTheDocument()
    expect(container.querySelectorAll('.lp-platform [tabindex], .lp-platform a, .lp-platform button')).toHaveLength(0)
  })

  it('states the four limits, naming which connectors are live and which practice only', () => {
    render(createElement(LimitsSection))
    expect(screen.getByRole('heading', { level: 2, name: /Stated plainly/ })).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(4)
    expect(
      screen.getByText(/^Each connector reaches a real account only once an administrator adds its access token/),
    ).toHaveTextContent(/Until then agents work with practice data and nothing is sent\.$/)
  })

  it('closes with the demos on the page where the site has no demo accounts, and says nothing of them', async () => {
    demoAccounts('none')
    const { container } = render(createElement(FinalCta))
    // Before the answer arrives the safe variant shows, and it stays once the answer is "none".
    expect(screen.getByRole('link', { name: 'See it work' })).toHaveAttribute('href', '#demos')
    await settleDemoLookup()
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('link', { name: 'See it work' })).toHaveAttribute('href', '#demos')
    expect(screen.getByRole('link', { name: 'Create your workspace' })).toHaveAttribute('href', '/create-workspace')
    expect(container.textContent).not.toMatch(/demo account/i)
  })

  it('closes with the demo accounts where the site offers them', async () => {
    demoAccounts('offered')
    render(createElement(FinalCta))
    await settleDemoLookup()
    expect(screen.getByRole('link', { name: 'Try the demo' })).toHaveAttribute('href', '/sign-in')
    expect(screen.getByRole('link', { name: 'Create your workspace' })).toHaveAttribute('href', '/create-workspace')
  })

  it('closes with the footer links, including the page for IT teams', () => {
    render(createElement(LandingFooter))
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/sign-in')
    expect(screen.getByRole('link', { name: 'Create your workspace' })).toHaveAttribute('href', '/create-workspace')
    expect(screen.getByRole('link', { name: 'Technical details for IT teams' })).toHaveAttribute('href', '/trust')
  })

  it('explains how it works in three steps', () => {
    render(createElement(HowItWorks))
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
    expect(screen.getByRole('heading', { level: 3, name: 'Approve what matters' })).toBeInTheDocument()
  })

  it('draws who can do what from the same role data as the permission map', () => {
    render(createElement(SafetySection))
    const table = screen.getByRole('table')
    const rows = within(table).getAllByRole('row').slice(1)
    // Only what a person can do in the console today: no workspace can be closed, so no row says so.
    expect(rows).toHaveLength(5)
    expect(table.textContent).not.toMatch(/close the workspace/i)
    // Row "Approve what gets sent" (approval:decide): owner, admin and manager only.
    const approve = rows.find((row) => within(row).queryByRole('rowheader', { name: 'Approve what gets sent' }))
    expect(approve).toBeDefined()
    const cells = within(approve as HTMLElement).getAllByRole('cell')
    expect(cells.map((cell) => cell.getAttribute('data-held'))).toEqual(
      ROLE_ORDER.map((role) => String(holdsAll(role, ['approval:decide']))),
    )
    expect(cells.map((cell) => cell.getAttribute('data-held'))).toEqual(['true', 'true', 'true', 'false', 'false'])
    expect(within(approve as HTMLElement).getByText('Employee cannot approve what gets sent')).toBeInTheDocument()
  })

  it('answers the buyer questions as native disclosures, without promising live connections', () => {
    const { container } = render(createElement(Faq))
    const items = container.querySelectorAll('details')
    expect(items.length).toBeGreaterThanOrEqual(6)
    const email = screen.getByText(/not connected to real mailboxes or calendars/)
    expect(email).toHaveTextContent(CONNECTOR_NOTICE)
    // Says "not yet" once, and does not hint that some tools already connect for real.
    expect(email.textContent?.match(/\byet\b/g)).toHaveLength(1)
    expect(email).not.toHaveTextContent(/connect today|connect for real|\blive\b/i)
    expect(container.textContent).not.toMatch(/permission code|approval:decide|MCP|Spring Boot/)
    // No connector is named as working, and no demo account is promised.
    expect(container.textContent).not.toMatch(/connectors for|demo account|Slack|GitHub/i)
  })

  it('has no axe violations in any section', async () => {
    const options = { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } }
    for (const component of [
      AgentsSection,
      AuditChainDemo,
      PlatformBand,
      LimitsSection,
      FinalCta,
      LandingFooter,
      HowItWorks,
      SafetySection,
      Faq,
    ] as ReadonlyArray<ComponentType<object>>) {
      const { container, unmount } = render(createElement(component))
      expect((await axe.run(container, options)).violations).toEqual([])
      unmount()
    }
  })
})
