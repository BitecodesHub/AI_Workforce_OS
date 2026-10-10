// @find: tests for demo stage, demo tabs, ARIA tabs, hidden demos, link opens demo tab
// @what: Tests the demo tab stage: tabs, hidden-not-unmounted demos and links that open a tab.
// @flow: Covers DemoStage.tsx
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { REVEAL_EVENT } from '../shared/revealEvent'
import { DemoStage } from './DemoStage'

/*
 * The four demos behind one row of tabs. Each demo is hidden rather than unmounted, so its anchor
 * and its state survive; the tabs follow the ARIA tabs pattern; and a link or an address naming a
 * demo opens its tab.
 */

const IDS = ['approval', 'failover', 'audit', 'cited'] as const

function tabs(): HTMLElement[] {
  return screen.getAllByRole('tab')
}

function selectedTab(): HTMLElement | undefined {
  return tabs().find((tab) => tab.getAttribute('aria-selected') === 'true')
}

function panel(id: string): HTMLElement {
  return document.getElementById(`${id}-panel`) as HTMLElement
}

afterEach(() => {
  window.history.replaceState(null, '', '/')
})

describe('DemoStage', () => {
  it('shows one demo at a time behind four tabs, and keeps every anchor', () => {
    render(<DemoStage />)
    expect(screen.getByRole('tablist', { name: 'Demos' })).toBeInTheDocument()
    expect(tabs()).toHaveLength(4)
    expect(selectedTab()).toHaveAttribute('id', 'approval-tab')
    expect(panel('approval')).not.toHaveAttribute('hidden')
    for (const id of IDS.slice(1)) expect(panel(id)).toHaveAttribute('hidden')
    for (const tab of tabs()) {
      const controlled = document.getElementById(tab.getAttribute('aria-controls') ?? '')
      expect(controlled).toHaveAttribute('role', 'tabpanel')
      expect(controlled).toHaveAttribute('aria-labelledby', tab.id)
    }
    for (const id of IDS) expect(document.getElementById(id)).not.toBeNull()
  })

  it('moves between tabs with the arrow keys, Home and End, keeping one tab stop', () => {
    render(<DemoStage />)
    const first = tabs()[0] as HTMLElement
    first.focus()

    fireEvent.keyDown(first, { key: 'ArrowRight' })
    expect(selectedTab()).toHaveAttribute('id', 'failover-tab')
    expect(document.activeElement).toBe(selectedTab())
    expect(panel('failover')).not.toHaveAttribute('hidden')
    expect(panel('approval')).toHaveAttribute('hidden')

    fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'End' })
    expect(selectedTab()).toHaveAttribute('id', 'cited-tab')
    fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'ArrowRight' })
    expect(selectedTab()).toHaveAttribute('id', 'approval-tab')
    fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'ArrowLeft' })
    expect(selectedTab()).toHaveAttribute('id', 'cited-tab')
    fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'Home' })
    expect(selectedTab()).toHaveAttribute('id', 'approval-tab')

    expect(tabs().filter((tab) => tab.tabIndex === 0)).toHaveLength(1)
  })

  it('selects a demo by click without losing the others', () => {
    render(<DemoStage />)
    fireEvent.click(document.getElementById('audit-tab') as HTMLElement)
    expect(selectedTab()).toHaveAttribute('id', 'audit-tab')
    fireEvent.click(document.getElementById('approval-tab') as HTMLElement)
    expect(selectedTab()).toHaveAttribute('id', 'approval-tab')
    expect(document.getElementById('audit')).not.toBeNull()
  })

  it('opens the tab that a link elsewhere on the page names, and ignores other targets', () => {
    render(<DemoStage />)
    act(() => {
      window.dispatchEvent(new CustomEvent(REVEAL_EVENT, { detail: { id: 'audit' } }))
    })
    expect(selectedTab()).toHaveAttribute('id', 'audit-tab')
    expect(panel('audit')).not.toHaveAttribute('hidden')

    act(() => {
      window.dispatchEvent(new CustomEvent(REVEAL_EVENT, { detail: { id: 'roles' } }))
    })
    expect(selectedTab()).toHaveAttribute('id', 'audit-tab')
  })

  it('opens the demo named in the address on arrival', () => {
    window.history.replaceState(null, '', '/home#cited')
    render(<DemoStage />)
    expect(selectedTab()).toHaveAttribute('id', 'cited-tab')
  })
})
