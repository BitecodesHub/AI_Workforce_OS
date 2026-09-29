import axe from 'axe-core'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PasswordInput } from '../ui'
import { RouterProvider } from '../../lib/router'
import { SignIn } from '../../routes/SignIn'
import { DemoRolePicker, initialsOf, type DemoAccount } from './DemoRolePicker'
import { Stepper } from './Stepper'

/*
 * The signed-out screens' building blocks: the one-row demo role picker, the password field's
 * show and hide control, the stepper, and the sign-in screen put together from them.
 */

const ACCOUNTS: DemoAccount[] = [
  { email: 'owner@demo.test', displayName: 'Ava Owner', role: 'owner', describes: 'Everything, including billing' },
  { email: 'manager@demo.test', displayName: 'Maya Manager', role: 'manager', describes: 'Runs agents and approves actions' },
  { email: 'viewer@demo.test', displayName: 'Vik Viewer', role: 'viewer', describes: 'Reads dashboards, changes nothing' },
]

describe('DemoRolePicker', () => {
  it('offers one tile per role, each a named sign-in with the role described', () => {
    render(<DemoRolePicker accounts={ACCOUNTS} password="shared-pass" pending={null} busy={false} onPick={() => {}} />)
    const tiles = screen.getAllByRole('button')
    expect(tiles).toHaveLength(3)
    expect(tiles[1]).toHaveAccessibleName('Sign in as Maya Manager, Manager')
    expect(tiles[1]).toHaveAccessibleDescription('Runs agents and approves actions.')
    expect(screen.getByText('shared-pass')).toBeInTheDocument()
    expect(screen.getByText(/Start with Manager/)).toBeInTheDocument()
  })

  it('previews a role under the row on hover and focus, and signs it in on press', () => {
    const onPick = vi.fn()
    render(<DemoRolePicker accounts={ACCOUNTS} password={null} pending={null} busy={false} onPick={onPick} />)
    const viewer = screen.getByRole('button', { name: /Vik Viewer/ })
    fireEvent.mouseOver(viewer)
    expect(screen.getByText(/Reads dashboards, changes nothing/, { selector: 'p' })).toBeInTheDocument()
    fireEvent.mouseOut(viewer)
    fireEvent.focus(viewer)
    expect(screen.getByText(/Reads dashboards, changes nothing/, { selector: 'p' })).toBeInTheDocument()
    fireEvent.click(viewer)
    expect(onPick).toHaveBeenCalledWith(ACCOUNTS[2])
  })

  it('keeps every tile focusable but inert while a sign-in runs, and says which', () => {
    const onPick = vi.fn()
    render(
      <DemoRolePicker accounts={ACCOUNTS} password={null} pending="manager@demo.test" busy onPick={onPick} />,
    )
    const tiles = screen.getAllByRole('button')
    for (const tile of tiles) {
      expect(tile).not.toBeDisabled()
      expect(tile).toHaveAttribute('aria-disabled', 'true')
    }
    expect(tiles[1]).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByText('Signing in as Maya Manager')).toBeInTheDocument()
    fireEvent.click(tiles[0] as HTMLElement)
    expect(onPick).not.toHaveBeenCalled()
  })

  it('draws initials from the given name alone, not the role that follows it', () => {
    // Every demo account is "<given name> <role>"; using both words would double a letter
    // whenever the two happen to start alike, exactly as they do here.
    expect(initialsOf('Maya Manager')).toBe('MA')
    expect(initialsOf('Arjun Admin')).toBe('AR')
    expect(initialsOf('Priya Anne Shah')).toBe('PR')
    expect(initialsOf('Cher')).toBe('CH')
    expect(initialsOf('  ')).toBe('')
  })
})

describe('PasswordInput', () => {
  it('shows and hides the password with one named, pressed-state control', () => {
    render(<PasswordInput label="Password" defaultValue="correct horse" />)
    const field = screen.getByLabelText('Password')
    const toggle = screen.getByRole('button', { name: 'Show password' })
    expect(field).toHaveAttribute('type', 'password')
    expect(toggle).toHaveAttribute('aria-pressed', 'false')
    expect(toggle).toHaveAttribute('aria-controls', field.id)
    fireEvent.click(toggle)
    expect(field).toHaveAttribute('type', 'text')
    expect(toggle).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(toggle)
    expect(field).toHaveAttribute('type', 'password')
  })
})

describe('Stepper', () => {
  it('marks finished, current and upcoming steps in words as well as colour', () => {
    render(<Stepper steps={['You', 'Workspace', 'Working hours', 'Models']} current={1} />)
    const steps = within(screen.getByRole('list', { name: 'Progress' })).getAllByRole('listitem')
    expect(steps[0]).toHaveTextContent('You, completed')
    expect(steps[1]).toHaveAttribute('aria-current', 'step')
    expect(steps[2]).not.toHaveAttribute('aria-current')
    expect(steps[3]).toHaveTextContent('4')
  })
})

describe('SignIn', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: true, json: async () => ({ accounts: ACCOUNTS, password: 'shared-pass' }) })),
    )
    window.history.pushState({}, '', '/sign-in')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    window.history.pushState({}, '', '/')
  })

  async function renderSignIn() {
    const view = render(
      <RouterProvider>
        <SignIn />
      </RouterProvider>,
    )
    await act(async () => {})
    return view
  }

  it('puts the form first, then the demo roles, then what the product is', async () => {
    const { container } = await renderSignIn()
    expect(screen.getByRole('heading', { level: 1, name: 'Welcome back' })).toBeInTheDocument()
    const email = screen.getByLabelText('Email address')
    const roles = screen.getByRole('button', { name: /Maya Manager/ })
    const pitch = screen.getByRole('heading', { level: 2, name: /Agents that do the work/ })
    expect(email.compareDocumentPosition(roles) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(roles.compareDocumentPosition(pitch) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(container.querySelector('input[autocomplete="current-password"]')).not.toBeNull()
  })

  it('has no axe violations', async () => {
    const { container } = await renderSignIn()
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((violation) => violation.id)).toEqual([])
  }, 30_000)
})
