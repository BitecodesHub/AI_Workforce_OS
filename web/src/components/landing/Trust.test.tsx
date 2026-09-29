import axe from 'axe-core'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
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
  if (typeof globalThis.fetch !== 'function') {
    vi.stubGlobal('fetch', () => Promise.reject(new Error('No network in tests')))
  }
  fetchSpy = vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('The public page must not make network requests'))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('Trust', () => {
  it('makes no network request and has exactly one h1', () => {
    const { container } = renderTrust()
    expect(container.querySelectorAll('h1')).toHaveLength(1)
    expect(fetchSpy).not.toHaveBeenCalled()
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
