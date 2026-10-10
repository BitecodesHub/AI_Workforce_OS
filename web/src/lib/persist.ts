// @find: persist, localStorage, remember state, saved draft, collapsed section, chosen tab, usePersistentState, readStored, writeStored
// @what: Safe localStorage helpers for state that survives a reload (drafts, tabs, collapsed sections).
// @flow: Used by many pages; never throws if storage is blocked
import { useCallback, useState } from 'react'

/*
 * State that survives a reload, kept in localStorage: a collapsed section, a chosen tab, a draft
 * message. Every access is wrapped in try/catch - a private window, cleared site data, or a
 * browser that blocks storage entirely must never crash the page, and simply behaves as if
 * nothing had ever been saved.
 */

// @find: read stored value, localStorage get
export function readStored<T>(key: string, fallback: T, validate?: (value: unknown) => value is T): T {
  try {
    const raw = localStorage.getItem(key)
    if (raw == null) return fallback
    const parsed: unknown = JSON.parse(raw)
    if (validate && !validate(parsed)) return fallback
    return parsed as T
  } catch {
    return fallback
  }
}

// @find: write stored value, localStorage set
export function writeStored<T>(key: string, value: T): void {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Private browsing, a full quota, or storage blocked outright: the page keeps working for
    // this visit, it simply will not remember the choice next time.
  }
}

// @find: use persistent state, remembered state hook
/** React state that reads its initial value from storage and writes every change back to it. */
export function usePersistentState<T>(
  key: string,
  initial: T,
  validate?: (value: unknown) => value is T,
): [T, (value: T | ((previous: T) => T)) => void] {
  const [state, setState] = useState<T>(() => readStored(key, initial, validate))

  const set = useCallback(
    (value: T | ((previous: T) => T)) => {
      setState((previous) => {
        const next = value instanceof Function ? value(previous) : value
        writeStored(key, next)
        return next
      })
    },
    [key],
  )

  return [state, set]
}
