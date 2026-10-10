// @find: list filter, search box, facets, filter chips, query string filter, ?q=, status filter, useListFilter, filtered list survives reload
// @what: Search and facet filters for a list, kept in the URL so Back, reload and shared links keep the filter.
// @flow: Used by list pages; reads and writes the URL via router.tsx
import { useCallback, useMemo } from 'react'
import { useRouter } from './router'

/*
 * Search and facet filters for a list, kept in the URL.
 *
 * The state lives in the query string (?q=invoice&status=failed,running) rather than in component
 * state, so a filtered list survives Back, a reload and a copied link, and a person who opens a
 * run from a filtered list returns to the same filtered list. Every change replaces the current
 * history entry instead of adding one, so Back leaves the list rather than undoing keystrokes.
 *
 * Other query parameters on the page (Tasks' ?goal=, for example) are left untouched. Facet
 * values are joined with commas, so they must not contain one; status codes and ids do not.
 */

type FacetValue = string | readonly string[] | null | undefined

export type ListFilterOptions<T> = {
  /** The loaded rows. Undefined while loading reads as an empty list. */
  rows: readonly T[] | undefined
  /** The searchable text of a row; every whitespace-separated term must appear in it. */
  text: (row: T) => string
  /** One entry per facet: the URL parameter name, and the value or values a row has for it. */
  facets?: Record<string, (row: T) => FacetValue>
  /** The URL parameter for the free-text search. Defaults to 'q'. */
  queryParam?: string
}

export type ListFilter<T> = {
  query: string
  setQuery: (query: string) => void
  /** The selected values per facet parameter; an empty array means the facet is not filtering. */
  selected: Record<string, string[]>
  /** Adds the value to the facet's selection, or removes it if it is already selected. */
  toggle: (param: string, value: string) => void
  /** Selects exactly this value for the facet, or clears the facet with null. */
  setOnly: (param: string, value: string | null) => void
  /** Clears the search and every facet. */
  clear: () => void
  /** Rows matching the search and every facet (any selected value within a facet). */
  filtered: T[]
  /**
   * Per facet, how many rows have each value, counted with the search and the other facets
   * applied but not this one, so each count says what choosing that value would show.
   */
  counts: Record<string, Record<string, number>>
  /** True when the search or any facet is filtering. */
  active: boolean
  /** The number of rows before filtering. */
  total: number
}

function splitValues(raw: string | null): string[] {
  return raw ? raw.split(',').map((value) => value.trim()).filter(Boolean) : []
}

function valuesOf(value: FacetValue): readonly string[] {
  if (value == null) return []
  return typeof value === 'string' ? [value] : value
}

function setValues(next: URLSearchParams, param: string, values: string[]) {
  if (values.length > 0) next.set(param, values.join(','))
  else next.delete(param)
}

function termsOf(query: string): string[] {
  return query.toLowerCase().split(/\s+/).filter(Boolean)
}

// @find: use list filter, search and filter a table, URL query state
export function useListFilter<T>({ rows, text, facets, queryParam = 'q' }: ListFilterOptions<T>): ListFilter<T> {
  const { search, navigate } = useRouter()

  // Stable while the facet names are unchanged, even when `facets` is a new object each render.
  const facetKey = Object.keys(facets ?? {}).join('\n')
  const params = useMemo(() => (facetKey ? facetKey.split('\n') : []), [facetKey])

  const query = search.get(queryParam) ?? ''
  const selected = useMemo(() => {
    const result: Record<string, string[]> = {}
    for (const param of params) result[param] = splitValues(search.get(param))
    return result
  }, [params, search])

  /** Applies a change to the current query string and replaces the history entry with it. */
  const write = useCallback(
    (change: (next: URLSearchParams) => void) => {
      // Read the live URL rather than the rendered one, so two changes in one event both land.
      const next = new URLSearchParams(window.location.search)
      change(next)
      const qs = next.toString().replace(/%2C/gi, ',')
      navigate(`${window.location.pathname}${qs ? `?${qs}` : ''}${window.location.hash}`, {
        replace: true,
        scroll: false,
      })
    },
    [navigate],
  )

  const setQuery = useCallback(
    (value: string) =>
      write((next) => {
        if (value.trim()) next.set(queryParam, value)
        else next.delete(queryParam)
      }),
    [write, queryParam],
  )

  const toggle = useCallback(
    (param: string, value: string) =>
      write((next) => {
        const current = splitValues(next.get(param))
        setValues(next, param, current.includes(value) ? current.filter((v) => v !== value) : [...current, value])
      }),
    [write],
  )

  const setOnly = useCallback(
    (param: string, value: string | null) => write((next) => setValues(next, param, value ? [value] : [])),
    [write],
  )

  const clear = useCallback(
    () =>
      write((next) => {
        next.delete(queryParam)
        for (const param of params) next.delete(param)
      }),
    [write, queryParam, params],
  )

  const { filtered, counts } = useMemo(() => {
    const all = rows ?? []
    const terms = termsOf(query)
    const facetFns = facets ?? {}

    const matchesText = (row: T) => {
      if (terms.length === 0) return true
      const haystack = text(row).toLowerCase()
      return terms.every((term) => haystack.includes(term))
    }
    const matchesFacet = (row: T, param: string) => {
      const wanted = selected[param] ?? []
      if (wanted.length === 0) return true
      const fn = facetFns[param]
      if (!fn) return true
      return valuesOf(fn(row)).some((value) => wanted.includes(value))
    }

    const textMatches = all.filter(matchesText)
    const filteredRows = textMatches.filter((row) => params.every((param) => matchesFacet(row, param)))

    const facetCounts: Record<string, Record<string, number>> = {}
    for (const param of params) {
      const fn = facetFns[param]
      const tally: Record<string, number> = {}
      if (fn) {
        for (const row of textMatches) {
          if (!params.every((other) => other === param || matchesFacet(row, other))) continue
          for (const value of new Set(valuesOf(fn(row)))) tally[value] = (tally[value] ?? 0) + 1
        }
      }
      facetCounts[param] = tally
    }

    return { filtered: filteredRows, counts: facetCounts }
  }, [rows, query, facets, text, selected, params])

  const active = query.trim() !== '' || params.some((param) => (selected[param]?.length ?? 0) > 0)

  return {
    query,
    setQuery,
    selected,
    toggle,
    setOnly,
    clear,
    filtered,
    counts,
    active,
    total: rows?.length ?? 0,
  }
}
