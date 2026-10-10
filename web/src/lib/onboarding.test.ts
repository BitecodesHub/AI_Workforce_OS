// @find: tests for onboarding, guide steps, hide guide, localStorage failure
// @what: Unit tests for the getting-started guide memory.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { GUIDE_CHANGE_EVENT, isGuideHidden, isStepDone, markStepDone, onGuideChange, setGuideHidden } from './onboarding'

/*
 * An in-memory localStorage. Recent Node versions define their own global localStorage, which
 * shadows jsdom's and is undefined without a storage file, so the tests install their own.
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

function installStorage(storage: Storage) {
  Object.defineProperty(window, 'localStorage', { configurable: true, value: storage })
}

const originalStorage = Object.getOwnPropertyDescriptor(window, 'localStorage')

beforeEach(() => installStorage(memoryStorage()))

afterEach(() => {
  vi.restoreAllMocks()
  if (originalStorage) Object.defineProperty(window, 'localStorage', originalStorage)
})

describe('getting-started memory', () => {
  it('remembers steps per user under aiwos.gs keys', () => {
    expect(isStepDone('user-1', 'read-trace')).toBe(false)
    expect(markStepDone('user-1', 'read-trace')).toBe(true)
    expect(isStepDone('user-1', 'read-trace')).toBe(true)
    expect(window.localStorage.getItem('aiwos.gs.user-1.read-trace')).toBe('1')
    // Another person on the same browser starts fresh.
    expect(isStepDone('user-2', 'read-trace')).toBe(false)
  })

  it('hides and shows the guide', () => {
    expect(isGuideHidden('user-1')).toBe(false)
    setGuideHidden('user-1', true)
    expect(isGuideHidden('user-1')).toBe(true)
    expect(window.localStorage.getItem('aiwos.gs.user-1.hidden')).toBe('1')
    setGuideHidden('user-1', false)
    expect(isGuideHidden('user-1')).toBe(false)
  })

  it('reads false and does not throw when storage is blocked', () => {
    const blocked = memoryStorage()
    blocked.getItem = () => {
      throw new DOMException('blocked', 'SecurityError')
    }
    blocked.setItem = () => {
      throw new DOMException('full', 'QuotaExceededError')
    }
    installStorage(blocked)
    expect(isStepDone('user-1', 'give-task')).toBe(false)
    expect(isGuideHidden('user-1')).toBe(false)
    expect(markStepDone('user-1', 'give-task')).toBe(false)
    expect(setGuideHidden('user-1', true)).toBe(false)
  })

  it('ignores an empty user id', () => {
    expect(markStepDone('', 'see-role')).toBe(false)
    expect(isStepDone('', 'see-role')).toBe(false)
  })

  it('tells listeners about changes', () => {
    const listener = vi.fn()
    const stop = onGuideChange(listener)
    setGuideHidden('user-1', true)
    expect(listener).toHaveBeenCalledTimes(1)
    window.dispatchEvent(new StorageEvent('storage', { key: 'aiwos.gs.user-1.meet-agent' }))
    expect(listener).toHaveBeenCalledTimes(2)
    window.dispatchEvent(new StorageEvent('storage', { key: 'unrelated' }))
    expect(listener).toHaveBeenCalledTimes(2)
    stop()
    window.dispatchEvent(new Event(GUIDE_CHANGE_EVENT))
    expect(listener).toHaveBeenCalledTimes(2)
  })
})
