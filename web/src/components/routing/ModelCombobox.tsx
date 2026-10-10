// @find: model picker, model combobox, searchable model list, choose model, provider models, Bedrock models, listbox
// @what: Searchable model picker that filters a provider's models as you type.
// @flow: Used by CandidateModelField.
import React from 'react'
import { createPortal } from 'react-dom'
import { NOT_AVAILABLE, capGroups, filterModels, formatContext, groupModels, optionSummary, shortPrice } from '../../lib/modelCatalogue'
import type { CatalogueModel } from '../../lib/modelCatalogue'

/*
 * A searchable model picker: a text field that filters a provider's models as you type, with the
 * matches in a list grouped "Free" first, then "Paid".
 *
 * It follows the ARIA combobox pattern (a combobox input that controls a listbox): the arrow keys
 * move through the matches, Enter chooses one, Escape closes the list and puts the chosen model's
 * name back. The list is drawn in a portal fixed beside the field, so no card or scrolling column
 * can clip it, and only the first OPTION_LIMIT matches are drawn, so a list of several hundred
 * opens and filters at once.
 */

/** The most options drawn at once; typing narrows the rest. */
export const OPTION_LIMIT = 150

export type ModelComboboxProps = {
  label: string
  /** The chosen model's id. */
  value: string
  /** Shown for a value that is not among the options. */
  fallbackLabel?: string
  options: readonly CatalogueModel[]
  onChange: (modelId: string) => void
  /** True while the provider's list is being fetched and nothing is known yet. */
  loading?: boolean
  disabled?: boolean
  /** Names the provider in the empty and loading wording. */
  providerName: string
  /** Described by, for the line under the field that says where the list came from. */
  describedBy?: string | undefined
}

export type Placement = { top?: number; bottom?: number; left: number; width: number; maxHeight: number }

const GAP = 4
const EDGE = 8

// @find: place, place, model picker, model combobox, searchable model list, choose model
export function place(anchor: HTMLElement): Placement {
  const rect = anchor.getBoundingClientRect()
  const viewportWidth = window.innerWidth || document.documentElement.clientWidth
  const viewportHeight = window.innerHeight || document.documentElement.clientHeight
  const width = Math.min(Math.max(rect.width, 460), viewportWidth - EDGE * 2)
  const left = Math.min(Math.max(EDGE, rect.left), viewportWidth - width - EDGE)
  const below = viewportHeight - rect.bottom - GAP - EDGE
  const above = rect.top - GAP - EDGE
  if (below < 240 && above > below) {
    return { bottom: viewportHeight - rect.top + GAP, left, width, maxHeight: Math.min(420, above) }
  }
  return { top: rect.bottom + GAP, left, width, maxHeight: Math.max(160, Math.min(420, below)) }
}

