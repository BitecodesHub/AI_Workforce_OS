// @find: tests for useRovingList, roving tabindex, keyboard navigation, arrow keys list, accessible list, focus list item
// @what: Automated tests for useRovingList.
// @flow: Run with the web test runner; covers useRovingList.
import { act, renderHook } from '@testing-library/react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { describe, expect, it } from 'vitest'
import { nextRovingId, useRovingList } from './useRovingList'

describe('nextRovingId', () => {
  const ids = ['a', 'b', 'c']

  it('moves down, wrapping past the end', () => {
    expect(nextRovingId(ids, 'a', 'ArrowDown')).toBe('b')
    expect(nextRovingId(ids, 'c', 'ArrowDown')).toBe('a')
  })

  it('moves up, wrapping past the start', () => {
    expect(nextRovingId(ids, 'b', 'ArrowUp')).toBe('a')
    expect(nextRovingId(ids, 'a', 'ArrowUp')).toBe('c')
  })

  it('jumps to the ends with Home and End', () => {
    expect(nextRovingId(ids, 'b', 'Home')).toBe('a')
    expect(nextRovingId(ids, 'b', 'End')).toBe('c')
  })

  it('returns null for an empty list', () => {
    expect(nextRovingId([], null, 'ArrowDown')).toBeNull()
  })

  it('starts from the first row when nothing is active yet', () => {
    expect(nextRovingId(ids, null, 'ArrowDown')).toBe('a')
  })
})

describe('useRovingList', () => {
  it('starts with the first id as the active, tabbable row', () => {
    const { result } = renderHook(() => useRovingList(['a', 'b', 'c']))
    expect(result.current.activeId).toBe('a')
  })

  it('moves the active id on ArrowDown, only when the row itself has focus', () => {
    const { result } = renderHook(() => useRovingList(['a', 'b', 'c']))
    const row = document.createElement('div')
    const event = {
      key: 'ArrowDown',
      target: row,
      currentTarget: row,
      preventDefault: () => {},
    } as unknown as ReactKeyboardEvent
    act(() => result.current.onKeyDown(event))
    expect(result.current.activeId).toBe('b')
  })

  it('ignores the key when it bubbled up from a control inside the row', () => {
    const { result } = renderHook(() => useRovingList(['a', 'b', 'c']))
    const row = document.createElement('div')
    const button = document.createElement('button')
    const event = {
      key: 'ArrowDown',
      target: button,
      currentTarget: row,
      preventDefault: () => {},
    } as unknown as ReactKeyboardEvent
    act(() => result.current.onKeyDown(event))
    expect(result.current.activeId).toBe('a')
  })

  it('keeps exactly one row as the active id at a time', () => {
    const { result } = renderHook(() => useRovingList(['a', 'b', 'c']))
    act(() => result.current.setActiveId('c'))
    expect(result.current.activeId).toBe('c')
    expect(result.current.ids.filter((id) => id === result.current.activeId)).toHaveLength(1)
  })

  it('falls back to the first id once the active row is removed', () => {
    const { result, rerender } = renderHook(({ ids }) => useRovingList(ids), { initialProps: { ids: ['a', 'b', 'c'] } })
    act(() => result.current.setActiveId('b'))
    expect(result.current.activeId).toBe('b')
    rerender({ ids: ['a', 'c'] })
    expect(result.current.activeId).toBe('a')
  })
})
