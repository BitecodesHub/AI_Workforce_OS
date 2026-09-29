import axe from 'axe-core'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import { App } from '../../App'
import { RouterProvider } from '../../lib/router'
import { Landing } from '../../routes/Landing'

/*
 * The composed public page.
 *
 * jsdom has no matchMedia and no IntersectionObserver, so the page renders exactly as a visitor
 * with reduced motion sees it: every demo at its end state, with nothing animating. These checks
 * cover what no single owner's test can: the page as a whole makes no network request, has no
 * accessibility violations, keeps one h1, and shows each demo's end state.
 *
 * This file lives outside src/routes/ on purpose: the eyebrow test scans every .tsx file there.
 */

function renderLanding() {
  return render(
    <RouterProvider>
      <Landing />
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
  fetchSpy = vi
    .spyOn(globalThis, 'fetch')
    .mockRejectedValue(new Error('The public page must not make network requests'))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('Landing', () => {
  it('makes no network request', () => {
    renderLanding()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('has no axe violations', async () => {
    const { container } = renderLanding()
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations).toEqual([])
    expect(fetchSpy).not.toHaveBeenCalled()
  }, 30_000)

  it('renders both demos at their end state under reduced motion, in plain words', () => {
    renderLanding()
    expect(within(region('approval')).getByText('Exactly what will be sent')).toBeInTheDocument()
    // The plain voice shows the email as an email, not as JSON.
    expect(within(region('approval')).getByText('Subject')).toBeInTheDocument()
    expect(within(region('cited')).getAllByText('Project_Proposal.pdf').length).toBeGreaterThan(0)
    expect(document.getElementById('failover')).toBeNull()
  })

  it('speaks to the people choosing the product, not the engineers running it', () => {
    renderLanding()
    const text = screen.getByRole('main').textContent ?? ''
    for (const jargon of [
      'permission code',
      'approval:decide',
      'gmail.send_message',
      'Spring Boot',
      'MCP',
      'tool server',
      'Retry-After',
      'hash',
    ]) {
      expect(text, `the home page says "${jargon}"`).not.toContain(jargon)
    }
    expect(screen.getByRole('link', { name: 'Technical details for IT teams' })).toHaveAttribute('href', '/trust')
  })

  it('has exactly one h1', () => {
    const { container } = renderLanding()
    expect(container.querySelectorAll('h1')).toHaveLength(1)
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  it('opens with the skip link and a main landmark', () => {
    const { container } = renderLanding()
    const first = container.querySelector('a')
    expect(first).toHaveTextContent('Skip to content')
    expect(first).toHaveAttribute('href', '#main')
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main')
  })

  it('keeps every anchor id unique and every in-page link pointing at one', () => {
    const { container } = renderLanding()
    const ids = [...container.querySelectorAll('[id]')].map((element) => element.id)
    expect(ids.filter((id, index) => ids.indexOf(id) !== index)).toEqual([])
    const anchors = ['main', 'how', 'team', 'demos', 'approval', 'cited', 'safety', 'faq', 'start']
    for (const id of anchors) expect(ids, `missing anchor ${id}`).toContain(id)
    const hashes = [...container.querySelectorAll('a[href^="#"]')].map((link) => link.getAttribute('href') ?? '')
    expect(hashes.length).toBeGreaterThan(0)
    for (const hash of hashes) expect(ids, `link ${hash} has no target`).toContain(hash.slice(1))
  })

  it('opens a hidden demo when a link on the page points at it', () => {
    const scroll = vi.fn()
    Object.defineProperty(Element.prototype, 'scrollIntoView', { value: scroll, configurable: true, writable: true })
    try {
      renderLanding()
      expect(region('cited-tab')).toHaveAttribute('aria-selected', 'false')
      const link = document.querySelector('.lp-benefits a[href="#cited"]') as HTMLElement
      fireEvent.click(link)
      expect(region('cited-tab')).toHaveAttribute('aria-selected', 'true')
      expect(region('cited-panel')).not.toHaveAttribute('hidden')
      expect(scroll).toHaveBeenCalled()
    } finally {
      delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView
      window.history.replaceState(null, '', '/')
    }
  })

  it('gives each demo one polite status line', () => {
    renderLanding()
    // One demo shows at a time, so each is opened from its tab before its status is counted.
    for (const id of ['approval', 'cited']) {
      fireEvent.click(region(`${id}-tab`))
      expect(within(region(id)).getAllByRole('status')).toHaveLength(1)
    }
  })
})

describe('App title for the public page', () => {
  afterEach(() => {
    window.history.pushState({}, '', '/')
  })

  it('titles the signed-out root and /home as the public page', () => {
    for (const path of ['/', '/home']) {
      window.history.pushState({}, '', path)
      const { unmount } = render(
        <RouterProvider>
          <App />
        </RouterProvider>,
      )
      expect(document.title).toBe('AI employees for your business · AI Workforce OS')
      unmount()
    }
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