// @find: ModelCombobox, model combobox, model picker, model combobox, searchable model list, choose model
export function ModelCombobox({
  label,
  value,
  fallbackLabel,
  options,
  onChange,
  loading = false,
  disabled = false,
  providerName,
  describedBy,
}: ModelComboboxProps) {
  const id = React.useId()
  const inputId = `${id}-input`
  const listId = `${id}-list`
  const inputRef = React.useRef<HTMLInputElement>(null)
  const listRef = React.useRef<HTMLDivElement>(null)

  const selected = options.find((option) => option.id === value)
  const selectedLabel = selected?.displayName ?? fallbackLabel ?? value

  const [open, setOpen] = React.useState(false)
  /** What is typed; null while nothing has been typed since the list opened. */
  const [query, setQuery] = React.useState<string | null>(null)
  const [active, setActive] = React.useState<string | null>(null)
  const [placement, setPlacement] = React.useState<Placement | null>(null)
  const [host, setHost] = React.useState<Element | null>(null)

  const { groups, hidden, flat } = React.useMemo(() => {
    const matched = filterModels(options, query ?? '')
    const capped = capGroups(groupModels(matched), OPTION_LIMIT)
    return { groups: capped.groups, hidden: capped.hidden, flat: capped.groups.flatMap((group) => group.options) }
  }, [options, query])

  const optionId = (modelId: string) => `${id}-option-${flat.findIndex((option) => option.id === modelId)}`

  const reposition = React.useCallback(() => {
    if (inputRef.current) setPlacement(place(inputRef.current))
  }, [])

  /** Opens the list beside the field, inside the dialog that holds it if any, so it is never under it. */
  const openList = () => {
    const input = inputRef.current
    if (input) {
      setPlacement(place(input))
      setHost(input.closest('dialog') ?? document.body)
    }
    setOpen(true)
  }

  React.useEffect(() => {
    if (!open) return
    window.addEventListener('resize', reposition)
    window.addEventListener('scroll', reposition, true)
    return () => {
      window.removeEventListener('resize', reposition)
      window.removeEventListener('scroll', reposition, true)
    }
  }, [open, reposition])

  // Keep the highlighted option in view as the arrow keys move it.
  React.useEffect(() => {
    if (!open || !active) return
    const node = listRef.current?.querySelector<HTMLElement>(`[data-model-id="${CSS.escape(active)}"]`)
    node?.scrollIntoView?.({ block: 'nearest' })
  }, [open, active])

  const show = () => {
    if (disabled) return
    openList()
    setQuery(null)
    setActive(selected ? selected.id : (groupModels(options)[0]?.options[0]?.id ?? null))
  }

  const close = () => {
    setOpen(false)
    setQuery(null)
  }

  const choose = (option: CatalogueModel) => {
    if (option.id !== value) onChange(option.id)
    close()
    inputRef.current?.focus()
  }

  const move = (step: number | 'first' | 'last') => {
    if (flat.length === 0) return
    const index = flat.findIndex((option) => option.id === active)
    const next =
      step === 'first' ? 0 : step === 'last' ? flat.length - 1 : Math.min(flat.length - 1, Math.max(0, index + step))
    setActive(flat[index === -1 && typeof step === 'number' ? 0 : next]?.id ?? null)
  }

  const onKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault()
        if (!open) show()
        else move(1)
        break
      case 'ArrowUp':
        event.preventDefault()
        if (!open) show()
        else move(-1)
        break
      case 'Home':
        if (open) {
          event.preventDefault()
          move('first')
        }
        break
      case 'End':
        if (open) {
          event.preventDefault()
          move('last')
        }
        break
      case 'Enter': {
        if (!open) return
        event.preventDefault()
        const option = flat.find((candidate) => candidate.id === active)
        if (option) choose(option)
        break
      }
      case 'Escape':
        if (open) {
          event.preventDefault()
          event.stopPropagation()
          close()
        }
        break
      case 'Tab':
        if (open) close()
        break
    }
  }

  const onInput = (event: React.ChangeEvent<HTMLInputElement>) => {
    const text = event.target.value
    setQuery(text)
    if (!open) openList()
    const first = groupModels(filterModels(options, text))[0]?.options[0]
    setActive(first?.id ?? null)
  }

  const activeId = open && active && flat.some((option) => option.id === active) ? optionId(active) : undefined

  const status = loading
    ? `Loading models from ${providerName}`
    : flat.length === 0
      ? query
        ? 'No model matches that. Try fewer words, or part of the model id.'
        : `${providerName} lists no model that can use tools.`
      : null

  const panel =
    open && placement && host
      ? createPortal(
          <div
            ref={listRef}
            className="model-combobox-panel"
            style={{
              top: placement.top,
              bottom: placement.bottom,
              left: placement.left,
              width: placement.width,
              maxHeight: placement.maxHeight,
            }}
            // A press inside the list must not take focus from the field, or the list closes first.
            onMouseDown={(event) => event.preventDefault()}
          >
            <div id={listId} role="listbox" aria-label={`${label} options`} className="model-combobox-list">
              {groups.map((group) => (
                <div key={group.key} role="group" aria-labelledby={`${id}-group-${group.key}`}>
                  <div id={`${id}-group-${group.key}`} role="presentation" className="model-combobox-heading eyebrow">
                    {group.label}
                  </div>
                  {group.options.map((option) => {
                    const isActive = option.id === active
                    const price = shortPrice(option)
                    const context = formatContext(option.contextLength)
                    return (
                      <div
                        key={option.id}
                        id={optionId(option.id)}
                        role="option"
                        aria-selected={option.id === value}
                        aria-label={optionSummary(option)}
                        data-model-id={option.id}
                        data-active={isActive || undefined}
                        className="model-combobox-option"
                        title={option.pricingNote ?? undefined}
                        onMouseEnter={() => setActive(option.id)}
                        onClick={() => choose(option)}
                      >
                        <span className="model-combobox-name">{option.displayName}</span>
                        <span className="model-combobox-id mono">{option.id}</span>
                        <span className="model-combobox-tags">
                          {context && <span className="model-combobox-tag">{context}</span>}
                          {option.vision && <span className="model-combobox-tag">Vision</span>}
                          {price && <span className="model-combobox-tag">{price}</span>}
                          {option.unavailable && <span className="model-combobox-tag">{NOT_AVAILABLE}</span>}
                          {option.id === value && <span className="model-combobox-tag model-combobox-tag-chosen">Chosen</span>}
                        </span>
                      </div>
                    )
                  })}
                </div>
              ))}
            </div>
            {status && (
              <p className="model-combobox-status caption" role="status">
                {status}
              </p>
            )}
            {hidden > 0 && (
              <p className="model-combobox-status caption">
                {`Showing ${flat.length} of ${flat.length + hidden}. Type to narrow the list.`}
              </p>
            )}
          </div>,
          host,
        )
      : null

  return (
    <div className="field model-combobox">
      <label className="field-label" htmlFor={inputId}>
        {label}
      </label>
      <div className="model-combobox-control">
        <input
          ref={inputRef}
          id={inputId}
          className="input model-combobox-input"
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={activeId}
          aria-describedby={describedBy}
          autoComplete="off"
          spellCheck={false}
          disabled={disabled}
          placeholder={loading ? 'Loading models' : 'Search models'}
          value={query ?? selectedLabel}
          onChange={onInput}
          onKeyDown={onKeyDown}
          onClick={() => (open ? undefined : show())}
          onFocus={(event) => event.target.select()}
          onBlur={close}
        />
        <span className="model-combobox-chevron" aria-hidden="true">
          <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
            <path d="M3 4.5 6 7.5l3-3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </span>
      </div>
      {panel}
    </div>
  )
}
