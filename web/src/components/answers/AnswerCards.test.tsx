// @find: tests for answer cards, email draft card, ask to send, table sort, csv export, download csv, chart data table, show the figures, checklist toggle, choices send, card fallback, hostile card strings, focus after card action, card replies
// @what: Component tests for every answer card's interactions, drawn through the shared Markdown renderer.
// @flow: Covers components/answers/* via components/ui/Markdown.tsx.
import axe from 'axe-core'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CardReply } from '../../lib/answerBlocks'
import { choiceRequest, emailSendRequest } from '../../lib/answerBlocks'
import { Markdown } from '../ui/Markdown'
import type { AnswerActions } from './answerActions'
import { axisMargin } from './ChartCard'

/*
 * Each card is drawn the way an agent's answer is: a fenced block inside Markdown. The checks are
 * the ones a person would make - edit the draft and ask for it to be sent, sort and export the
 * table, read the chart's figures, tick the checklist, pick a choice - plus what must never happen:
 * a send straight from the browser, a hostile string turning into markup or a link, a "yes" meant
 * for one card counting for another, or keyboard focus falling to the page.
 */

const fence = (language: string, body: unknown) =>
  ['```' + language, typeof body === 'string' ? body : JSON.stringify(body), '```'].join('\n')

const DRAFT = { to: 'sam@example.com', subject: 'Welcome to the team', body: 'Hello Sam,\n\nWelcome aboard.' }

let clipboard: string[]

beforeEach(() => {
  clipboard = []
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: vi.fn(async (text: string) => void clipboard.push(text)) },
  })
})

afterEach(() => {
  vi.restoreAllMocks()
})

/** Renders an answer holding `text`, with what its cards may do. */
function renderAnswer(text: string, actions?: AnswerActions) {
  return render(<Markdown text={text} actions={actions} />)
}

/** What was sent from the cards of an answer, by the card's place. */
const repliesOf = (entries: [number, CardReply][]) => new Map(entries)

