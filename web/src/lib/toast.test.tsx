import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider, useToast } from './toast'

/*
 * The toast provider itself, exercised through a small harness rather than through any one
 * screen's call site: a toast with an action must run that action and clear itself, and it must
 * stay on screen long enough to read and press the action, longer than a plain toast gets.
 */

function Harness({ withAction }: { withAction: boolean }) {
  const toast = useToast()
  return (
    <button
      type="button"
      onClick={() =>
        toast.success('Saved', withAction ? { action: { label: 'Undo', onSelect: () => toast.info('Undone') } } : undefined)
      }
    >
      Trigger
    </button>
  )
}

function press(name: string) {
  act(() => screen.getByRole('button', { name }).click())
}

afterEach(() => {
  vi.useRealTimers()
})

describe('a toast with an action', () => {
  it('renders the action as a button after the message, runs it and dismisses the toast', () => {
    render(
      <ToastProvider>
        <Harness withAction />
      </ToastProvider>,
    )
    press('Trigger')
    expect(screen.getByText('Saved')).toBeInTheDocument()

    press('Undo')
    expect(screen.queryByText('Saved')).not.toBeInTheDocument()
    expect(screen.getByText('Undone')).toBeInTheDocument()
  })

  it('stays on screen past the default lifetime, then clears itself', () => {
    vi.useFakeTimers()
    render(
      <ToastProvider>
        <Harness withAction />
      </ToastProvider>,
    )
    press('Trigger')

    act(() => {
      vi.advanceTimersByTime(4_500)
    })
    expect(screen.getByText('Saved')).toBeInTheDocument()

    act(() => {
      vi.advanceTimersByTime(3_500)
    })
    expect(screen.queryByText('Saved')).not.toBeInTheDocument()
  })

  it('can still be dismissed by hand before its action fires', () => {
    render(
      <ToastProvider>
        <Harness withAction />
      </ToastProvider>,
    )
    press('Trigger')
    press('Dismiss')
    expect(screen.queryByText('Saved')).not.toBeInTheDocument()
  })
})

describe('a plain toast', () => {
  it('shows no action button and clears itself at the default lifetime', () => {
    vi.useFakeTimers()
    render(
      <ToastProvider>
        <Harness withAction={false} />
      </ToastProvider>,
    )
    press('Trigger')
    expect(screen.queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument()

    act(() => {
      vi.advanceTimersByTime(4_500)
    })
    expect(screen.queryByText('Saved')).not.toBeInTheDocument()
  })
})

describe('an error toast', () => {
  it('stays on screen rather than clearing itself on a timer', () => {
    vi.useFakeTimers()
    function ErrorHarness() {
      const toast = useToast()
      return (
        <button type="button" onClick={() => toast.error('Something broke')}>
          Trigger
        </button>
      )
    }
    render(
      <ToastProvider>
        <ErrorHarness />
      </ToastProvider>,
    )
    press('Trigger')
    act(() => {
      vi.advanceTimersByTime(20_000)
    })
    expect(screen.getByText('Something broke')).toBeInTheDocument()
  })
})
