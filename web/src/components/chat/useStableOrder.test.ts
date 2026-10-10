// @find: tests for useStableOrder, stable order, list order frozen, avoid reorder while polling, sidebar order, hover freeze
// @what: Automated tests for useStableOrder.
// @flow: Run with the web test runner; covers useStableOrder.
import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { useStableOrder } from './useStableOrder'

describe('useStableOrder', () => {
  it('tracks the server order while not frozen', () => {
    const { result, rerender } = renderHook(({ ids, frozen }) => useStableOrder(ids, frozen), {
      initialProps: { ids: ['a', 'b', 'c'], frozen: false },
    })
    expect(result.current).toEqual(['a', 'b', 'c'])
    rerender({ ids: ['c', 'b', 'a'], frozen: false })
    expect(result.current).toEqual(['c', 'b', 'a'])
  })

  it('keeps the order it had the moment it froze, even as the server reorders', () => {
    const { result, rerender } = renderHook(({ ids, frozen }) => useStableOrder(ids, frozen), {
      initialProps: { ids: ['a', 'b', 'c'], frozen: false },
    })
    rerender({ ids: ['a', 'b', 'c'], frozen: true })
    rerender({ ids: ['c', 'b', 'a'], frozen: true })
    expect(result.current).toEqual(['a', 'b', 'c'])
  })

  it('appends a newly arrived id at the end while frozen', () => {
    const { result, rerender } = renderHook(({ ids, frozen }) => useStableOrder(ids, frozen), {
      initialProps: { ids: ['a', 'b'], frozen: true },
    })
    rerender({ ids: ['new', 'a', 'b'], frozen: true })
    expect(result.current).toEqual(['a', 'b', 'new'])
  })

  it('drops a removed id while frozen, keeping the rest in place', () => {
    const { result, rerender } = renderHook(({ ids, frozen }) => useStableOrder(ids, frozen), {
      initialProps: { ids: ['a', 'b', 'c'], frozen: true },
    })
    rerender({ ids: ['a', 'c'], frozen: true })
    expect(result.current).toEqual(['a', 'c'])
  })

  it('takes the server order again the moment it unfreezes', () => {
    const { result, rerender } = renderHook(({ ids, frozen }) => useStableOrder(ids, frozen), {
      initialProps: { ids: ['a', 'b'], frozen: true },
    })
    rerender({ ids: ['new', 'a', 'b'], frozen: true })
    expect(result.current).toEqual(['a', 'b', 'new'])
    rerender({ ids: ['new', 'a', 'b'], frozen: false })
    expect(result.current).toEqual(['new', 'a', 'b'])
  })
})
