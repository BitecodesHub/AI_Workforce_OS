// @find: tests for theme setting, dark mode, no-flash script, data-theme, color-scheme, theme persistence, CSP hash
// @what: Tests that the theme setting persists, applies to <html>, and that index.html's inline script agrees with it.
// @flow: Tests lib/theme.ts, index.html and infra/launcher/nginx.conf
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import {
  THEME_STORAGE_KEY,
  applyTheme,
  readThemeSetting,
  resetThemeStoreForTests,
  resolveTheme,
  setThemeSetting,
  startThemeSync,
  type ThemeSetting,
} from './theme'

/*
 * A fresh in-memory Storage per test: recent Node versions define their own global localStorage,
 * which shadows jsdom's and reads as undefined (see persist.test.ts).
 */
function memoryStorage(overrides: Partial<Storage> = {}): Storage {
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
    ...overrides,
  }
}

function installStorage(storage: Storage) {
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: storage })
}

const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')

function stubSystem(prefersDark: boolean) {
  const listeners = new Set<() => void>()
  const query = {
    matches: prefersDark,
    addEventListener: (_: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_: string, listener: () => void) => listeners.delete(listener),
  }
  window.matchMedia = vi.fn(() => query) as unknown as typeof window.matchMedia
  return {
    flip(next: boolean) {
      query.matches = next
      for (const listener of listeners) listener()
    },
  }
}

const root = () => document.documentElement

beforeEach(() => {
  installStorage(memoryStorage())
  resetThemeStoreForTests()
  delete root().dataset.theme
  root().style.colorScheme = ''
})

afterEach(() => {
  vi.restoreAllMocks()
  if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage)
})

