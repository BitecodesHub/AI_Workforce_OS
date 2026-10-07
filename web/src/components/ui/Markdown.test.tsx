import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Markdown } from './Markdown'

/*
 * Rendering-level checks that sit alongside lib/markdown.test.ts's parser checks: the source that
 * matters here is the one that could turn into a DOM attribute or an injected element, not merely
 * a mis-parsed block.
 */

describe('Markdown', () => {
  it('never turns an unsafe link scheme into an anchor', () => {
    const { container } = render(<Markdown text="[x](javascript:alert(1))" />)
    expect(container.querySelector('a')).toBeNull()
    expect(container.textContent).toContain('[x](javascript:alert(1))')
  })

  it('renders a safe link with a hardened target', () => {
    render(<Markdown text="[x](https://example.org)" />)
    const link = screen.getByRole('link', { name: 'x' })
    expect(link).toHaveAttribute('href', 'https://example.org')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('renders a table inside a focusable, labelled region', () => {
    const table = '| A | B |\n| --- | --- |\n| 1 | 2 |'
    render(<Markdown text={table} />)
    const region = screen.getByRole('region', { name: 'Table' })
    expect(region).toHaveAttribute('tabIndex', '0')
    expect(region.querySelector('table')).not.toBeNull()
    expect(screen.getByRole('cell', { name: '1' })).toBeInTheDocument()
  })

  it('never creates an element from raw HTML in the source', () => {
    const { container } = render(<Markdown text="<img src=x onerror=alert(1)>" />)
    expect(container.querySelector('img')).toBeNull()
    expect(container.textContent).toContain('<img src=x onerror=alert(1)>')
  })

  it('steps headings down so a message never outranks the page around it', () => {
    render(<Markdown text={'# Title\n\n### Sub'} />)
    expect(screen.getByRole('heading', { level: 4, name: 'Title' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 5, name: 'Sub' })).toBeInTheDocument()
  })

  it('numbers a list from its own first number, and leaves a list from 1 plain', () => {
    const { container } = render(<Markdown text={'3. Third\n\n4. Fourth'} />)
    const list = container.querySelector('ol')
    expect(list).toHaveAttribute('start', '3')
    expect(list?.querySelectorAll('li')).toHaveLength(2)

    const plain = render(<Markdown text={'1. First\n2. Second'} />)
    expect(plain.container.querySelector('ol')).not.toHaveAttribute('start')
  })

  it('gives a code block a copy button and a scrollable pre', () => {
    const source = '```ts\nconst x = 1\n```'
    render(<Markdown text={source} />)
    expect(screen.getByRole('button', { name: 'Copy code' })).toBeInTheDocument()
    expect(screen.getByText('const x = 1').closest('pre')).not.toBeNull()
  })

  describe('citations', () => {
    const answer = 'Leave accrues monthly [1], and carers may take ten days [2]. Nothing covers [3].'

    it('turns [n] into a button that opens source n, only for a source that exists', () => {
      const onOpen = vi.fn()
      render(<Markdown text={answer} citations={{ count: 2, onOpen }} />)

      fireEvent.click(screen.getByRole('button', { name: 'Open source 2' }))
      expect(onOpen).toHaveBeenCalledWith(2)
      fireEvent.click(screen.getByRole('button', { name: 'Open source 1' }))
      expect(onOpen).toHaveBeenLastCalledWith(1)
      expect(screen.queryByRole('button', { name: 'Open source 3' })).toBeNull()
      expect(screen.getByText(/Nothing covers \[3\]\./)).toBeInTheDocument()
    })

    it('keeps the sentence readable: the number stays in the text of the button', () => {
      const { container } = render(<Markdown text={answer} citations={{ count: 2, onOpen: () => {} }} />)
      expect(container.textContent).toBe(answer)
    })

    it('leaves every [n] as text when there is nothing to open', () => {
      const { container } = render(<Markdown text={answer} />)
      expect(container.querySelectorAll('button')).toHaveLength(0)
      expect(container.textContent).toBe(answer)

      const none = render(<Markdown text={answer} citations={{ count: 0, onOpen: () => {} }} />)
      expect(none.container.querySelectorAll('button')).toHaveLength(0)
    })

    it('never makes a button of a number in a code span or a code block, or in a link', () => {
      const source = 'Use `items[1]` here and [see 2](https://example.org).\n\n```\nrow[1]\n```'
      const { container } = render(<Markdown text={source} citations={{ count: 2, onOpen: () => {} }} />)
      // The only button is the code block's own copy button.
      expect(screen.queryByRole('button', { name: 'Open source 1' })).toBeNull()
      expect(container.querySelectorAll('a button, button a')).toHaveLength(0)
      expect(screen.getByRole('link', { name: 'see 2' })).toHaveAttribute('href', 'https://example.org')
    })

    it('works inside a list item and a table cell', () => {
      const source = '- Carers [1]\n\n| Topic | Source |\n| --- | --- |\n| Leave | [2] |'
      render(<Markdown text={source} citations={{ count: 2, onOpen: () => {} }} />)
      expect(screen.getByRole('button', { name: 'Open source 1' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Open source 2' })).toBeInTheDocument()
    })

    it('can be reached and pressed from the keyboard like any button', async () => {
      const onOpen = vi.fn()
      render(<Markdown text="Five days [1]" citations={{ count: 1, onOpen }} />)
      const button = screen.getByRole('button', { name: 'Open source 1' })
      expect(button).toHaveAttribute('type', 'button')
      button.focus()
      expect(button).toHaveFocus()
      fireEvent.click(button)
      expect(onOpen).toHaveBeenCalledTimes(1)
    })
  })
})
