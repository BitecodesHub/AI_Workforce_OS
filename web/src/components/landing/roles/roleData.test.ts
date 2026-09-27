import { createElement } from 'react'
import axe from 'axe-core'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import {
  ALL_CODES,
  CAPABILITIES,
  CONSOLE_AREAS,
  PERMISSION_GROUPS,
  ROLE_CODES,
  ROLE_ORDER,
  compareRoles,
  countAreas,
  holdsAll,
} from './roleData'
import { RoleSwitcher } from './RoleSwitcher'

/*
 * The role data must match the platform exactly: Permission.java for the codes, PermissionSeeder
 * for the compositions and App.tsx for the console areas. These figures are the ones the public
 * page states, so a drift here is a false claim there.
 */

describe('roleData', () => {
  it('gives the five roles 46, 45, 25, 12 and 8 codes', () => {
    expect(ROLE_ORDER.map((role) => ROLE_CODES[role].size)).toEqual([46, 45, 25, 12, 8])
  })

  it('has 46 unique codes, equal to the union of the 17 groups', () => {
    expect(PERMISSION_GROUPS).toHaveLength(17)
    expect(ALL_CODES).toHaveLength(46)
    expect(new Set(ALL_CODES).size).toBe(46)
    const union = new Set(PERMISSION_GROUPS.flatMap((group) => group.codes))
    expect(union).toEqual(new Set(ALL_CODES))
    for (const group of PERMISSION_GROUPS) {
      for (const code of group.codes) expect(code.startsWith(`${group.domain}:`)).toBe(true)
    }
  })

  it('only uses codes that exist', () => {
    const known = new Set(ALL_CODES)
    for (const role of ROLE_ORDER) for (const code of ROLE_CODES[role]) expect(known.has(code)).toBe(true)
    for (const capability of CAPABILITIES) for (const code of capability.codes) expect(known.has(code)).toBe(true)
    for (const area of CONSOLE_AREAS) if (area.code !== null) expect(known.has(area.code)).toBe(true)
  })

  it('makes employee a subset of manager, and admin everything but closing the workspace', () => {
    for (const code of ROLE_CODES.employee) expect(ROLE_CODES.manager.has(code)).toBe(true)
    expect(compareRoles('owner', 'admin')).toEqual({ added: ['workspace:delete'], removed: [] })
  })

  it('compares manager with employee as 13 added and 0 removed', () => {
    const { added, removed } = compareRoles('manager', 'employee')
    expect(added).toHaveLength(13)
    expect(removed).toHaveLength(0)
    expect(compareRoles('employee', 'manager')).toEqual({ added: [], removed: added })
  })

  it('opens 11, 11, 10, 8 and 7 of the 11 console areas', () => {
    expect(CONSOLE_AREAS).toHaveLength(11)
    expect(ROLE_ORDER.map((role) => countAreas(role))).toEqual([11, 11, 10, 8, 7])
  })

  it('keeps the plain-words claims true to the seeder', () => {
    expect(holdsAll('manager', ['approval:decide'])).toBe(true)
    expect(holdsAll('employee', ['approval:read'])).toBe(true)
    expect(holdsAll('employee', ['approval:decide'])).toBe(false)
    expect(holdsAll('manager', ['member:invite', 'role:update'])).toBe(false)
    expect(holdsAll('manager', ['audit:read'])).toBe(false)
    expect(holdsAll('viewer', ['chat:use', 'task:create'])).toBe(false)
  })
})

describe('RoleSwitcher', () => {
  function announcer(container: HTMLElement): HTMLElement {
    const region = container.querySelector<HTMLElement>('p[aria-live="polite"]')
    if (!region) throw new Error('no polite announcer')
    return region
  }

  it('opens on the manager tab with an empty announcer', () => {
    const { container } = render(createElement(RoleSwitcher))
    const tab = screen.getByRole('tab', { name: 'Manager, 25 of 46 permission codes' })
    expect(tab).toHaveAttribute('aria-selected', 'true')
    expect(tab).toHaveAttribute('tabindex', '0')
    expect(screen.getAllByRole('tab').filter((t) => t.getAttribute('tabindex') === '0')).toHaveLength(1)
    const panel = screen.getByRole('tabpanel')
    expect(panel).toHaveAttribute('aria-labelledby', 'roles-tab-manager')
    expect(within(panel).getByRole('heading', { level: 3, name: 'Manager' })).toBeInTheDocument()
    expect(within(panel).getByText('10 of 11 areas')).toBeInTheDocument()
    expect(within(panel).getByRole('link', { name: /Sign in as a manager/ })).toHaveAttribute('href', '/sign-in')
    expect(announcer(container).textContent).toBe('')
  })

  it('moves and selects with the arrow keys, Home and End, and announces', () => {
    const { container } = render(createElement(RoleSwitcher))
    const manager = screen.getByRole('tab', { name: /^Manager/ })
    manager.focus()
    fireEvent.keyDown(manager, { key: 'ArrowRight' })
    const employee = screen.getByRole('tab', { name: /^Employee/ })
    expect(employee).toHaveAttribute('aria-selected', 'true')
    expect(employee).toHaveFocus()
    expect(announcer(container)).toHaveTextContent('Employee: 12 of 46 permission codes. 8 of 11 console areas.')

    fireEvent.keyDown(employee, { key: 'End' })
    expect(screen.getByRole('tab', { name: /^Viewer/ })).toHaveAttribute('aria-selected', 'true')
    fireEvent.keyDown(screen.getByRole('tab', { name: /^Viewer/ }), { key: 'ArrowDown' })
    expect(screen.getByRole('tab', { name: /^Owner/ })).toHaveAttribute('aria-selected', 'true')
    fireEvent.keyDown(screen.getByRole('tab', { name: /^Owner/ }), { key: 'ArrowUp' })
    expect(screen.getByRole('tab', { name: /^Viewer/ })).toHaveAttribute('aria-selected', 'true')
    fireEvent.keyDown(screen.getByRole('tab', { name: /^Viewer/ }), { key: 'Home' })
    expect(screen.getByRole('tab', { name: /^Owner/ })).toHaveFocus()
  })

  it('marks the codes a comparison adds and removes', () => {
    const { container } = render(createElement(RoleSwitcher))
    fireEvent.change(screen.getByLabelText('Compare with'), { target: { value: 'employee' } })
    expect(announcer(container)).toHaveTextContent('Manager compared with Employee: 13 added, none removed.')
    expect(container.querySelectorAll('.lp-roles-code[data-diff="added"]')).toHaveLength(13)
    expect(container.querySelectorAll('.lp-roles-code[data-diff="removed"]')).toHaveLength(0)
    expect(screen.getByRole('list', { name: 'approval: 2 of 2 held' })).toBeInTheDocument()

    // Selecting the compared role clears the comparison rather than comparing a role with itself.
    fireEvent.click(screen.getByRole('tab', { name: /^Employee/ }))
    expect(screen.getByLabelText('Compare with')).toHaveValue('none')
    expect(container.querySelectorAll('.lp-roles-code[data-diff]')).toHaveLength(0)
  })

  it('has no axe violations in the default or compare state', async () => {
    const { container } = render(createElement(RoleSwitcher))
    const options = { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } }
    expect((await axe.run(container, options)).violations).toEqual([])
    fireEvent.change(screen.getByLabelText('Compare with'), { target: { value: 'viewer' } })
    expect((await axe.run(container, options)).violations).toEqual([])
  })
})
