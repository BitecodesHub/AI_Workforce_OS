import { fireEvent, render, screen, within } from '@testing-library/react'
import React from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { CatalogueModel } from '../../lib/modelCatalogue'
import { ModelCombobox, OPTION_LIMIT } from './ModelCombobox'

/*
 * The searchable model picker on its own: typing filters, the free group comes first, the keys
 * move and choose, and the chosen model's name is what the field shows.
 */

const option = (id: string, displayName: string, extra: Partial<CatalogueModel> = {}): CatalogueModel => ({
  id,
  displayName,
  free: false,
  toolCalling: true,
  vision: false,
  contextLength: 128_000,
  pricePerMTokIn: 1,
  pricePerMTokOut: 2,
  ...extra,
})

const OPTIONS = [
  option('anthropic/claude-sonnet-4', 'Claude Sonnet 4', { vision: true, pricePerMTokIn: 3, pricePerMTokOut: 15 }),
  option('meta/llama-3.3-70b-instruct', 'Llama 3.3 70B'),
  option('google/gemma-4-31b-it:free', 'Gemma 4 31B', { free: true, pricePerMTokIn: 0, pricePerMTokOut: 0 }),
]

function Harness({ options = OPTIONS, initial = 'meta/llama-3.3-70b-instruct', onChange = vi.fn() }: {
  options?: CatalogueModel[]
  initial?: string
  onChange?: (id: string) => void
}) {
  const [value, setValue] = React.useState(initial)
  return (
    <ModelCombobox
      label="Candidate 1 model"
      value={value}
      options={options}
      providerName="OpenRouter"
      onChange={(id) => {
        setValue(id)
        onChange(id)
      }}
    />
  )
}

const field = () => screen.getByRole('combobox', { name: 'Candidate 1 model' })

describe('the model picker', () => {
  it('shows the chosen model by name, closed', () => {
    render(<Harness />)

    expect(field()).toHaveValue('Llama 3.3 70B')
    expect(field()).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('opens a list grouped Free first, then Paid, with the id, context and tags', () => {
    render(<Harness />)

    fireEvent.click(field())

    const list = screen.getByRole('listbox')
    expect(field()).toHaveAttribute('aria-expanded', 'true')
    const groups = within(list).getAllByRole('group')
    expect(groups.map((group) => group.getAttribute('aria-labelledby') && document.getElementById(group.getAttribute('aria-labelledby')!)?.textContent)).toEqual([
      'Free',
      'Paid',
    ])
    expect(within(groups[0]!).getAllByRole('option').map((node) => node.getAttribute('data-model-id'))).toEqual([
      'google/gemma-4-31b-it:free',
    ])
    expect(within(groups[1]!).getAllByRole('option').map((node) => node.getAttribute('data-model-id'))).toEqual([
      'anthropic/claude-sonnet-4',
      'meta/llama-3.3-70b-instruct',
    ])
    const sonnet = within(list).getByRole('option', { name: /Claude Sonnet 4/ })
    expect(sonnet).toHaveTextContent('anthropic/claude-sonnet-4')
    expect(sonnet).toHaveTextContent('128K')
    expect(sonnet).toHaveTextContent('Vision')
    expect(sonnet).toHaveTextContent('$3 / $15 per 1M')
    expect(within(list).getByRole('option', { name: /Llama 3.3 70B/ })).toHaveAttribute('aria-selected', 'true')
  })

  it('filters as you type, by name or id', () => {
    render(<Harness />)

    fireEvent.change(field(), { target: { value: 'gemma' } })
    expect(screen.getAllByRole('option').map((node) => node.getAttribute('data-model-id'))).toEqual([
      'google/gemma-4-31b-it:free',
    ])

    fireEvent.change(field(), { target: { value: 'anthropic/' } })
    expect(screen.getAllByRole('option').map((node) => node.getAttribute('data-model-id'))).toEqual([
      'anthropic/claude-sonnet-4',
    ])

    fireEvent.change(field(), { target: { value: 'nothing like this' } })
    expect(screen.queryAllByRole('option')).toHaveLength(0)
    expect(screen.getByRole('status')).toHaveTextContent('No model matches that.')
  })

  it('moves with the arrow keys, chooses with Enter and shows the name', () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)

    fireEvent.keyDown(field(), { key: 'ArrowDown' })
    // Opens on the chosen model.
    const active = () => document.getElementById(field().getAttribute('aria-activedescendant') ?? '')
    expect(active()).toHaveAttribute('data-model-id', 'meta/llama-3.3-70b-instruct')
    fireEvent.keyDown(field(), { key: 'ArrowUp' })
    expect(active()).toHaveAttribute('data-model-id', 'anthropic/claude-sonnet-4')
    fireEvent.keyDown(field(), { key: 'Home' })
    expect(active()).toHaveAttribute('data-model-id', 'google/gemma-4-31b-it:free')
    fireEvent.keyDown(field(), { key: 'End' })
    expect(active()).toHaveAttribute('data-model-id', 'meta/llama-3.3-70b-instruct')
    fireEvent.keyDown(field(), { key: 'ArrowUp' })
    fireEvent.keyDown(field(), { key: 'Enter' })

    expect(onChange).toHaveBeenCalledWith('anthropic/claude-sonnet-4')
    expect(field()).toHaveValue('Claude Sonnet 4')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('chooses the first match of a search with Enter', () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)

    fireEvent.change(field(), { target: { value: 'sonnet' } })
    fireEvent.keyDown(field(), { key: 'Enter' })

    expect(onChange).toHaveBeenCalledWith('anthropic/claude-sonnet-4')
  })

  it('closes with Escape and puts the chosen name back', () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)

    fireEvent.change(field(), { target: { value: 'gem' } })
    fireEvent.keyDown(field(), { key: 'Escape' })

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(field()).toHaveValue('Llama 3.3 70B')
    expect(onChange).not.toHaveBeenCalled()
  })

  it('chooses with a click', () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)

    fireEvent.click(field())
    fireEvent.click(screen.getByRole('option', { name: /Gemma 4 31B/ }))

    expect(onChange).toHaveBeenCalledWith('google/gemma-4-31b-it:free')
    expect(field()).toHaveValue('Gemma 4 31B')
  })

  it('draws only the first matches of a long list and says how to see the rest', () => {
    const many = Array.from({ length: 320 }, (_, index) => option(`vendor/model-${index}`, `Model ${index}`))
    render(<Harness options={many} initial="vendor/model-0" />)

    fireEvent.click(field())

    expect(screen.getAllByRole('option')).toHaveLength(OPTION_LIMIT)
    expect(screen.getByText(`Showing ${OPTION_LIMIT} of 320. Type to narrow the list.`)).toBeInTheDocument()
    fireEvent.change(field(), { target: { value: 'model 319' } })
    expect(screen.getAllByRole('option').map((node) => node.getAttribute('data-model-id'))).toEqual(['vendor/model-319'])
  })
})
