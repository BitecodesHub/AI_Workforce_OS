// @find: tests for hotkeys, keyboard shortcuts, useHotkeys, formatHotkey
// @what: Unit tests for keyboard shortcuts.
import { renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { formatHotkey, isMac, modLabel, useHotkeys } from './hotkeys'
import type { Hotkey } from './hotkeys'

function press(target: EventTarget, key: string, init: KeyboardEventInit = {}) {
  target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init }))
}

afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

describe('isMac and modLabel', () => {
  it('reads Cmd on a Mac platform and Ctrl elsewhere', () => {
    vi.spyOn(navigator, 'platform', 'get').mockReturnValue('MacIntel')
    expect(isMac()).toBe(true)
    expect(modLabel()).toBe('Cmd')

    vi.spyOn(navigator, 'platform', 'get').mockReturnValue('Win32')
    expect(isMac()).toBe(false)
    expect(modLabel()).toBe('Ctrl')
  })
})

describe('useHotkeys', () => {
  it('fires the handler only when the modifier matches', () => {
    const handler = vi.fn()
    const bindings: Hotkey[] = [{ key: 'k', mod: true, handler }]
    renderHook(() => useHotkeys(bindings))

    press(window, 'k')
    expect(handler).not.toHaveBeenCalled()

    press(window, 'k', { metaKey: true })
    expect(handler).toHaveBeenCalledTimes(1)

    press(window, 'k', { ctrlKey: true })
    expect(handler).toHaveBeenCalledTimes(2)
  })

  it('ignores a typing target unless allowInInput is set', () => {
    const input = document.createElement('input')
    document.body.appendChild(input)

    const quiet = vi.fn()
    const loud = vi.fn()
    renderHook(() =>
      useHotkeys([
        { key: 'k', mod: true, handler: quiet },
        { key: 'k', mod: true, allowInInput: true, handler: loud },
      ]),
    )

    press(input, 'k', { metaKey: true })
    expect(quiet).not.toHaveBeenCalled()
    expect(loud).toHaveBeenCalledTimes(1)
  })

  it('does nothing while disabled', () => {
    const handler = vi.fn()
    renderHook(() => useHotkeys([{ key: 'k', mod: true, handler }], false))
    press(window, 'k', { metaKey: true })
    expect(handler).not.toHaveBeenCalled()
  })

  it('stops listening once unmounted', () => {
    const handler = vi.fn()
    const { unmount } = renderHook(() => useHotkeys([{ key: 'k', mod: true, handler }]))
    unmount()
    press(window, 'k', { metaKey: true })
    expect(handler).not.toHaveBeenCalled()
  })
})

describe('formatHotkey', () => {
  it('lists the modifiers in reading order, then the key in capitals', () => {
    vi.spyOn(navigator, 'platform', 'get').mockReturnValue('Win32')
    expect(formatHotkey({ key: 'k', mod: true, shift: true, handler: () => {} })).toEqual(['Ctrl', 'Shift', 'K'])
  })

  it('keeps a named key, such as Enter, as given', () => {
    expect(formatHotkey({ key: 'Enter', mod: true, handler: () => {} })[1]).toBe('Enter')
  })
})
