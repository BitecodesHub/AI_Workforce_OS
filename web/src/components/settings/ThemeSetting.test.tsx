// @find: tests for theme switch, appearance setting, dark mode control, keyboard
// @what: Tests that the theme switch is a labelled radio group that persists and applies the choice.
// @flow: Renders ThemeSwitch from ThemeSetting.tsx
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { ThemeSwitch } from './ThemeSetting'
import { THEME_STORAGE_KEY, resetThemeStoreForTests } from '../../lib/theme'

const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')

// A fresh in-memory Storage per test; Node's own global localStorage shadows jsdom's (see persist.test.ts).
function installMemoryStorage() {
  const items = new Map<string, string>()
  const storage = {
    get length() {
      return items.size
    },
    clear: () => items.clear(),
    getItem: (key: string) => items.get(key) ?? null,
    key: (index: number) => [...items.keys()][index] ?? null,
    removeItem: (key: string) => void items.delete(key),
    setItem: (key: string, value: string) => void items.set(key, String(value)),
  }
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: storage })
}

afterEach(() => {
  if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage)
})

beforeEach(() => {
  installMemoryStorage()
  resetThemeStoreForTests()
  window.matchMedia = vi.fn(() => ({
    matches: false,
    addEventListener: () => {},
    removeEventListener: () => {},
  })) as unknown as typeof window.matchMedia
})

describe('ThemeSwitch', () => {
  it('offers System, Light and Dark as a named radio group, System chosen by default', () => {
    render(<ThemeSwitch />)
    const group = screen.getByRole('group', { name: 'Theme' })
    expect(group).toBeInTheDocument()
    const radios = screen.getAllByRole('radio')
    expect(radios.map((radio) => radio.closest('label')?.textContent)).toEqual(['System', 'Light', 'Dark'])
    expect(screen.getByRole('radio', { name: 'System' })).toBeChecked()
  })

  it('stores and applies the chosen theme', () => {
    render(<ThemeSwitch />)
    fireEvent.click(screen.getByRole('radio', { name: 'Dark' }))
    expect(screen.getByRole('radio', { name: 'Dark' })).toBeChecked()
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(document.documentElement.style.colorScheme).toBe('dark')
  })

  it('shows the stored choice when it opens, and keeps two copies in step', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'light')
    render(
      <>
        <ThemeSwitch legend="Theme one" />
        <ThemeSwitch legend="Theme two" />
      </>,
    )
    const [first, second] = screen.getAllByRole('radio', { name: 'Light' })
    expect(first).toBeChecked()
    fireEvent.click(screen.getAllByRole('radio', { name: 'Dark' })[0]!)
    expect(screen.getAllByRole('radio', { name: 'Dark' })[1]).toBeChecked()
    expect(second).not.toBeChecked()
  })
})
