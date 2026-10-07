import axe from 'axe-core'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import { DEMO_ACCOUNTS_URL, resetDemoAccountsForTests } from '../../lib/demo'
import { RouterProvider } from '../../lib/router'
import { Trust } from '../../routes/Trust'

/*
 * The page for IT and security teams: everything technical the home page no longer carries.
 * All four demos in their technical voice, the permission map, what runs, and the limits.
 *
 * Kept outside src/routes/ for the same reason as Landing.test.tsx: the eyebrow test scans every
 * .tsx file there.
 */

function renderTrust() {
  return render(
    <RouterProvider>
      <Trust />
    </RouterProvider>,
  )
}

function region(id: string): HTMLElement {
  const element = document.getElementById(id)
  if (!element) throw new Error(`No element with id ${id}`)
  return element
}

let fetchSpy: MockInstance<typeof globalThis.fetch>

beforeEach(() => {
  resetDemoAccountsForTests()
  if (typeof globalThis.fetch !== 'function') {
    vi.stubGlobal('fetch', () => Promise.reject(new Error('No network in tests')))
  }
  // The one request allowed: whether this site offers demo accounts. It is left unanswered,
  // which renders exactly like a site that offers none.
  fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation((input) =>
    String(input) === DEMO_ACCOUNTS_URL
      ? new Promise<Response>(() => {})
      : Promise.reject(new Error('The public page makes no request but the demo-account lookup')),
  )
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('Trust', () => {
  it('asks the network only whether demo accounts exist, and has exactly one h1', () => {
    const { container } = renderTrust()
    expect(container.querySelectorAll('h1')).toHaveLength(1)
    expect(fetchSpy.mock.calls.map(([input]) => String(input))).toEqual([DEMO_ACCOUNTS_URL])
  })

  it('names every connector as able to connect live', () => {
    renderTrust()
    const live = screen.getByRole('list', { name: 'Connect live' })
    const names = within(live).getAllByRole('listitem').map((item) => item.textContent)
    expect(names).toHaveLength(19)
    expect(names).toEqual(expect.arrayContaining(['GitHub', 'Gmail', 'Salesforce', 'Webhook']))
    expect(screen.queryByRole('list', { name: 'Practice data only' })).not.toBeInTheDocument()
  })

  it('has no axe violations', async () => {
    const { container } = renderTrust()
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations).toEqual([])
  }, 30_000)

  it('keeps every technical section and anchor, with every in-page link pointing at one', () => {
    const { container } = renderTrust()
    const ids = [...container.querySelectorAll('[id]')].map((element) => element.id)
    expect(ids.filter((id, index) => ids.indexOf(id) !== index)).toEqual([])
    for (const id of ['main', 'demos', 'approval', 'failover', 'audit', 'cited', 'roles', 'platform', 'limits', 'start']) {
      expect(ids, `missing anchor ${id}`).toContain(id)
    }
    const hashes = [...container.querySelectorAll('a[href^="#"]')].map((link) => link.getAttribute('href') ?? '')
    for (const hash of hashes) expect(ids, `link ${hash} has no target`).toContain(hash.slice(1))
  })

  it('shows all four demos in the technical voice, and the role explorer', () => {
    renderTrust()
    expect(screen.getAllByRole('tab', { name: /Approval gate|Model routing|Audit chain|Cited answers/ })).toHaveLength(4)
    expect(within(region('approval')).getAllByText(/gmail\.send_message/).length).toBeGreaterThan(0)
    expect(within(region('failover')).getAllByText('Answered').length).toBeGreaterThan(0)
    expect(screen.getByRole('tab', { name: /^Manager/ })).toHaveAttribute('aria-selected', 'true')
    const back = screen.getAllByRole('link', { name: 'Back to the overview' })
    expect(back.length).toBeGreaterThan(0)
    for (const link of back) expect(link).toHaveAttribute('href', '/home')
  })

  it('gives each demo one polite status line', () => {
    renderTrust()
    for (const id of ['approval', 'failover', 'audit', 'cited']) {
      fireEvent.click(region(`${id}-tab`))
      expect(within(region(id)).getAllByRole('status')).toHaveLength(1)
    }
  })
})
