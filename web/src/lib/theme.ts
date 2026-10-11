// @find: theme, dark mode, light mode, colour scheme, system theme, prefers-color-scheme, data-theme, useTheme, theme setting
// @what: The three-way theme setting (System, Light, Dark): stored per browser, applied to <html> as data-theme and color-scheme.
// @flow: index.html's inline script applies the stored choice before first paint; ThemeSetting (Navbar, Profile) changes it through useTheme
import { useSyncExternalStore } from 'react'

/*
 * The theme is a setting with three values. System follows the operating system and keeps
 * following it while the page is open; Light and Dark pin one theme. The choice is kept in this
 * browser only (it is a property of the screen, not of the account), and stored as a bare string
 * so the inline script in index.html can read it without parsing anything.
 *
 * Keep THEME_STORAGE_KEY and the resolution rule in step with that script: theme.test.ts runs
 * the script itself and checks both agree.
 */

/** The person's choice: follow the system, or pin light or dark. */
export type ThemeSetting = 'system' | 'light' | 'dark'
/** The theme actually in force after the system choice is resolved. */
export type Theme = 'light' | 'dark'

/**
 * localStorage key of the setting; shared with the inline script in index.html, so the two must stay in step.
 */
export const THEME_STORAGE_KEY = 'aiwos.theme'
/** The settings in the order menus list them. */
export const THEME_SETTINGS: readonly ThemeSetting[] = ['system', 'light', 'dark']
/** Media query that is true when the operating system prefers a dark colour scheme. */
const DARK_QUERY = '(prefers-color-scheme: dark)'

/** Type guard for a stored value. */
export function isThemeSetting(value: unknown): value is ThemeSetting {
  return value === 'system' || value === 'light' || value === 'dark'
}

// @find: read theme setting, stored theme
/**
 * Reads the saved setting from localStorage; returns "system" when nothing valid is saved or storage is blocked.
 */
export function readThemeSetting(): ThemeSetting {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY)
    return isThemeSetting(stored) ? stored : 'system'
  } catch {
    return 'system'
  }
}

/** Saves the setting (system is stored as no value at all); ignores blocked storage. */
function writeThemeSetting(setting: ThemeSetting): void {
  try {
    // System is the default, so it is stored as nothing at all.
    if (setting === 'system') localStorage.removeItem(THEME_STORAGE_KEY)
    else localStorage.setItem(THEME_STORAGE_KEY, setting)
  } catch {
    // Storage blocked: the choice holds for this visit and is simply not remembered.
  }
}

/** Whether the operating system currently prefers dark; false when matchMedia is unavailable. */
function systemPrefersDark(): boolean {
  try {
    return typeof window.matchMedia === 'function' && window.matchMedia(DARK_QUERY).matches
  } catch {
    return false
  }
}

/**
 * Turns a setting into a concrete theme: system becomes dark or light by the second argument, the others are returned as they are.
 */
export function resolveTheme(setting: ThemeSetting, prefersDark: boolean): Theme {
  if (setting === 'system') return prefersDark ? 'dark' : 'light'
  return setting
}

// @find: apply theme, set data-theme, color-scheme
/**
 * Resolves the setting and writes it to the root element as data-theme and color-scheme. Returns the theme applied. The root defaults to the document element.
 */
export function applyTheme(setting: ThemeSetting, root: HTMLElement = document.documentElement): Theme {
  const theme = resolveTheme(setting, systemPrefersDark())
  root.dataset.theme = theme
  root.style.colorScheme = theme
  return theme
}

/*
 * One store for the setting, so the navbar menu and the Profile page always agree, and a change
 * made in another tab arrives here through the storage event.
 */
/** Subscribers to the shared setting store. */
const listeners = new Set<() => void>()
/** The cached setting; null until first read from storage. */
let current: ThemeSetting | null = null

/** Current setting, read from storage on first use and cached after that. */
function getSetting(): ThemeSetting {
  if (current === null) current = readThemeSetting()
  return current
}

/** Store subscription for useSyncExternalStore; returns the unsubscribe function. */
function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** Tells every subscriber that the setting changed. */
function notify(): void {
  for (const listener of listeners) listener()
}

// @find: set theme setting, change theme
/**
 * Changes the theme: updates the cache, saves it, applies it to the page and notifies subscribers, so every control (navbar menu, Profile page) updates together.
 */
export function setThemeSetting(setting: ThemeSetting): void {
  current = setting
  writeThemeSetting(setting)
  applyTheme(setting)
  notify()
}

/*
 * Called once from main.tsx. Under System, follows the operating system when it switches (at
 * sunset, say) without a reload; and picks up a choice made in another tab. Returns a cleanup.
 */
// @find: start theme sync, follow system theme, theme across tabs
/**
 * Starts keeping the page in step with its sources: applies the current setting, follows operating system changes while the setting is system, and follows changes made in other tabs through the storage event. Called once from main.tsx; returns the cleanup that removes both listeners.
 */
export function startThemeSync(): () => void {
  applyTheme(getSetting())
  const query = typeof window.matchMedia === 'function' ? window.matchMedia(DARK_QUERY) : null
  const onSystemChange = () => {
    if (getSetting() === 'system') applyTheme('system')
  }
  const onStorage = (event: StorageEvent) => {
    if (event.key !== null && event.key !== THEME_STORAGE_KEY) return
    current = readThemeSetting()
    applyTheme(current)
    notify()
  }
  query?.addEventListener?.('change', onSystemChange)
  window.addEventListener('storage', onStorage)
  return () => {
    query?.removeEventListener?.('change', onSystemChange)
    window.removeEventListener('storage', onStorage)
  }
}

/** Test seam: forget the cached setting so the next read goes back to storage. */
export function resetThemeStoreForTests(): void {
  current = null
}

// @find: use theme hook
/**
 * Hook returning the current setting and setThemeSetting, for the theme picker controls. Re-renders on any change, including one from another tab.
 */
export function useTheme(): [ThemeSetting, (setting: ThemeSetting) => void] {
  const setting = useSyncExternalStore(subscribe, getSetting, getSetting)
  return [setting, setThemeSetting]
}

/** Words shown for each setting in menus. */
export const THEME_LABELS: Record<ThemeSetting, string> = {
  system: 'System',
  light: 'Light',
  dark: 'Dark',
}
