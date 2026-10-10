// @find: tests for PayloadPreview, approval preview, what will be sent, email preview, message preview, payload, recipients, cc, bcc, action class, exactly what will be sent, approval details
// @what: Automated tests for PayloadPreview.
// @flow: Run with the web test runner; covers PayloadPreview.
import axe from 'axe-core'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { PayloadPreview } from './PayloadPreview'

/*
 * What an approver reads before deciding. The block promises the exact request, so the rule that
 * matters most is that no field is ever left out: the one that goes unseen is the one that was
 * worth seeing. A request that could not be read as fields is shown as the text it is.
 */

const EMAIL = JSON.stringify({
  to: 'jane.doe@customer.example',
  subject: 'Your welcome pack',
  body: 'Hello Jane,\n\nYour pack is on its way.\nThanks',
})

function labels(container: HTMLElement): string[] {
  return [...container.querySelectorAll('dt')].map((term) => term.textContent ?? '')
}

function valueOf(container: HTMLElement, label: string): HTMLElement {
  const term = [...container.querySelectorAll('dt')].find((candidate) => candidate.textContent === label)
  const value = term?.nextElementSibling
  if (!(value instanceof HTMLElement)) throw new Error(`No row labelled ${label}`)
  return value
}

describe('PayloadPreview', () => {
  it('shows an email as To, Subject and Body, with the body keeping its line breaks', () => {
    const { container } = render(<PayloadPreview payload={EMAIL} actionClass="OUTBOUND" />)

    expect(labels(container)).toEqual(['To', 'Subject', 'Body'])
    expect(valueOf(container, 'To')).toHaveTextContent('jane.doe@customer.example')
    const body = valueOf(container, 'Body')
    expect(body.textContent).toBe('Hello Jane,\n\nYour pack is on its way.\nThanks')
    expect(body.style.whiteSpace).toBe('pre-wrap')
  })

  it('reads known fields first, in the order a person reads them, whatever order the request wrote them', () => {
    const payload = JSON.stringify({ body: 'b', currency: 'AUD', bcc: 'x@y.example', amount: 25, subject: 's', cc: 'c@d.example', to: 't@u.example', channel: '#team', text: 'hi' })

    const { container } = render(<PayloadPreview payload={payload} actionClass="OUTBOUND" />)

    expect(labels(container)).toEqual(['To', 'Cc', 'Bcc', 'Subject', 'Body', 'Message', 'Channel', 'Amount', 'Currency'])
    expect(valueOf(container, 'Amount')).toHaveTextContent('25')
  })

  it('hides no field: what it does not know goes under Other details, every key', () => {
    const payload = JSON.stringify({
      to: 'jane@customer.example',
      bcc: 'audit@elsewhere.example',
      attachments: [{ name: 'contract.pdf', url: 'https://files.example/c.pdf' }],
      reply_to: 'boss@customer.example',
      priority: 1,
      draft: false,
      note: null,
      cc: '',
    })

    const { container } = render(<PayloadPreview payload={payload} actionClass="OUTBOUND" />)

    // Every top-level key has a row of its own: known ones first, then the rest under their heading.
    expect(labels(container)).toEqual(['To', 'Cc', 'Bcc', 'Attachments', 'Reply to', 'Priority', 'Draft', 'Note'])
    expect(screen.getByText('Other details')).toBeInTheDocument()
    expect(valueOf(container, 'Bcc')).toHaveTextContent('audit@elsewhere.example')
    expect(valueOf(container, 'Reply to')).toHaveTextContent('boss@customer.example')
    // Nested values are compact JSON; other values are written as they are.
    expect(valueOf(container, 'Attachments').textContent).toBe('[{"name":"contract.pdf","url":"https://files.example/c.pdf"}]')
    expect(valueOf(container, 'Priority')).toHaveTextContent('1')
    expect(valueOf(container, 'Draft')).toHaveTextContent('false')
    expect(valueOf(container, 'Note')).toHaveTextContent('null')
    // An empty value is shown as empty, not left out.
    expect(valueOf(container, 'Cc')).toHaveTextContent('(empty)')
  })

  it('shows a list of recipients as compact JSON, so none is dropped', () => {
    const payload = JSON.stringify({ to: ['a@x.example', 'b@y.example'], subject: 'Hi' })

    const { container } = render(<PayloadPreview payload={payload} actionClass="OUTBOUND" />)

    expect(valueOf(container, 'To').textContent).toBe('["a@x.example","b@y.example"]')
  })

  it('shows text as text, never as markup', () => {
    const payload = JSON.stringify({ to: 'a@x.example', body: '<b>Bold</b><script>window.hacked = true</script><img src=x onerror=alert(1)>' })

    const { container } = render(<PayloadPreview payload={payload} actionClass="OUTBOUND" />)

    expect(valueOf(container, 'Body').textContent).toBe(
      '<b>Bold</b><script>window.hacked = true</script><img src=x onerror=alert(1)>',
    )
    expect(container.querySelector('b, script, img')).toBeNull()
  })

  it('keeps the exact request behind Show the raw request', () => {
    const { container } = render(<PayloadPreview payload={EMAIL} actionClass="OUTBOUND" />)

    const toggle = screen.getByRole('button', { name: 'Show the raw request' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')

    const raw = container.querySelector('pre')
    expect(raw).not.toBeNull()
    expect(JSON.parse(raw!.textContent ?? '')).toEqual(JSON.parse(EMAIL))
  })

  it('captions the block by what the request does', () => {
    const { rerender } = render(<PayloadPreview payload={EMAIL} actionClass="OUTBOUND" />)
    expect(screen.getByText('Exactly what will be sent')).toBeInTheDocument()

    rerender(<PayloadPreview payload={EMAIL} actionClass="DESTRUCTIVE" />)
    expect(screen.getByText('Exactly what will be removed')).toBeInTheDocument()

    rerender(<PayloadPreview payload={EMAIL} actionClass="WRITE" />)
    expect(screen.getByText('Exactly what will change')).toBeInTheDocument()
  })

  it('shows a request that is not JSON as the text it is, with nothing to toggle', () => {
    const { container } = render(<PayloadPreview payload={'to=jane; send now'} actionClass="OUTBOUND" />)

    expect(container.querySelector('pre')).toHaveTextContent('to=jane; send now')
    expect(container.querySelector('dt')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Show the raw request' })).toBeNull()
  })

  it('shows JSON that is not an object as text too', () => {
    const { container } = render(<PayloadPreview payload={'["a","b"]'} actionClass="OUTBOUND" />)

    expect(container.querySelector('dt')).toBeNull()
    expect(container.querySelector('pre')?.textContent).toContain('"a"')
  })

  it('says so when an action carries no details, rather than showing an empty box', () => {
    render(<PayloadPreview payload={'{}'} actionClass="OUTBOUND" />)

    expect(screen.getByText('This action carries no further details.')).toBeInTheDocument()
  })

  it('reads a sandbox Slack post as a message and its channel', () => {
    const { container } = render(
      <PayloadPreview payload={JSON.stringify({ channel: '#standup', text: 'Done: invoices\nNext: payroll' })} actionClass="OUTBOUND" />,
    )

    // The order the fields are listed in: what it says before where it goes.
    expect(labels(container)).toEqual(['Message', 'Channel'])
    expect(within(valueOf(container, 'Message')).getByText(/Done: invoices/)).toBeInTheDocument()
  })

  it('has no axe violations', async () => {
    const { container } = render(<PayloadPreview payload={EMAIL} actionClass="OUTBOUND" />)

    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations).toEqual([])
  })
})
