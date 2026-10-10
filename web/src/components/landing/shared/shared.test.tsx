// @find: tests for shared landing components, reduced motion, LandingRoot, DemoFrame, SegmentedControl, hooks
// @what: Tests the shared landing foundation in a jsdom environment.
// @flow: Covers files in shared/
import { useState } from 'react'
import { act, fireEvent, render, renderHook, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useReducedMotion } from '../../../hooks/useReducedMotion'
import { useSequence } from '../../../hooks/useSequence'
import { useCountUp } from '../../../hooks/useCountUp'
import { DemoFrame } from './DemoFrame'
import { SegmentedControl } from './SegmentedControl'
import { LandingRoot, useLandingMotion } from './LandingRoot'
import { SectionHead } from './LandingSection'
import { Icon } from './Icon'

/*
 * jsdom has no matchMedia and no IntersectionObserver, which is exactly the environment the
 * foundation promises to degrade gracefully in: reduced motion, everything visible, end states
 * rendered at once. Tests that need motion on install a matchMedia stub and remove it afterwards.
 */

const originalMatchMedia = window.matchMedia

function setMatchMedia(value: ((query: string) => MediaQueryList) | undefined) {
  Object.defineProperty(window, 'matchMedia', { configurable: true, writable: true, value })
}

function stubMotionOn() {
  setMatchMedia(
    (query: string) =>
      ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
        dispatchEvent: () => false,
      }) as unknown as MediaQueryList,
  )
}

afterEach(() => {
  setMatchMedia(originalMatchMedia)
  vi.useRealTimers()
})

type Role = 'manager' | 'employee' | 'viewer'

const ROLE_OPTIONS: ReadonlyArray<{ value: Role; label: string }> = [
  { value: 'manager', label: 'Manager' },
  { value: 'employee', label: 'Employee' },
  { value: 'viewer', label: 'Viewer' },
]

function RoleHarness({ onChange }: { onChange: (value: Role) => void }) {
  const [value, setValue] = useState<Role>('manager')
  return (
    <SegmentedControl
      legend="Deciding as"
      value={value}
      options={ROLE_OPTIONS}
      onChange={(next) => {
        setValue(next)
        onChange(next)
      }}
    />
  )
}

describe('useReducedMotion', () => {
  it('returns true when matchMedia is unavailable', () => {
    setMatchMedia(undefined)
    const { result } = renderHook(() => useReducedMotion())
    expect(result.current).toBe(true)
  })

  it('follows the media query when matchMedia exists', () => {
    stubMotionOn()
    const { result } = renderHook(() => useReducedMotion())
    expect(result.current).toBe(false)
  })
})

describe('useSequence', () => {
  it('runs every step synchronously, in order of time, when motion is reduced', () => {
    setMatchMedia(undefined)
    const calls: string[] = []
    const { result } = renderHook(() => useSequence())
    act(() => {
      result.current.play([
        { at: 900, run: () => calls.push('last') },
        { at: 0, run: () => calls.push('first') },
        { at: 300, run: () => calls.push('middle') },
      ])
    })
    expect(calls).toEqual(['first', 'middle', 'last'])
  })

  it('schedules one timer per step when motion is on, and clears them on unmount', () => {
    stubMotionOn()
    vi.useFakeTimers()
    const calls: string[] = []
    const { result, unmount } = renderHook(() => useSequence())
    const firstPlay = result.current.play

    act(() => {
      result.current.play([
        { at: 0, run: () => calls.push('start') },
        { at: 200, run: () => calls.push('end') },
      ])
    })
    expect(calls).toEqual([])
    act(() => {
      vi.advanceTimersByTime(0)
    })
    expect(calls).toEqual(['start'])
    act(() => {
      vi.advanceTimersByTime(200)
    })
    expect(calls).toEqual(['start', 'end'])
    expect(result.current.play).toBe(firstPlay)

    act(() => {
      result.current.play([{ at: 100, run: () => calls.push('never') }])
    })
    unmount()
    vi.advanceTimersByTime(500)
    expect(calls).toEqual(['start', 'end'])
  })
})

