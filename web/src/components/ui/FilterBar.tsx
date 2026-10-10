// @find: filter bar, search list, filter chips, facets, filter select, no matches, clear filters, FilterBar, FilterEmpty, list search, result count
// @what: Search box and filter controls above a long list, with a live result count and an empty state.
// @flow: Used by list screens; state lives in lib/useListFilter (URL).
import { useId } from 'react'
import { formatCount } from '../../lib/format'
import { Button, EmptyState, Input, Select } from './index'
import { EmptyIcon } from './QueryState'

/*
 * Search and filters above a long list.
 *
 * The bar only draws the controls; the state lives in the URL through useListFilter
 * (lib/useListFilter.ts), so a filtered list survives Back and can be shared as a link. The count
 * under the controls is a polite live region: somebody typing into the search hears how many rows
 * are left without having to go looking for the table.
 */

// @find: filter option type
export type FilterOption = {
  value: string
  label: string
  /** How many rows this option would show, given the search and the other filters. */
  count?: number | undefined
}

// @find: filter facet type, toggle chips
/** A set of toggle chips. Several may be pressed at once; a row matching any of them is shown. */
export type FilterFacet = {
  param: string
  label: string
  options: FilterOption[]
  selected: string[]
  onToggle: (value: string) => void
}

// @find: filter select type
/** A single choice from a list, such as one agent. */
export type FilterSelect = {
  label: string
  value: string
  options: Array<{ value: string; label: string }>
  onChange: (value: string) => void
}

// @find: filter bar, search and filter a list
export function FilterBar({
  searchLabel,
  query,
  onQueryChange,
  placeholder,
  facets = [],
  selects = [],
  shown,
  total,
  scopeNote,
  active,
  onClear,
}: {
  searchLabel: string
  query: string
  onQueryChange: (query: string) => void
  placeholder?: string | undefined
  facets?: FilterFacet[] | undefined
  selects?: FilterSelect[] | undefined
  /** Rows left after filtering. */
  shown: number
  /** Rows the filters ran over. */
  total: number
  /**
   * What the filters cover when that is less than everything, as a sentence: "Search covers the
   * 150 runs loaded so far." Required wherever only a page of history is loaded, or the count
   * would claim more than it knows.
   */
  scopeNote?: string | undefined
  /** Whether any search or filter is set, which offers the way back. */
  active: boolean
  onClear: () => void
}) {
  const barId = useId()
  return (
    <div className="filter-bar" role="search" aria-label={searchLabel}>
      <div className="filter-search">
        <Input
          type="search"
          label={searchLabel}
          value={query}
          onChange={(event) => onQueryChange(event.target.value)}
          placeholder={placeholder}
          autoComplete="off"
          spellCheck={false}
        />
      </div>

      {facets.map((facet) => {
        const labelId = `${barId}-${facet.param}`
        return (
          <div key={facet.param} className="filter-facet" role="group" aria-labelledby={labelId}>
            <span className="field-label" id={labelId}>
              {facet.label}
            </span>
            <div className="filter-chips">
              {facet.options.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  className="filter-chip"
                  aria-pressed={facet.selected.includes(option.value)}
                  onClick={() => facet.onToggle(option.value)}
                >
                  {option.label}
                  {option.count !== undefined && (
                    <span className="filter-chip-count tabular">{formatCount(option.count)}</span>
                  )}
                </button>
              ))}
            </div>
          </div>
        )
      })}

      {selects.map((select) => (
        <div key={select.label} className="filter-select">
          <Select label={select.label} value={select.value} onChange={(event) => select.onChange(event.target.value)}>
            {select.options.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </Select>
        </div>
      ))}

      {active && (
        <Button variant="quiet" onClick={onClear}>
          Clear filters
        </Button>
      )}

      <p role="status" className="caption filter-count">
        Showing {formatCount(shown)} of {formatCount(total)}.{scopeNote ? ` ${scopeNote}` : ''}
      </p>
    </div>
  )
}

// @find: no results for filter, clear filters empty state
/** What a filtered list shows when nothing is left, with the way back. */
export function FilterEmpty({ onClear, what }: { onClear: () => void; what: string }) {
  return (
    <EmptyState
      icon={<EmptyIcon kind="search" />}
      title="Nothing matches these filters"
      body={`No ${what} match the current search and filters.`}
      action={
        <Button variant="outline" onClick={onClear}>
          Clear filters
        </Button>
      }
    />
  )
}