describe('theme setting', () => {
  it('defaults to System, and ignores a stored value it does not recognise', () => {
    expect(readThemeSetting()).toBe('system')
    localStorage.setItem(THEME_STORAGE_KEY, 'sepia')
    expect(readThemeSetting()).toBe('system')
  })

  it('falls back to System when storage throws', () => {
    installStorage(
      memoryStorage({
        getItem: () => {
          throw new Error('blocked')
        },
      }),
    )
    expect(readThemeSetting()).toBe('system')
  })

  it('keeps working for the visit when storage refuses a write', () => {
    stubSystem(false)
    installStorage(
      memoryStorage({
        setItem: () => {
          throw new Error('quota')
        },
      }),
    )
    expect(() => setThemeSetting('dark')).not.toThrow()
    expect(root().dataset.theme).toBe('dark')
  })

  it('persists Light and Dark, and stores nothing for System', () => {
    stubSystem(false)
    setThemeSetting('dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
    resetThemeStoreForTests()
    expect(readThemeSetting()).toBe('dark')
    setThemeSetting('light')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')
    setThemeSetting('system')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull()
  })

  it('resolves System from the device, and Light or Dark regardless of it', () => {
    expect(resolveTheme('system', true)).toBe('dark')
    expect(resolveTheme('system', false)).toBe('light')
    expect(resolveTheme('light', true)).toBe('light')
    expect(resolveTheme('dark', false)).toBe('dark')
  })

  it('applies the theme to <html> as data-theme and color-scheme', () => {
    stubSystem(true)
    expect(applyTheme('system')).toBe('dark')
    expect(root().dataset.theme).toBe('dark')
    expect(root().style.colorScheme).toBe('dark')
    applyTheme('light')
    expect(root().dataset.theme).toBe('light')
    expect(root().style.colorScheme).toBe('light')
  })

  it('follows the device while set to System, and stops following once pinned', () => {
    const system = stubSystem(false)
    const stop = startThemeSync()
    expect(root().dataset.theme).toBe('light')
    system.flip(true)
    expect(root().dataset.theme).toBe('dark')
    setThemeSetting('light')
    system.flip(true)
    expect(root().dataset.theme).toBe('light')
    stop()
  })

  it('picks up a choice made in another tab', () => {
    stubSystem(false)
    const stop = startThemeSync()
    localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    window.dispatchEvent(new StorageEvent('storage', { key: THEME_STORAGE_KEY }))
    expect(root().dataset.theme).toBe('dark')
    stop()
  })
})

/*
 * The inline script in index.html runs before React, so it cannot import theme.ts. These run the
 * script itself against every combination and hold it to the same answer as resolveTheme.
 */
describe('no-flash script in index.html', () => {
  const html = readFileSync(join(__dirname, '..', '..', 'index.html'), 'utf8')
  const inline = /<script>([^<]+)<\/script>/.exec(html)?.[1] ?? ''

  function run(stored: string | null | 'throws', prefersDark: boolean | 'missing') {
    const attributes: Record<string, string> = {}
    const element = { setAttribute: (k: string, v: string) => (attributes[k] = v), style: { colorScheme: '' } }
    const storage = {
      getItem: () => {
        if (stored === 'throws') throw new Error('blocked')
        return stored
      },
    }
    const win = prefersDark === 'missing' ? {} : { matchMedia: () => ({ matches: prefersDark }) }
    new Function('localStorage', 'window', 'document', inline)(storage, win, { documentElement: element })
    return { theme: attributes['data-theme'], scheme: element.style.colorScheme }
  }

  it('is in the head, before the app script, and sets both data-theme and color-scheme', () => {
    expect(inline.length).toBeGreaterThan(0)
    expect(html.indexOf(inline)).toBeLessThan(html.indexOf('</head>'))
    expect(inline).toContain(`'${THEME_STORAGE_KEY}'`)
  })

  it('agrees with resolveTheme for every stored value and device preference', () => {
    const stored: (ThemeSetting | null | string)[] = [null, 'system', 'light', 'dark', 'garbage']
    for (const value of stored) {
      for (const prefersDark of [true, false]) {
        const setting: ThemeSetting = value === 'light' || value === 'dark' ? value : 'system'
        const expected = resolveTheme(setting, prefersDark)
        expect(run(value, prefersDark), `stored ${value}, device dark ${prefersDark}`).toEqual({
          theme: expected,
          scheme: expected,
        })
      }
    }
  })

  it('falls back to light when storage throws or matchMedia is missing', () => {
    expect(run('throws', false).theme).toBe('light')
    expect(run('throws', true).theme).toBe('dark')
    expect(run(null, 'missing').theme).toBe('light')
    expect(run('dark', 'missing').theme).toBe('dark')
  })

  it('marks <html> with data-session only when this browser has signed in before', () => {
    /** Runs the inline script with only the signed-in hint stored, and returns the attributes it set on <html>. */
    function attributesFor(signedIn: string | null) {
      const attributes: Record<string, string> = {}
      const element = { setAttribute: (k: string, v: string) => (attributes[k] = v), style: { colorScheme: '' } }
      const storage = { getItem: (key: string) => (key === 'aiwos.signedIn' ? signedIn : null) }
      new Function('localStorage', 'window', 'document', inline)(storage, {}, { documentElement: element })
      return attributes
    }
    expect(attributesFor('1')).toHaveProperty('data-session', '')
    expect(attributesFor(null)).not.toHaveProperty('data-session')
    expect(attributesFor('0')).not.toHaveProperty('data-session')
    // The key is the one session.ts writes when a person signs in.
    const session = readFileSync(join(__dirname, 'session.ts'), 'utf8')
    expect(session).toContain("const SIGNED_IN_HINT = 'aiwos.signedIn'")
  })

  it('is allowed by its hash in the Content-Security-Policy', () => {
    const nginx = readFileSync(join(__dirname, '..', '..', '..', 'infra', 'launcher', 'nginx.conf'), 'utf8')
    const hash = createHash('sha256').update(inline, 'utf8').digest('base64')
    expect(nginx, 'update the script hash in infra/launcher/nginx.conf').toContain(`'sha256-${hash}'`)
  })
})
