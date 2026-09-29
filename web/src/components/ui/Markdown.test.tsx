import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
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

  it('gives a code block a copy button and a scrollable pre', () => {
    const source = '```ts\nconst x = 1\n```'
    render(<Markdown text={source} />)
    expect(screen.getByRole('button', { name: 'Copy code' })).toBeInTheDocument()
    expect(screen.getByText('const x = 1').closest('pre')).not.toBeNull()
  })
})
