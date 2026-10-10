// @find: tests for chat queries, earlier messages paging, merge thread, slid out messages, lowest position, show earlier messages
// @what: Tests for how earlier pages merge with the live conversation window without gaps or duplicates.
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { lowestPosition, mergeThread, slidOut, useEarlierPages } from './chatQueries'
import type { ChatMessage } from './queries'

/*
 * A long conversation read past its live window: messages that slide out stay, earlier pages are
 * anchored to whatever is the oldest message loaded at the time, and nothing shows twice.
 */

function message(id: string, position: number): ChatMessage {
  return {
    id,
    position,
    authorKind: 'user',
    authorId: 'user-1',
    agentId: null,
    kind: 'text',
    content: `Message ${position}`,
    detail: {},
    goalId: null,
    createdAt: '2026-10-01T00:00:00Z',
  }
}

/** Messages at positions from..to, inclusive, with ids m<position>. */
function range(from: number, to: number): ChatMessage[] {
  return Array.from({ length: to - from + 1 }, (_, index) => message(`m${from + index}`, from + index))
}

const positions = (messages: readonly ChatMessage[]) => messages.map((m) => m.position)

describe('slidOut, mergeThread and lowestPosition', () => {
  it('keeps what fell off the front of the window', () => {
    expect(positions(slidOut(range(10, 14), range(12, 16)))).toEqual([10, 11])
    expect(slidOut([], range(12, 16))).toEqual([])
    expect(slidOut(range(10, 14), [])).toEqual([])
  })

  it('merges sources in position order, each message once, the later source winning', () => {
    const live = { ...message('m3', 3), content: 'Live copy' }
    const merged = mergeThread([message('m3', 3), message('m1', 1)], [live, message('m4', 4)])
    expect(positions(merged)).toEqual([1, 3, 4])
    expect(merged[1]!.content).toBe('Live copy')
  })

  it('finds the oldest position loaded', () => {
    expect(lowestPosition([message('a', 9), message('b', 4)])).toBe(4)
    expect(lowestPosition([])).toBeNull()
  })
})

describe('useEarlierPages', () => {
  let requested: string[]
  let pages: Record<number, { messages: ChatMessage[]; hasEarlier: boolean }>

  beforeEach(() => {
    requested = []
    pages = {}
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : input.toString()
        requested.push(url)
        const before = Number(new URL(url, 'http://localhost').searchParams.get('before'))
        const page = pages[before] ?? { messages: [], hasEarlier: false }
        return Promise.resolve(
          new Response(JSON.stringify(page), { status: 200, headers: { 'Content-Type': 'application/json' } }),
        )
      }),
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function renderPages(initial: { id: string | null; live: ChatMessage[]; hasEarlier: boolean }) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children)
    return renderHook(({ id, live, hasEarlier }) => useEarlierPages(id, live, hasEarlier), { initialProps: initial, wrapper })
  }

  it('shows the live window as it is until anything older is loaded', () => {
    const live = range(100, 104)
    const { result } = renderPages({ id: 'c1', live, hasEarlier: true })
    expect(positions(result.current.messages)).toEqual([100, 101, 102, 103, 104])
    expect(result.current.hasEarlier).toBe(true)
  })

  it('keeps messages that slid out of the live window on screen', () => {
    const { result, rerender } = renderPages({ id: 'c1', live: range(100, 104), hasEarlier: true })
    rerender({ id: 'c1', live: range(102, 106), hasEarlier: true })
    expect(positions(result.current.messages)).toEqual([100, 101, 102, 103, 104, 105, 106])
  })

  it('loads the page before the oldest message loaded, merged without duplicates', async () => {
    pages[100] = { messages: [...range(97, 99), message('m100', 100)], hasEarlier: true }
    const { result } = renderPages({ id: 'c1', live: range(100, 104), hasEarlier: true })

    await act(async () => {
      await result.current.loadEarlier()
    })

    expect(requested).toEqual(['/api/conversations/c1/messages?before=100&limit=100'])
    expect(positions(result.current.messages)).toEqual([97, 98, 99, 100, 101, 102, 103, 104])
    expect(result.current.hasEarlier).toBe(true)
  })

  it('anchors the next page to the oldest message now loaded, even after the window moved on', async () => {
    pages[100] = { messages: range(97, 99), hasEarlier: true }
    pages[97] = { messages: range(95, 96), hasEarlier: false }
    const { result, rerender } = renderPages({ id: 'c1', live: range(100, 104), hasEarlier: true })

    await act(async () => {
      await result.current.loadEarlier()
    })
    // The window slides forward past what was loaded: nothing in between goes missing.
    rerender({ id: 'c1', live: range(103, 107), hasEarlier: true })
    expect(positions(result.current.messages)).toEqual([97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107])

    await act(async () => {
      await result.current.loadEarlier()
    })
    expect(requested[1]).toBe('/api/conversations/c1/messages?before=97&limit=100')
    expect(positions(result.current.messages)[0]).toBe(95)
    expect(result.current.hasEarlier).toBe(false)

    // Nothing earlier is left, so nothing more is asked for.
    let page: unknown = 'not called'
    await act(async () => {
      page = await result.current.loadEarlier()
    })
    expect(page).toBeNull()
    expect(requested).toHaveLength(2)
  })

  it('pages back several times in a row before rendering, each from where the last stopped', async () => {
    pages[100] = { messages: range(98, 99), hasEarlier: true }
    pages[98] = { messages: range(96, 97), hasEarlier: true }
    const { result } = renderPages({ id: 'c1', live: range(100, 104), hasEarlier: true })

    await act(async () => {
      await result.current.loadEarlier()
      await result.current.loadEarlier()
    })
    expect(requested).toEqual([
      '/api/conversations/c1/messages?before=100&limit=100',
      '/api/conversations/c1/messages?before=98&limit=100',
    ])
    expect(positions(result.current.messages)[0]).toBe(96)
  })

  it('knows there is nothing before position 0 without asking', () => {
    const { result, rerender } = renderPages({ id: 'c1', live: range(0, 4), hasEarlier: false })
    // The window moves on, but everything from the first message is still on screen.
    rerender({ id: 'c1', live: range(3, 7), hasEarlier: true })
    expect(positions(result.current.messages)[0]).toBe(0)
    expect(result.current.hasEarlier).toBe(false)
  })

  it('starts again for another conversation', async () => {
    pages[100] = { messages: range(98, 99), hasEarlier: true }
    const { result, rerender } = renderPages({ id: 'c1', live: range(100, 104), hasEarlier: true })
    await act(async () => {
      await result.current.loadEarlier()
    })

    const other = [message('x0', 0), message('x1', 1)]
    rerender({ id: 'c2', live: other, hasEarlier: false })
    expect(positions(result.current.messages)).toEqual([0, 1])
    expect(result.current.hasEarlier).toBe(false)
  })

  it('lets the caller wrap the change, to keep the reader in place', async () => {
    pages[100] = { messages: range(98, 99), hasEarlier: false }
    const { result } = renderPages({ id: 'c1', live: range(100, 104), hasEarlier: true })
    const apply = vi.fn((update: () => void) => update())

    await act(async () => {
      await result.current.loadEarlier(apply)
    })
    expect(apply).toHaveBeenCalledTimes(1)
    expect(positions(result.current.messages)[0]).toBe(98)
  })
})
