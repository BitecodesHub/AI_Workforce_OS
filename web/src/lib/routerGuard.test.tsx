// @find: tests for the router leave guard, unsaved changes, ask before leaving, Back button guard, sign out with unsaved changes, useLeaveGuard, leaveWhenAllowed, navigate force
// @what: Tests that a screen holding a leave guard is asked before any way of leaving it: a link, navigate(), Back and Forward, and actions such as signing out.
// @flow: Renders RouterProvider with a small screen that registers useLeaveGuard
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider, leaveWhenAllowed, useLeaveGuard, useRouter } from './router'
import type { LeaveGuard } from './router'

/** A screen that holds the given guard and shows where the router is. */
function Screen({ guard }: { guard: LeaveGuard | null }) {
  useLeaveGuard(guard)
  const { path, search, navigate } = useRouter()
  return (
    <div>
      <p data-testid="where">{`${path}${search.toString() ? `?${search.toString()}` : ''}`}</p>
      <a href="/elsewhere">Elsewhere</a>
      <button type="button" onClick={() => navigate('/workflows/wf-1?run=1', { replace: true })}>
        Same screen
      </button>
      <button type="button" onClick={() => navigate('/forced', { force: true })}>
        Forced
      </button>
    </div>
  )
}

function renderAt(path: string, guard: LeaveGuard | null) {
  window.history.replaceState(null, '', path)
  return render(
    <RouterProvider>
      <Screen guard={guard} />
    </RouterProvider>,
  )
}

afterEach(() => {
  window.history.replaceState(null, '', '/')
  vi.unstubAllGlobals()
})

describe('leave guard', () => {
  it('asks before a link leaves the screen, and leaves once the guard proceeds', () => {
    vi.stubGlobal('scrollTo', vi.fn())
    let proceed: (() => void) | null = null
    const guard = vi.fn<LeaveGuard>((next) => {
      proceed = next
    })
    renderAt('/workflows/wf-1', guard)

    fireEvent.click(screen.getByRole('link', { name: 'Elsewhere' }))
    expect(guard).toHaveBeenCalledTimes(1)
    expect(window.location.pathname).toBe('/workflows/wf-1')
    act(() => proceed!())
    expect(window.location.pathname).toBe('/elsewhere')
    expect(screen.getByTestId('where')).toHaveTextContent('/elsewhere')
  })

  it('never asks for a move within the same screen, or for a move made with force', () => {
    vi.stubGlobal('scrollTo', vi.fn())
    const guard = vi.fn<LeaveGuard>()
    renderAt('/workflows/wf-1', guard)

    fireEvent.click(screen.getByRole('button', { name: 'Same screen' }))
    expect(window.location.search).toBe('?run=1')
    fireEvent.click(screen.getByRole('button', { name: 'Forced' }))
    expect(window.location.pathname).toBe('/forced')
    expect(guard).not.toHaveBeenCalled()
  })

  it('puts the address back when Back is pressed, asks, and goes back only if the person proceeds', () => {
    let proceed: (() => void) | null = null
    const guard = vi.fn<LeaveGuard>((next) => {
      proceed = next
    })
    renderAt('/workflows/wf-1', guard)

    act(() => {
      window.history.replaceState(null, '', '/workflows')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    expect(guard).toHaveBeenCalledTimes(1)
    expect(window.location.pathname).toBe('/workflows/wf-1')
    expect(screen.getByTestId('where')).toHaveTextContent('/workflows/wf-1')
    act(() => proceed!())
    expect(window.location.pathname).toBe('/workflows')
    expect(screen.getByTestId('where')).toHaveTextContent('/workflows')
  })

  it('lets Back through, and runs a leaving action at once, when no screen holds a guard', () => {
    renderAt('/workflows/wf-1', null)
    act(() => {
      window.history.replaceState(null, '', '/workflows')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    expect(screen.getByTestId('where')).toHaveTextContent('/workflows')
    const action = vi.fn()
    leaveWhenAllowed(action)
    expect(action).toHaveBeenCalledTimes(1)
  })

  it('runs a leaving action such as signing out through the guard, and drops the guard when the screen closes', () => {
    const guard = vi.fn<LeaveGuard>()
    const view = renderAt('/workflows/wf-1', guard)
    const action = vi.fn()
    leaveWhenAllowed(action)
    expect(guard).toHaveBeenCalledTimes(1)
    expect(action).not.toHaveBeenCalled()
    guard.mock.calls[0]![0]()
    expect(action).toHaveBeenCalledTimes(1)

    view.unmount()
    leaveWhenAllowed(action)
    expect(action).toHaveBeenCalledTimes(2)
    expect(guard).toHaveBeenCalledTimes(1)
  })
})