describe('useCountUp', () => {
  it('returns the target at once when motion is reduced', () => {
    setMatchMedia(undefined)
    const { result } = renderHook(() => useCountUp(46, false))
    expect(result.current).toBe(46)
  })
})

describe('DemoFrame', () => {
  it('renders an empty polite status, the Simulated tag and its bento area', () => {
    render(
      <DemoFrame
        area="audit"
        index="03"
        name="Audit chain"
        title="It records decisions and outcomes"
        lead="Every decision is recorded."
        status=""
      >
        <p>Body</p>
      </DemoFrame>,
    )

    const status = screen.getByRole('status')
    expect(status.textContent).toBe('')
    expect(status).toHaveAttribute('aria-atomic', 'true')
    expect(screen.getByText('Simulated')).toBeInTheDocument()

    const tile = screen.getByRole('article', { name: 'It records decisions and outcomes' })
    expect(tile).toHaveAttribute('id', 'audit')
    expect(tile).toHaveAttribute('data-area', 'audit')
    // Without IntersectionObserver the reveal marks the tile as seen straight away.
    expect(tile).toHaveAttribute('data-inview', 'true')
    expect(screen.getByRole('heading', { level: 3 })).toHaveAttribute('id', 'audit-title')
    expect(screen.getByText('03 · Audit chain')).toBeInTheDocument()
  })
})

describe('SegmentedControl', () => {
  it('changes value on click', () => {
    const onChange = vi.fn()
    render(<RoleHarness onChange={onChange} />)

    expect(screen.getByRole('group', { name: 'Deciding as' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Manager' })).toBeChecked()

    fireEvent.click(screen.getByRole('radio', { name: 'Employee' }))

    expect(onChange).toHaveBeenCalledWith('employee')
    expect(screen.getByRole('radio', { name: 'Employee' })).toBeChecked()
    expect(screen.getByRole('radio', { name: 'Manager' })).not.toBeChecked()

    const names = new Set(screen.getAllByRole('radio').map((radio) => radio.getAttribute('name')))
    expect(names.size).toBe(1)
  })
})

describe('LandingRoot and helpers', () => {
  it('switches motion off in an environment without matchMedia and hides the aurora', () => {
    setMatchMedia(undefined)
    const { container } = render(
      <LandingRoot>
        <p>Content</p>
      </LandingRoot>,
    )
    const root = container.querySelector('.lp')
    expect(root).toHaveAttribute('data-motion', 'off')
    expect(root).toHaveAttribute('data-ambient', 'running')
    expect(container.querySelector('.lp-aurora')).toHaveAttribute('aria-hidden', 'true')
    expect(container.querySelectorAll('.lp-aurora-blob')).toHaveLength(3)
  })

  it('reports standalone motion state outside a LandingRoot', () => {
    setMatchMedia(undefined)
    const { result } = renderHook(() => useLandingMotion())
    expect(result.current.reduced).toBe(true)
    expect(result.current.ambientPaused).toBe(false)
    expect(() => result.current.setAmbientPaused(true)).not.toThrow()
  })

  it('renders a section head with the aside only when a lead is given', () => {
    const { rerender, container } = render(
      <SectionHead eyebrow="Under the hood" title="What is actually running" titleId="platform-title" band />,
    )
    expect(screen.getByRole('heading', { level: 2 })).toHaveAttribute('id', 'platform-title')
    expect(screen.getByRole('heading', { level: 2 })).toHaveClass('lp-h2', 'lp-h2-band')
    expect(container.querySelector('.lp-head-aside')).toBeNull()

    rerender(
      <SectionHead eyebrow="Who can do what" title="Five roles" titleId="roles-title" lead="Roles are composed." />,
    )
    expect(container.querySelector('.lp-head-aside .lp-lead')).toHaveTextContent('Roles are composed.')
  })

  it('draws icons as hidden, unfocusable SVG', () => {
    const { container } = render(<Icon name="lock" size={16} />)
    const svg = container.querySelector('svg')
    expect(svg).toHaveAttribute('aria-hidden', 'true')
    expect(svg).toHaveAttribute('focusable', 'false')
    expect(svg).toHaveAttribute('width', '16')
  })
})