describe('email-draft card', () => {
  it('shows the draft in editable fields, and asks the agent to send the edited version, naming the card', async () => {
    const send = vi.fn(async () => true)
    renderAnswer(`Here is a draft.\n\n${fence('email-draft', DRAFT)}`, { send, agentName: 'HR' })

    expect(screen.getByRole('group', { name: 'Email draft: Welcome to the team' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 5, name: 'Email draft: Welcome to the team' })).toBeInTheDocument()
    expect(screen.getByLabelText('To')).toHaveValue('sam@example.com')
    expect(screen.getByLabelText('Message')).toHaveValue('Hello Sam,\n\nWelcome aboard.')

    fireEvent.change(screen.getByLabelText('Subject'), { target: { value: 'Welcome, Sam' } })
    fireEvent.change(screen.getByLabelText(/Cc/), { target: { value: 'lee@example.com' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Ask HR to send it' }))
    })

    expect(send).toHaveBeenCalledTimes(1)
    expect(send).toHaveBeenCalledWith(
      0,
      emailSendRequest({ to: 'sam@example.com', cc: 'lee@example.com', subject: 'Welcome, Sam', body: 'Hello Sam,\n\nWelcome aboard.' }),
    )
    // One request per version of the draft: the button rests until the draft changes again, and
    // focus moves to the line that says what happened rather than to the page.
    const asked = screen.getByRole('button', { name: 'Asked HR' })
    expect(asked).toHaveAttribute('aria-disabled', 'true')
    fireEvent.click(asked)
    expect(send).toHaveBeenCalledTimes(1)
    const status = screen.getByText(/The email goes only after a person approves it\./)
    expect(status).toHaveAttribute('role', 'status')
    expect(document.activeElement).toBe(status)
    fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'Hello Sam, welcome.' } })
    expect(screen.getByRole('button', { name: 'Ask HR to send it' })).not.toHaveAttribute('aria-disabled')
  })

  it('keeps focus on the button while the request is on its way', async () => {
    let finish: (sent: boolean) => void = () => {}
    const send = vi.fn(() => new Promise<boolean>((resolve) => (finish = resolve)))
    renderAnswer(fence('email-draft', DRAFT), { send, agentName: 'HR' })
    const button = screen.getByRole('button', { name: 'Ask HR to send it' })
    button.focus()
    await act(async () => {
      fireEvent.click(button)
    })
    expect(button).toHaveAttribute('aria-busy', 'true')
    expect(button).not.toBeDisabled()
    expect(document.activeElement).toBe(button)
    fireEvent.click(button)
    expect(send).toHaveBeenCalledTimes(1)
    await act(async () => finish(false))
    expect(document.activeElement).toBe(button)
    expect(screen.getByText(/Your request could not be sent\./)).toBeInTheDocument()
  })

  it('mentions a request already sent from this very card after a reload, and only from this card', () => {
    const { unmount } = renderAnswer(fence('email-draft', DRAFT), {
      send: vi.fn(async () => true),
      agentName: 'HR',
      replies: repliesOf([[0, { content: { ...DRAFT, cc: '' }, mine: true, queued: false }]]),
    })
    expect(screen.getByText(/You already asked HR to send this draft\. Asking again sends another request\./)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ask HR to send it' })).toBeEnabled()
    unmount()

    // A request from a different card of the answer says nothing about this one.
    renderAnswer(fence('email-draft', DRAFT), {
      send: vi.fn(async () => true),
      agentName: 'HR',
      replies: repliesOf([[1, { content: {}, mine: true, queued: false }]]),
    })
    expect(screen.queryByText(/already asked/)).toBeNull()
  })

  it('starts from the version last asked to send after a reload, and Undo goes back to the agent\'s draft', () => {
    renderAnswer(fence('email-draft', DRAFT), {
      send: vi.fn(async () => true),
      agentName: 'HR',
      replies: repliesOf([
        [0, { content: { to: 'sam@example.com', cc: '', subject: 'Welcome, Sam', body: 'Hello Sam, see you on Monday.' }, mine: true, queued: false }],
      ]),
    })
    expect(screen.getByLabelText('Subject')).toHaveValue('Welcome, Sam')
    expect(screen.getByLabelText('Message')).toHaveValue('Hello Sam, see you on Monday.')
    expect(screen.getByText(/You already asked HR to send this draft\./)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Undo my changes' }))
    expect(screen.getByLabelText('Subject')).toHaveValue('Welcome to the team')
    expect(screen.getByLabelText('Message')).toHaveValue('Hello Sam,\n\nWelcome aboard.')
  })

  it('says so when someone else asked, and when the request waits its turn', () => {
    renderAnswer(fence('email-draft', DRAFT), {
      send: vi.fn(async () => true),
      agentName: 'HR',
      replies: repliesOf([[0, { content: {}, mine: false, queued: true }]]),
    })
    expect(screen.getByText(/Someone in this conversation already asked HR to send this draft; the request waits its turn\./)).toBeInTheDocument()
  })

  it('never sends without a recipient, a subject and a message, says what is missing, and moves focus to it', async () => {
    const send = vi.fn(async () => true)
    renderAnswer(fence('email-draft', DRAFT), { send, agentName: 'HR' })
    fireEvent.change(screen.getByLabelText('Subject'), { target: { value: ' ' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Ask HR to send it' }))
    })
    expect(send).not.toHaveBeenCalled()
    expect(screen.getByText('Add a subject.')).toBeInTheDocument()
    expect(document.activeElement).toBe(screen.getByLabelText('Subject'))
    expect(screen.getByText(/^The draft is not ready to send/)).toHaveTextContent('The draft is not ready to send: Add a subject.')
    expect(screen.getByText(/^The draft is not ready to send/)).toHaveAttribute('role', 'status')
  })

  it('says so when the request could not be sent, and leaves the button ready', async () => {
    const send = vi.fn(async () => false)
    renderAnswer(fence('email-draft', DRAFT), { send, agentName: 'HR' })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Ask HR to send it' }))
    })
    expect(screen.getByText(/Your request could not be sent\./)).toHaveAttribute('role', 'status')
    expect(screen.getByRole('button', { name: 'Ask HR to send it' })).not.toHaveAttribute('aria-disabled')
  })

  it('keeps every recipient in view and is set up for typing addresses, with the server limits as field limits', () => {
    renderAnswer(fence('email-draft', { ...DRAFT, to: ['sam.longname@example.com', 'lee@example.com'] }), { send: vi.fn(async () => true) })
    const to = screen.getByLabelText('To')
    expect(to.tagName).toBe('TEXTAREA')
    expect(to).toHaveValue('sam.longname@example.com, lee@example.com')
    expect(to).toHaveAttribute('inputmode', 'email')
    expect(to).toHaveAttribute('autocapitalize', 'off')
    expect(to).toHaveAttribute('spellcheck', 'false')
    expect(to).toHaveAttribute('maxlength', '6000')
    expect(screen.getByLabelText('Message')).toHaveAttribute('maxlength', '10000')
    // Enter does not start a new line inside an address.
    const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    to.dispatchEvent(enter)
    expect(enter.defaultPrevented).toBe(true)
  })

  it('shows the whole message: the box is as tall as the draft', () => {
    const body = Array.from({ length: 30 }, (_, index) => `Line ${index + 1}`).join('\n')
    renderAnswer(fence('email-draft', { ...DRAFT, body }), { send: vi.fn(async () => true) })
    expect(Number(screen.getByLabelText('Message').getAttribute('rows'))).toBeGreaterThanOrEqual(30)
  })

  it('copies the edited email, and offers to undo the changes', async () => {
    renderAnswer(fence('email-draft', DRAFT), { send: vi.fn(async () => true) })
    fireEvent.change(screen.getByLabelText('Subject'), { target: { value: 'Hi' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Copy email' }))
    })
    expect(clipboard).toEqual(['To: sam@example.com\nSubject: Hi\n\nHello Sam,\n\nWelcome aboard.'])
    fireEvent.click(screen.getByRole('button', { name: 'Undo my changes' }))
    expect(screen.getByLabelText('Subject')).toHaveValue('Welcome to the team')
  })

  it('has no send button outside Chat, and points to the conversation instead', () => {
    renderAnswer(fence('email-draft', DRAFT), { chatHref: '/chat?c=c1' })
    expect(screen.queryByRole('button', { name: /send it/ })).toBeNull()
    expect(screen.getByText(/To have this email sent, reply to the agent in its conversation/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the conversation' })).toHaveAttribute('href', '/chat?c=c1')
  })

  it('says to ask in Chat where the answer came from no conversation', () => {
    renderAnswer(fence('email-draft', DRAFT))
    expect(screen.getByText('To have this email sent, ask an agent in Chat.')).toBeInTheDocument()
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('tells a read-only role it can read and copy but not ask', () => {
    renderAnswer(fence('email-draft', DRAFT), { readOnly: true })
    expect(screen.queryByRole('button', { name: /send it/ })).toBeNull()
    expect(screen.getByText(/cannot ask agents to act on it/)).toBeInTheDocument()
  })

  it('shows hostile strings as plain text: no script, no image, no link', () => {
    const { container } = renderAnswer(
      fence('email-draft', { to: 'javascript:alert(1)', subject: '<script>alert(1)</script>', body: '<img src=x onerror=alert(1)> [x](javascript:alert(1))' }),
      { send: vi.fn(async () => true) },
    )
    expect(container.querySelector('script, img, a')).toBeNull()
    expect(screen.getByLabelText('Subject')).toHaveValue('<script>alert(1)</script>')
    expect(screen.getByLabelText('To')).toHaveValue('javascript:alert(1)')
  })
})

describe('table card', () => {
  const TABLE = {
    title: 'Plan comparison',
    columns: ['Plan', 'Price', 'Seats'],
    rows: [
      ['Basic', '$90/mo', 5],
      ['Enterprise', '$1,200/mo', 100],
      ['Pro', '$300/mo', 20],
      ['Team', '$150/mo', 10],
    ],
  }

  /** The first column of every body row, in the order shown. */
  const firstColumn = () =>
    within(screen.getByRole('group', { name: 'Plan comparison, scrollable table' }))
      .getAllByRole('row')
      .slice(1)
      .map((row) => row.querySelector('td')?.textContent)

  it('sorts a column as numbers on click (a unit after the price included), then the other way, then back', () => {
    renderAnswer(fence('table', TABLE))
    const price = screen.getByRole('button', { name: 'Price' })
    expect(price.closest('th')).toHaveAttribute('aria-sort', 'none')

    fireEvent.click(price)
    expect(firstColumn()).toEqual(['Basic', 'Team', 'Pro', 'Enterprise'])
    expect(price.closest('th')).toHaveAttribute('aria-sort', 'ascending')

    fireEvent.click(price)
    expect(firstColumn()).toEqual(['Enterprise', 'Pro', 'Team', 'Basic'])
    expect(price.closest('th')).toHaveAttribute('aria-sort', 'descending')

    fireEvent.click(price)
    expect(firstColumn()).toEqual(['Basic', 'Enterprise', 'Pro', 'Team'])
  })

  it('names its headings by their words alone (aria-sort says the state), and the table by a caption', () => {
    const { container } = renderAnswer(fence('table', TABLE))
    expect(screen.getAllByRole('columnheader').map((header) => header.textContent)).toEqual(['Plan', 'Price', 'Seats'])
    expect(container.querySelector('caption')?.textContent).toBe('Plan comparison')
    // Nothing screen-reader-only sits in the scroll box's headings to escape it at phone width.
    expect(container.querySelector('thead .visually-hidden')).toBeNull()
    expect(screen.getByRole('table', { name: 'Plan comparison' })).toBeInTheDocument()
  })

  it('filters rows by the words typed, and says how many are shown', () => {
    renderAnswer(fence('table', TABLE))
    fireEvent.change(screen.getByLabelText('Filter rows'), { target: { value: 'pro' } })
    expect(firstColumn()).toEqual(['Pro'])
    expect(screen.getByText(/Showing 1 of 4 rows\./)).toHaveAttribute('role', 'status')
    fireEvent.change(screen.getByLabelText('Filter rows'), { target: { value: 'nothing like this' } })
    expect(screen.getByText('No rows match this filter.')).toBeInTheDocument()
  })

  it('copies the rows shown as CSV, in the order shown', async () => {
    renderAnswer(fence('table', TABLE))
    fireEvent.click(screen.getByRole('button', { name: 'Seats' }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Copy as CSV' }))
    })
    expect(clipboard).toEqual(['Plan,Price,Seats\r\nBasic,$90/mo,5\r\nTeam,$150/mo,10\r\nPro,$300/mo,20\r\nEnterprise,"$1,200/mo",100'])
  })

  it('downloads a CSV file named after the title, with formula cells made safe', async () => {
    const created: Blob[] = []
    const createObjectURL = vi.fn((blob: Blob) => {
      created.push(blob)
      return 'blob:table'
    })
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() })
    const clicks: string[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicks.push(this.download)
    })
    renderAnswer(fence('table', { title: 'Plan comparison', columns: ['Name', 'Formula'], rows: [['Risky', '=1+2']] }))

    fireEvent.click(screen.getByRole('button', { name: 'Download CSV' }))

    expect(clicks).toEqual(['plan-comparison.csv'])
    expect(created[0]!.type).toBe('text/csv;charset=utf-8')
    const text = await new Promise<string>((resolve) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.readAsText(created[0]!)
    })
    expect(text.replace(/^\uFEFF/, '')).toBe("Name,Formula\r\nRisky,'=1+2")
  })

  it('shows a cell as text, never as markup or a link', () => {
    const { container } = renderAnswer(
      fence('table', { columns: ['Cell'], rows: [['<script>alert(1)</script>'], ['[x](javascript:alert(1))'], ['https://example.org']] }),
    )
    expect(container.querySelector('script, a')).toBeNull()
    expect(screen.getByText('<script>alert(1)</script>')).toBeInTheDocument()
  })

  it('draws a Markdown pipe table in a table fence as a proper table', () => {
    renderAnswer(fence('table', '| Plan | Price |\n|---|---|\n| Basic | 10 |'))
    expect(screen.getAllByRole('columnheader').map((header) => header.textContent)).toEqual(['Plan', 'Price'])
  })
})

