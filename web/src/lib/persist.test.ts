// @find: tests for persist, readStored, writeStored, usePersistentState, blocked storage
// @what: Unit tests for persistent state helpers.
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readStored, usePersistentState, writeStored } from './persist'

const isString = (value: unknown): value is string => typeof value === 'string'

/*
 * A minimal Storage-like backing store, installed fresh for each test. Recent Node versions define
 * their own global `localStorage`, which shadows jsdom's and reads as undefined without a
 * --localstorage-file flag (see onboarding.test.ts, which hits the same problem for
 * window.localStorage), so this file installs its own rather than relying on either.
 */
function memoryStorage(): Storage {
  const items = new Map<string, string>()
  return {
    get length() {
      return items.size
    },
    clear: () => items.clear(),
    getItem: (key) => items.get(key) ?? null,
    key: (index) => [...items.keys()][index] ?? null,
    removeItem: (key) => void items.delete(key),
    setItem: (key, value) => void items.set(key, String(value)),
  }
}

const originalDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')

function installStorage(storage: Storage) {
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: storage })
}

beforeEach(() => installStorage(memoryStorage()))

afterEach(() => {
  vi.restoreAllMocks()
  if (originalDescriptor) Object.defineProperty(globalThis, 'localStorage', originalDescriptor)
})

describe('readStored', () => {
  it('reads back what was stored', () => {
    localStorage.setItem('k', JSON.stringify({ a: 1 }))
    expect(readStored('k', { a: 0 })).toEqual({ a: 1 })
  })

  it('falls back when the key is missing', () => {
    expect(readStored('missing', 'fallback')).toBe('fallback')
  })

  it('falls back on unparsable JSON', () => {
    localStorage.setItem('k', 'not json')
    expect(readStored('k', 'fallback')).toBe('fallback')
  })

  it('falls back when the stored value fails validation', () => {
    localStorage.setItem('k', JSON.stringify(42))
    expect(readStored('k', 'fallback', isString)).toBe('fallback')
  })

  it('falls back when localStorage throws', () => {
    vi.spyOn(localStorage, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    expect(readStored('k', 'fallback')).toBe('fallback')
  })
})

describe('writeStored', () => {
  it('stores a JSON-serialised value', () => {
    writeStored('k', { a: 1 })
    expect(localStorage.getItem('k')).toBe(JSON.stringify({ a: 1 }))
  })

  it('does not throw when localStorage throws', () => {
    vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
      throw new Error('quota exceeded')
    })
    expect(() => writeStored('k', 'value')).not.toThrow()
  })
})

describe('usePersistentState', () => {
  it('reads its initial value from storage and writes changes back', () => {
    localStorage.setItem('count', JSON.stringify(2))
    const { result } = renderHook(() => usePersistentState('count', 0))
    expect(result.current[0]).toBe(2)

    act(() => result.current[1](3))
    expect(result.current[0]).toBe(3)
    expect(localStorage.getItem('count')).toBe('3')

    act(() => result.current[1]((previous) => previous + 1))
    expect(result.current[0]).toBe(4)
  })

  it('falls back to the initial value when storage throws on read and on write', () => {
    vi.spyOn(localStorage, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const { result } = renderHook(() => usePersistentState('count', 5))
    expect(result.current[0]).toBe(5)
    act(() => result.current[1](9))
    expect(result.current[0]).toBe(9)
  })
})
