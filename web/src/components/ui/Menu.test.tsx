// @find: tests for dropdown menu, menu keyboard, arrow keys, Escape, checkbox radio items, accessibility, MenuButton
// @what: Tests menu keyboard behaviour and floating position.
// @flow: Covers Menu.tsx.
import axe from 'axe-core'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { MenuEntry } from './Menu'
import { floatingMenuPosition, MenuButton } from './Menu'

/*
 * The dropdown's keyboard behaviour (D17's outside-press fix is covered where MenuButton is used
 * from a real screen, not here): arrow and Home/End movement, Esc returning focus to the trigger,
 * and the checkbox/radio items that stay open so several can be set in one visit.
 */

function renderMenu(items: MenuEntry[]) {
  return render(<MenuButton label="More actions" items={items} />)
}

const PLAIN_ITEMS: MenuEntry[] = [
  { id: 'one', label: 'First', onSelect: () => {} },
  { id: 'two', label: 'Second', onSelect: () => {} },
  { id: 'three', label: 'Third', onSelect: () => {} },
]

describe('MenuButton', () => {
  it('opens on the trigger and moves focus to the first item', () => {
    renderMenu(PLAIN_ITEMS)
    fireEvent.click(screen.getByRole('button', { name: 'More actions' }))
    expect(screen.getByRole('menuitem', { name: 'First' })).toHaveFocus()
  })

  it('moves focus with ArrowDown, ArrowUp, Home and End', () => {
    renderMenu(PLAIN_ITEMS)
    fireEvent.click(screen.getByRole('button', { name: 'More actions' }))
    const [first, second, third] = [
      screen.getByRole('menuitem', { name: 'First' }),
      screen.getByRole('menuitem', { name: 'Second' }),
      screen.getByRole('menuitem', { name: 'Third' }),
    ]
    fireEvent.keyDown(first, { key: 'ArrowDown' })
    expect(second).toHaveFocus()
    fireEvent.keyDown(second, { key: 'End' })
    expect(third).toHaveFocus()
    fireEvent.keyDown(third, { key: 'Home' })
    expect(first).toHaveFocus()
    fireEvent.keyDown(first, { key: 'ArrowUp' })
    expect(third).toHaveFocus()
  })

  it('closes on Esc and returns focus to the trigger', () => {
    renderMenu(PLAIN_ITEMS)
    const trigger = screen.getByRole('button', { name: 'More actions' })
    fireEvent.click(trigger)
    fireEvent.keyDown(screen.getByRole('menuitem', { name: 'First' }), { key: 'Escape' })
    expect(screen.queryByRole('menu')).toBeNull()
    expect(trigger).toHaveFocus()
  })

  it('closes a plain item on select', () => {
    const onSelect = vi.fn()
    renderMenu([{ id: 'one', label: 'First', onSelect }])
    fireEvent.click(screen.getByRole('button', { name: 'More actions' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'First' }))
    expect(onSelect).toHaveBeenCalledOnce()
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('renders a checked entry as menuitemcheckbox and keeps the menu open on select', () => {
    const onSelect = vi.fn()
    renderMenu([{ id: 'flag', label: 'Show archived', checked: false, onSelect }])
    fireEvent.click(screen.getByRole('button', { name: 'More actions' }))
    const item = screen.getByRole('menuitemcheckbox', { name: 'Show archived' })
    expect(item).toHaveAttribute('aria-checked', 'false')
    fireEvent.click(item)
    expect(onSelect).toHaveBeenCalledOnce()
    expect(screen.getByRole('menu')).toBeInTheDocument()
  })

  it('renders a grouped checked entry as menuitemradio inside a labelled group', () => {
    const items: MenuEntry[] = [
      { id: 'sort-group', groupLabel: 'Sort by' },
      { id: 'sort-name', label: 'Name', checked: true, group: 'sort-group', onSelect: () => {} },
      { id: 'sort-date', label: 'Date', checked: false, group: 'sort-group', onSelect: () => {} },
    ]
    renderMenu(items)
    fireEvent.click(screen.getByRole('button', { name: 'More actions' }))
    const group = screen.getByRole('group', { name: 'Sort by' })
    expect(group.querySelectorAll('[role="menuitemradio"]')).toHaveLength(2)
    expect(screen.getByRole('menuitemradio', { name: 'Name' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('menuitemradio', { name: 'Date' })).toHaveAttribute('aria-checked', 'false')
  })

  it('has no axe violations open', async () => {
    const { container } = renderMenu(PLAIN_ITEMS)
    fireEvent.click(screen.getByRole('button', { name: 'More actions' }))
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((v) => v.id)).toEqual([])
  })

  it('portals a floating panel to the body and closes on a press outside it', () => {
    const { container } = render(<MenuButton label="Row actions" trigger="icon" icon={null} items={PLAIN_ITEMS} portal align="end" />)
    const trigger = screen.getByRole('button', { name: 'Row actions' })
    fireEvent.click(trigger)
    const menu = screen.getByRole('menu')
    expect(container.contains(menu)).toBe(false)
    expect(document.body.contains(menu)).toBe(true)
    expect(menu.style.position).toBe('fixed')
    // A press inside the floating panel keeps it open; one outside closes it.
    fireEvent.pointerDown(screen.getByRole('menuitem', { name: 'Second' }))
    expect(screen.getByRole('menu')).toBeInTheDocument()
    fireEvent.pointerDown(document.body)
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('returns focus to the trigger on Tab from a floating panel', () => {
    render(<MenuButton label="Row actions" items={PLAIN_ITEMS} portal />)
    const trigger = screen.getByRole('button', { name: 'Row actions' })
    fireEvent.click(trigger)
    fireEvent.keyDown(screen.getByRole('menuitem', { name: 'First' }), { key: 'Tab' })
    expect(screen.queryByRole('menu')).toBeNull()
    expect(trigger).toHaveFocus()
  })
})

describe('floatingMenuPosition', () => {
  const viewport = { width: 375, height: 800 }
  const panel = { width: 200, height: 180 }

  it('drops below the trigger, its end edge aligned to the trigger', () => {
    const position = floatingMenuPosition({ top: 100, bottom: 130, left: 300, right: 330 }, panel, viewport, 'end')
    expect(position).toEqual({ top: 134, left: 130, placement: 'below' })
  })

  it('flips above when there is no room below', () => {
    const position = floatingMenuPosition({ top: 700, bottom: 730, left: 300, right: 330 }, panel, viewport, 'end')
    expect(position.placement).toBe('above')
    expect(position.top).toBe(700 - 4 - 180)
  })

  it('stays inside the window horizontally', () => {
    expect(floatingMenuPosition({ top: 100, bottom: 130, left: 10, right: 40 }, panel, viewport, 'end').left).toBe(8)
    expect(floatingMenuPosition({ top: 100, bottom: 130, left: 300, right: 330 }, panel, viewport, 'start').left).toBe(375 - 8 - 200)
  })
})