describe('chart card', () => {
  const CHART = { type: 'bar', title: 'Monthly sales', unit: '$', labels: ['Jan', 'Feb', 'Mar'], series: [{ name: 'Sales', values: [120, 90, 150] }] }

  it('draws a named image with every figure in a table for screen readers', () => {
    const { container } = renderAnswer(fence('chart', CHART))
    expect(screen.getByRole('img', { name: /Monthly sales/ })).toBeInTheDocument()
    expect(container.querySelectorAll('svg rect')).toHaveLength(3)
    const table = container.querySelector('table.visually-hidden')!
    expect(within(table as HTMLElement).getByText('Monthly sales')).toBeInTheDocument()
    expect(within(table as HTMLElement).getAllByRole('row').map((row) => row.textContent)).toEqual([
      'LabelSales',
      'Jan$120',
      'Feb$90',
      'Mar$150',
    ])
  })

  it('shows the figures to anyone who opens them, for a phone with no hover and dropped labels', () => {
    const { container } = renderAnswer(fence('chart', CHART))
    const toggle = screen.getByRole('button', { name: 'Show the figures' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    expect(screen.getByRole('button', { name: 'Hide the figures' })).toHaveAttribute('aria-expanded', 'true')
    const table = container.querySelector('table.answer-chart-figures')!
    expect(table).not.toHaveClass('visually-hidden')
    expect(toggle.getAttribute('aria-controls')).toBe(table.id)
    expect(container.querySelectorAll('table')).toHaveLength(1)
  })

  it('makes room on the axis for large values and writes them short, so no label is clipped', () => {
    const { container } = renderAnswer(
      fence('chart', { ...CHART, labels: ['North', 'South'], series: [{ name: 'Revenue', values: [1_500_000, -1_200_000] }] }),
    )
    // The value axis: the labels right-aligned against it.
    const labels = [...container.querySelectorAll('text.answer-chart-axis[text-anchor="end"]')].map((node) => node.textContent)
    expect(labels).toEqual(['-$2M', '-$1M', '$0', '$1M', '$2M'])
    const longest = Math.max(...labels.map((label) => (label ?? '').length))
    const x = Number(container.querySelector('text.answer-chart-axis')!.getAttribute('x'))
    // The labels end 6px left of the axis; the margin holds the longest at about 7px a character.
    expect(x + 6).toBeGreaterThanOrEqual(longest * 7)
    expect(axisMargin(['-$1,200,000'], 480)).toBeGreaterThanOrEqual(11 * 7 + 12)
    expect(axisMargin(['150 customers'.repeat(4)], 300)).toBe(100)
  })

  it('says a word unit once under the chart instead of on every axis label', () => {
    const { container } = renderAnswer(fence('chart', { ...CHART, unit: 'customers', series: [{ name: 'New', values: [150, 90, 120] }] }))
    expect(screen.getByText('Figures in customers.')).toBeInTheDocument()
    const labels = [...container.querySelectorAll('text.answer-chart-axis[text-anchor="end"]')].map((node) => node.textContent ?? '')
    expect(labels.length).toBeGreaterThan(0)
    expect(labels.some((label) => label.includes('customers'))).toBe(false)
  })

  it('gives the second and third bar series a pattern as well as a colour', () => {
    const { container } = renderAnswer(
      fence('chart', {
        ...CHART,
        series: [
          { name: 'This week', values: [1, 2, 3] },
          { name: 'Last week', values: [2, 3, 4] },
          { name: 'Target', values: [3, 3, 3] },
        ],
      }),
    )
    const fills = [...container.querySelectorAll('svg[role="img"] rect[fill^="url(#"]')].map((rect) => rect.getAttribute('fill'))
    expect(fills.filter((fill) => fill?.endsWith('-stripes)'))).toHaveLength(3)
    expect(fills.filter((fill) => fill?.endsWith('-dots)'))).toHaveLength(3)
    const ids = [...container.querySelectorAll('pattern')].map((pattern) => pattern.id)
    expect(fills.every((fill) => ids.includes(fill!.slice(5, -1)))).toBe(true)
    expect(container.querySelector('.answer-chart-legend')?.textContent).toBe('This weekLast weekTarget')
  })

  it('draws a line per series with a legend, the second dashed so lines differ without colour', () => {
    const { container } = renderAnswer(
      fence('chart', { ...CHART, type: 'line', series: [{ name: '2025', values: [1, 2, 3] }, { name: '2026', values: [2, 3, 4] }] }),
    )
    const lines = container.querySelectorAll('polyline')
    expect(lines).toHaveLength(2)
    expect(lines[0]).not.toHaveAttribute('stroke-dasharray')
    expect(lines[1]).toHaveAttribute('stroke-dasharray', '6 4')
    expect(container.querySelector('.answer-chart-legend')?.textContent).toBe('20252026')
  })

  it('draws a pie with each share in the legend and the table', () => {
    const { container } = renderAnswer(
      fence('chart', { type: 'pie', title: 'Tickets by channel', labels: ['Email', 'Phone', 'Chat'], series: [{ name: 'Tickets', values: [50, 30, 20] }] }),
    )
    expect(container.querySelectorAll('path.answer-chart-slice')).toHaveLength(3)
    expect(container.querySelector('.answer-chart-legend')?.textContent).toContain('Email: 50 (50%)')
    const table = container.querySelector('table.visually-hidden') as HTMLElement
    expect(within(table).getByRole('columnheader', { name: 'Share' })).toBeInTheDocument()
    expect(within(table).getByText('30%')).toBeInTheDocument()
  })
})

describe('checklist card', () => {
  const LIST = { title: 'Onboarding', items: [{ text: 'Laptop ready', done: true }, 'Building pass', 'Welcome lunch'] }

  it('ticks an item in this view only, and says the agent is not told', () => {
    const { container } = renderAnswer(fence('checklist', LIST))
    expect(screen.getByText('1 of 3 done')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Building pass' }))
    expect(screen.getByRole('checkbox', { name: 'Building pass' })).toBeChecked()
    expect(screen.getByText('2 of 3 done')).toBeInTheDocument()
    expect(container.textContent).toContain('Ticking an item changes only this view. The agent is not told.')
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('tells the agent what is done only when the person asks, with the items as data, and keeps focus on the card', async () => {
    const send = vi.fn(async () => true)
    renderAnswer(fence('checklist', LIST), { send, agentName: 'HR' })
    expect(screen.queryByRole('button', { name: 'Tell HR what is done' })).toBeNull()

    fireEvent.click(screen.getByRole('checkbox', { name: 'Building pass' }))
    expect(send).not.toHaveBeenCalled()
    const button = screen.getByRole('button', { name: 'Tell HR what is done' })
    button.focus()
    await act(async () => {
      fireEvent.click(button)
    })
    expect(send).toHaveBeenCalledWith(0, {
      kind: 'checklist',
      text: 'I have updated the checklist "Onboarding": 2 of 3 done.',
      content: {
        title: 'Onboarding',
        items: [
          { text: 'Laptop ready', done: true },
          { text: 'Building pass', done: true },
          { text: 'Welcome lunch', done: false },
        ],
      },
    })
    const status = screen.getByText('Sent to HR as your next message.')
    expect(status).toHaveAttribute('role', 'status')
    expect(screen.queryByRole('button', { name: 'Tell HR what is done' })).toBeNull()
    // The button is gone; focus is on the line that says what happened, not on the page.
    expect(document.activeElement).toBe(status)
  })
})

describe('choices card', () => {
  const CHOICE = { question: 'Which format should the weekly report use?', options: ['PDF', 'Slides', 'Email'] }

  it('sends the question with the chosen option, marks it, and rests the others', async () => {
    const send = vi.fn<(index: number, request: unknown) => Promise<boolean>>(async () => true)
    renderAnswer(fence('choices', CHOICE), { send })
    const group = screen.getByRole('group', { name: CHOICE.question })

    await act(async () => {
      fireEvent.click(within(group).getByRole('button', { name: 'Slides' }))
    })

    expect(send).toHaveBeenCalledWith(0, choiceRequest(CHOICE.question, 'Slides'))
    expect(send.mock.calls[0]![1]).toMatchObject({ text: 'My choice for "Which format should the weekly report use?": Slides' })
    expect(within(group).getByRole('button', { name: 'Slides' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(group).getByRole('button', { name: 'PDF' })).toBeDisabled()
    expect(screen.getByText('You chose "Slides".')).toHaveAttribute('role', 'status')
    fireEvent.click(within(group).getByRole('button', { name: 'Slides' }))
    expect(send).toHaveBeenCalledTimes(1)
  })

  it('keeps focus on the option while the choice is on its way', async () => {
    let finish: (sent: boolean) => void = () => {}
    const send = vi.fn(() => new Promise<boolean>((resolve) => (finish = resolve)))
    renderAnswer(fence('choices', CHOICE), { send })
    const slides = screen.getByRole('button', { name: 'Slides' })
    slides.focus()
    await act(async () => {
      fireEvent.click(slides)
    })
    for (const button of screen.getAllByRole('button')) {
      expect(button).not.toBeDisabled()
      expect(button).toHaveAttribute('aria-disabled', 'true')
    }
    expect(document.activeElement).toBe(slides)
    await act(async () => finish(true))
    expect(document.activeElement).toBe(slides)
    expect(slides).toHaveAttribute('aria-pressed', 'true')
  })

  it('shows a choice already sent from this card as made, saying who made it', () => {
    renderAnswer(fence('choices', CHOICE), {
      send: vi.fn(async () => true),
      replies: repliesOf([[0, { content: { question: CHOICE.question, option: 'Email' }, mine: false, queued: false }]]),
    })
    expect(screen.getByRole('button', { name: 'Email' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'PDF' })).toBeDisabled()
    expect(screen.getByText(/chose "Email"/)).toHaveTextContent('Someone in this conversation chose "Email".')
  })

  it('keeps two Yes/No cards in one answer apart: a "Yes" on one never answers or marks the other', async () => {
    const send = vi.fn(async () => true)
    const answer = [
      fence('choices', { question: 'Delete the 40 old customer files?', options: ['Yes', 'No'] }),
      fence('choices', { question: 'Would you like a short summary as well?', options: ['Yes', 'No'] }),
    ].join('\n\n')
    const { unmount } = renderAnswer(answer, { send })
    const [first, second] = screen.getAllByRole('group', { name: /\?$/ })
    await act(async () => {
      fireEvent.click(within(second!).getByRole('button', { name: 'Yes' }))
    })
    expect(send).toHaveBeenCalledWith(1, choiceRequest('Would you like a short summary as well?', 'Yes'))
    expect(within(first!).getByRole('button', { name: 'No' })).toBeEnabled()
    expect(within(first!).getByRole('button', { name: 'Yes' })).toHaveAttribute('aria-pressed', 'false')
    unmount()

    // After a reload, the reply names its card: only the second shows as answered.
    renderAnswer(answer, {
      send,
      replies: repliesOf([[1, { content: { question: 'Would you like a short summary as well?', option: 'Yes' }, mine: true, queued: false }]]),
    })
    const [firstAgain, secondAgain] = screen.getAllByRole('group', { name: /\?$/ })
    expect(within(firstAgain!).getByRole('button', { name: 'No' })).toBeEnabled()
    expect(within(firstAgain!).queryByText(/You chose/)).toBeNull()
    expect(within(secondAgain!.closest('.answer-card') as HTMLElement).getByText(/You chose/)).toHaveTextContent('You chose "Yes".')
  })

  it('lists the options with no buttons where nothing can be sent', () => {
    renderAnswer(fence('choices', CHOICE))
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.getByText('Slides')).toBeInTheDocument()
    expect(screen.getByText('To choose, ask an agent in Chat.')).toBeInTheDocument()
  })
})

describe('one request however fast the clicks come', () => {
  // Two clicks in one task both reach the handler before React draws the "sending" state. Seen live:
  // one tap on a choice sent the same answer twice and started two runs.
  it('sends a choice once for two clicks in one task', async () => {
    const send = vi.fn(async () => true)
    renderAnswer(fence('choices', { question: 'Which format?', options: ['PDF', 'Slides'] }), { send })
    const slides = screen.getByRole('button', { name: 'Slides' })
    await act(async () => {
      slides.click()
      slides.click()
    })
    expect(send).toHaveBeenCalledTimes(1)
    expect(slides).toHaveAttribute('aria-pressed', 'true')
  })

  it('asks once to send a draft for two clicks in one task', async () => {
    const send = vi.fn(async () => true)
    renderAnswer(fence('email-draft', DRAFT), { send, agentName: 'HR' })
    const ask = screen.getByRole('button', { name: 'Ask HR to send it' })
    await act(async () => {
      ask.click()
      ask.click()
    })
    expect(send).toHaveBeenCalledTimes(1)
  })

  it('tells the agent once for two clicks in one task', async () => {
    const send = vi.fn(async () => true)
    renderAnswer(fence('checklist', { title: 'Onboarding', items: ['Laptop ready', 'Building pass'] }), { send, agentName: 'HR' })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Laptop ready' }))
    const tell = screen.getByRole('button', { name: 'Tell HR what is done' })
    await act(async () => {
      tell.click()
      tell.click()
    })
    expect(send).toHaveBeenCalledTimes(1)
  })

  it('lets a choice be tried again after a send that failed', async () => {
    const send = vi.fn<() => Promise<boolean>>().mockResolvedValueOnce(false).mockResolvedValueOnce(true)
    renderAnswer(fence('choices', { question: 'Which format?', options: ['PDF', 'Slides'] }), { send })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'PDF' }))
    })
    expect(screen.getByText('Your choice could not be sent. Try again.')).toBeInTheDocument()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'PDF' }))
    })
    expect(send).toHaveBeenCalledTimes(2)
    expect(screen.getByRole('button', { name: 'PDF' })).toHaveAttribute('aria-pressed', 'true')
  })
})

