// @find: region combobox, region picker, AWS region, select region, searchable dropdown, grouped regions, RegionCombobox
// @what: Searchable dropdown for choosing an AWS region, with grouped options and notes.
// @flow: Used by BedrockCredentialFields
import React from 'react'
import { createPortal } from 'react-dom'
import { place, type Placement } from '../routing/ModelCombobox'
import { BEDROCK_REGIONS, groupRegions, regionLabel, type BedrockRegion } from '../../lib/bedrock'

/*
 * The AWS region for Bedrock: a searchable list grouped by geography ("United States", "Europe",
 * ...), each region shown as its console name and id, "US East (N. Virginia) — us-east-1".
 *
 * It is the same ARIA combobox as the model picker (components/routing/ModelCombobox.tsx), and
 * draws its list with the same styles: type to filter by name, id or geography, arrow keys to move,
 * Enter to choose, Escape to close and put the chosen region back. A region "Find my region"
 * checked carries a short tag saying what it found there.
 */

export type RegionNote = { text: string; tone: 'good' | 'warn' | 'bad' }

type Props = {
  label?: string
  value: string
  onChange: (regionId: string) => void
  hint?: string | undefined
  error?: string | undefined
  disabled?: boolean | undefined
  /** What "Find my region" found, by region id. */
  notes?: Readonly<Record<string, RegionNote>> | undefined
}

// @find: RegionCombobox, choose AWS region dropdown
export function RegionCombobox({ label = 'Region', value, onChange, hint, error, disabled = false, notes = {} }: Props) {
  const id = React.useId()
  const inputId = `${id}-input`
  const listId = `${id}-list`
  const hintId = `${id}-hint`
  const errorId = `${id}-error`
  const inputRef = React.useRef<HTMLInputElement>(null)
  const listRef = React.useRef<HTMLDivElement>(null)

  const [open, setOpen] = React.useState(false)
  const [query, setQuery] = React.useState<string | null>(null)
  const [active, setActive] = React.useState<string | null>(null)
  const [placement, setPlacement] = React.useState<Placement | null>(null)
  const [host, setHost] = React.useState<Element | null>(null)

  const groups = React.useMemo(() => groupRegions(query ?? ''), [query])
  const flat: BedrockRegion[] = React.useMemo(() => groups.flatMap((group) => group.regions), [groups])
  const optionId = (regionId: string) => `${id}-option-${regionId}`

  const reposition = React.useCallback(() => {
    if (inputRef.current) setPlacement(place(inputRef.current))
  }, [])

  React.useEffect(() => {
    if (!open) return
    window.addEventListener('resize', reposition)
    window.addEventListener('scroll', reposition, true)
    return () => {
      window.removeEventListener('resize', reposition)
      window.removeEventListener('scroll', reposition, true)
    }
  }, [open, reposition])

  React.useEffect(() => {
    if (!open || !active) return
    listRef.current?.querySelector<HTMLElement>(`[data-region-id="${CSS.escape(active)}"]`)?.scrollIntoView?.({ block: 'nearest' })
  }, [open, active])

  const openList = () => {
    const input = inputRef.current
    if (input) {
      setPlacement(place(input))
      setHost(input.closest('dialog') ?? document.body)
    }
    setOpen(true)
  }

  const show = () => {
    if (disabled) return
    openList()
    setQuery(null)
    setActive(value || (BEDROCK_REGIONS[0]?.id ?? null))
  }

  const close = () => {
    setOpen(false)
    setQuery(null)
  }

  const choose = (regionId: string) => {
    if (regionId !== value) onChange(regionId)
    close()
    inputRef.current?.focus()
  }

  const move = (step: number | 'first' | 'last') => {
    if (flat.length === 0) return
    const index = flat.findIndex((region) => region.id === active)
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
      case 'Enter':
        if (!open) return
        event.preventDefault()
        if (active && flat.some((region) => region.id === active)) choose(active)
        break
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
    setActive(groupRegions(text)[0]?.regions[0]?.id ?? null)
  }

  const describedBy = [error ? errorId : hint ? hintId : null].filter(Boolean).join(' ') || undefined
  const activeId = open && active && flat.some((region) => region.id === active) ? optionId(active) : undefined

  const panel =
    open && placement && host
      ? createPortal(
          <div
            ref={listRef}
            className="model-combobox-panel"
            style={{ top: placement.top, bottom: placement.bottom, left: placement.left, width: placement.width, maxHeight: placement.maxHeight }}
            onMouseDown={(event) => event.preventDefault()}
          >
            <div id={listId} role="listbox" aria-label={`${label} options`} className="model-combobox-list">
              {groups.map((group) => {
                const headingId = `${id}-group-${group.geography.replace(/\W+/g, '-')}`
                return (
                  <div key={group.geography} role="group" aria-labelledby={headingId}>
                    <div id={headingId} role="presentation" className="model-combobox-heading eyebrow">
                      {group.geography}
                    </div>
                    {group.regions.map((region) => {
                      const note = notes[region.id]
                      return (
                        <div
                          key={region.id}
                          id={optionId(region.id)}
                          role="option"
                          aria-selected={region.id === value}
                          aria-label={`${region.name}, ${region.id}${note ? `, ${note.text}` : ''}`}
                          data-region-id={region.id}
                          data-active={region.id === active || undefined}
                          className="model-combobox-option"
                          onMouseEnter={() => setActive(region.id)}
                          onClick={() => choose(region.id)}
                        >
                          <span className="model-combobox-name">{region.name}</span>
                          <span className="model-combobox-id mono">{region.id}</span>
                          <span className="model-combobox-tags">
                            {note && (
                              <span className={`model-combobox-tag region-note-${note.tone}`}>{note.text}</span>
                            )}
                            {region.id === value && <span className="model-combobox-tag model-combobox-tag-chosen">Chosen</span>}
                          </span>
                        </div>
                      )
                    })}
                  </div>
                )
              })}
            </div>
            {flat.length === 0 && (
              <p className="model-combobox-status caption" role="status">
                No region matches that. Try a city, a country or an id such as eu-west-1.
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
          aria-invalid={error ? true : undefined}
          autoComplete="off"
          spellCheck={false}
          disabled={disabled}
          placeholder="Search regions"
          value={query ?? regionLabel(value)}
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
      {hint && !error && (
        <p className="caption field-hint" id={hintId}>
          {hint}
        </p>
      )}
      {error && (
        <p className="field-error" id={errorId}>
          {error}
        </p>
      )}
      {panel}
    </div>
  )
}
