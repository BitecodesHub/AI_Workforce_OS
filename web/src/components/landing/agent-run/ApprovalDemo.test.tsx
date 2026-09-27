import { StrictMode } from 'react'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApprovalDemo } from './ApprovalDemo'

/*
 * jsdom has no matchMedia, so the demo renders with reduced motion: parked at the gate, with every
 * sequence running to its end state at once. One test installs a matchMedia stub to check that
 * autoplay reaches the gate with motion on.
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

describe('ApprovalDemo with reduced motion', () => {
  it('renders parked at the gate, showing exactly what will be sent', () => {
    render(<ApprovalDemo />)
    expect(screen.getByText('Exactly what will be sent')).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Email that will be sent' })).toHaveTextContent(
      '"to": "newhire@example.com"',
    )
    expect(screen.getByText('Simulated')).toBeInTheDocument()
    // The status line is empty at mount.
    expect(screen.getByRole('status')).toHaveTextContent('')
    expect(screen.getByRole('radio', { name: 'Manager' })).toBeChecked()
    expect(screen.queryByRole('button', { name: 'Start the run' })).not.toBeInTheDocument()
  })

  it('clicking Approve resumes the run to completion and focuses the result', () => {
    render(<ApprovalDemo />)
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
    expect(screen.getByRole('status')).toHaveTextContent(
      'The run resumed and completed.',
    )
    const heading = screen.getByRole('heading', { name: 'Sent, then completed' })
    expect(heading).toHaveFocus()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('choosing Employee hides Approve and explains why', () => {
    render(<ApprovalDemo />)
    fireEvent.click(screen.getByRole('radio', { name: 'Employee' }))
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
    expect(screen.getByText(/manager, admin and owner roles hold/)).toBeInTheDocument()
    expect(screen.getByText('Exactly what will be sent')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Let the deadline pass' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Deciding as an employee.')
  })

  it('a viewer does not see the payload, but can let the deadline pass', () => {
    render(<ApprovalDemo />)
    fireEvent.click(screen.getByRole('radio', { name: 'Viewer' }))
    expect(screen.queryByText('Exactly what will be sent')).not.toBeInTheDocument()
    expect(screen.getByText(/lacks approval:read/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Let the deadline pass' }))
    expect(screen.getByRole('status')).toHaveTextContent('Nobody decided in time')
    expect(screen.getByRole('heading', { name: 'Run cancelled' })).toHaveFocus()
  })

  it('reject cancels, and Run it again parks the run with focus on the card', () => {
    render(<ApprovalDemo />)
    fireEvent.click(screen.getByRole('button', { name: 'Reject' }))
    expect(screen.getByRole('heading', { name: 'Run cancelled' })).toHaveFocus()
    expect(screen.getByText('Nothing was sent.', { exact: false })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Run it again' }))
    expect(screen.getByRole('status')).toHaveTextContent('The run is parked, waiting for approval.')
    expect(screen.getByRole('heading', { name: 'Send the welcome email to the new hire' })).toHaveFocus()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument()
  })
})

describe('ApprovalDemo with motion on', () => {
  it('autoplays to the gate without moving focus, then Approve completes', () => {
    stubMotionOn()
    vi.useFakeTimers()
    render(
      <StrictMode>
        <ApprovalDemo />
      </StrictMode>,
    )
    expect(screen.getByText(/will appear here/)).toBeInTheDocument()

    act(() => {
      vi.advanceTimersByTime(0)
    })
    expect(screen.getByRole('status')).toHaveTextContent('Running. Drafting the email.')

    act(() => {
      vi.advanceTimersByTime(2000)
    })
    expect(screen.getByRole('status')).toHaveTextContent('The run is parked, waiting for approval.')
    expect(document.body).toHaveFocus()

    const approve = screen.getByRole('button', { name: 'Approve' })
    fireEvent.click(approve)
    act(() => {
      vi.advanceTimersByTime(0)
    })
    // While resuming, the trigger stays in place but is inert.
    expect(approve).toHaveAttribute('aria-disabled', 'true')
    fireEvent.click(approve)

    act(() => {
      vi.advanceTimersByTime(2000)
    })
    expect(screen.getByRole('status')).toHaveTextContent(
      'The run resumed and completed.',
    )
    expect(screen.getByRole('heading', { name: 'Sent, then completed' })).toHaveFocus()
  })
})