describe('a card that cannot be shown', () => {
  it('falls back to the code block with a short note in plain words, for broken text and for a failed check', () => {
    const { container } = renderAnswer(`${fence('chart', '{"type": "bar",')}\n\n${fence('choices', { question: 'Q', options: ['Only one'] })}`)
    const notes = [...container.querySelectorAll('.md-code-note')].map((note) => note.textContent)
    expect(notes).toEqual([
      "This card could not be shown, so it appears as text. The card's text is not in the format a card needs.",
      'This card could not be shown, so it appears as text. A choice needs two to four options.',
    ])
    expect(container.querySelectorAll('pre code')[0]?.textContent).toBe('{"type": "bar",')
    expect(container.querySelector('.answer-card')).toBeNull()
  })

  it('leaves an ordinary code block alone, with no note', () => {
    const { container } = renderAnswer(fence('python', 'print("hello")'))
    expect(container.querySelector('.md-code-note')).toBeNull()
    expect(container.querySelector('pre code')?.textContent).toBe('print("hello")')
  })
})

describe('answer cards and accessibility', () => {
  const ALL = [
    fence('email-draft', DRAFT),
    fence('table', { title: 'Plans', columns: ['Plan', 'Price'], rows: [['Basic', 10]] }),
    fence('chart', { type: 'bar', title: 'Sales', labels: ['Jan'], series: [{ name: 'Sales', values: [1] }] }),
    fence('checklist', { items: ['One'] }),
    fence('choices', { question: 'Which?', options: ['A', 'B'] }),
  ].join('\n\n')

  it('passes axe checks for every card together', async () => {
    const { container } = renderAnswer(ALL, { send: vi.fn(async () => true), agentName: 'HR' })
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((violation) => violation.id)).toEqual([])
  })

  it('adds no landmarks, and a heading for every card', () => {
    renderAnswer(ALL, { send: vi.fn(async () => true), agentName: 'HR' })
    expect(screen.queryAllByRole('region')).toHaveLength(0)
    expect(screen.getAllByRole('heading', { level: 5 }).map((heading) => heading.textContent)).toEqual([
      'Email draft: Welcome to the team',
      'Table: Plans',
      'Bar chart: Sales',
      'Checklist',
      'Choose one',
    ])
  })
})
