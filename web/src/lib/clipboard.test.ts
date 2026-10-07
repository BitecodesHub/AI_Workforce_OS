import { createElement } from 'react'
import type { ReactNode } from 'react'
import { act, renderHook, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { COPY_FAILED, copyText, useCopyText } from './clipboard'
import { ToastProvider } from './toast'

/* Every copy says whether it worked: a silent failure looks exactly like a success. */

function stubClipboard(writeText: ((text: string) => Promise<void>) | undefined) {
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: writeText ? { writeText } : undefined,
  })
}

afterEach(() => {
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })
})

describe('copyText', () => {
  it('resolves true once the text is on the clipboard', async () => {
    const writeText = vi.fn(async () => {})
    stubClipboard(writeText)
    await expect(copyText('hello')).resolves.toBe(true)
    expect(writeText).toHaveBeenCalledWith('hello')
  })

  it('resolves false, without throwing, when the browser refuses or has no clipboard', async () => {
    stubClipboard(async () => {
      throw new Error('Not allowed')
    })
    await expect(copyText('hello')).resolves.toBe(false)
    stubClipboard(undefined)
    await expect(copyText('hello')).resolves.toBe(false)
  })
})

describe('useCopyText', () => {
  const wrapper = ({ children }: { children: ReactNode }) => createElement(ToastProvider, null, children)

  it('confirms a copy that worked', async () => {
    stubClipboard(async () => {})
    const { result } = renderHook(() => useCopyText(), { wrapper })
    await act(async () => {
      await result.current('https://example.org/chat?c=1', 'Link copied')
    })
    expect(screen.getByText('Link copied')).toBeInTheDocument()
  })

  it('says so when a copy failed', async () => {
    stubClipboard(undefined)
    const { result } = renderHook(() => useCopyText(), { wrapper })
    let copied = true
    await act(async () => {
      copied = await result.current('text', 'Link copied')
    })
    expect(copied).toBe(false)
    expect(screen.getByText(COPY_FAILED)).toBeInTheDocument()
    expect(screen.queryByText('Link copied')).toBeNull()
  })
})
