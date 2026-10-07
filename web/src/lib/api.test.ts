import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, READ_TIMEOUT_MS, TIMEOUT_FAILURE, WRITE_TIMEOUT_MS, api } from './api'
import { clearSession, saveSession, takeRecentRenewal } from './session'

/*
 * What a request does when the service stalls or the caller walks away: a stalled read becomes the
 * error state with its retry button instead of a spinner that never ends, a write says it may still
 * have gone through, and a request the caller cancelled is a cancellation, never an error.
 */

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** A fetch that never answers, and rejects the way a real one does once its signal aborts. */
function stalledFetch() {
  return vi.fn((_path: string, init?: RequestInit) => {
    return new Promise<Response>((_resolve, reject) => {
      const signal = init?.signal
      if (!signal) return
      const stop = () => reject(signal.reason)
      if (signal.aborted) stop()
      else signal.addEventListener('abort', stop, { once: true })
    })
  })
}

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  sessionStorage.clear()
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  clearSession()
  takeRecentRenewal()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('a request that takes too long', () => {
  it('fails a read as a retryable timeout, in the words the error state shows', async () => {
    fetchMock = stalledFetch()
    vi.stubGlobal('fetch', fetchMock)

    const failure = await api('/api/runs', { timeoutMs: 15 }).catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ApiError)
    const error = failure as ApiError
    expect(error.status).toBe(0)
    expect(error.code).toBe('timeout')
    expect(error.retryable).toBe(true)
    expect(error.message).toBe('The platform is taking too long to answer.')
    expect(error.message).toBe(TIMEOUT_FAILURE)
  })

  it('adds that a write may still have gone through', async () => {
    fetchMock = stalledFetch()
    vi.stubGlobal('fetch', fetchMock)

    const failure = await api('/api/conversations/c1/messages', {
      method: 'POST',
      body: { text: 'Hello' },
      timeoutMs: 15,
    }).catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ApiError)
    expect((failure as ApiError).code).toBe('timeout')
    expect((failure as ApiError).message).toBe(
      'The platform is taking too long to answer. This may still have gone through; check before retrying.',
    )
  })

  it('stops a body that stalls after its headers arrive', async () => {
    fetchMock.mockImplementation((_path: string, init?: RequestInit) => {
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          init?.signal?.addEventListener('abort', () => controller.error(init.signal?.reason), { once: true })
        },
      })
      return Promise.resolve(new Response(body, { status: 200 }))
    })

    const failure = await api('/api/runs', { timeoutMs: 15 }).catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ApiError)
    expect((failure as ApiError).code).toBe('timeout')
  })
})

describe('a request the caller cancels', () => {
  it('is rethrown as the AbortError, not turned into an error to show', async () => {
    fetchMock = stalledFetch()
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()

    const pending = api('/api/runs', { signal: controller.signal }).catch((error: unknown) => error)
    controller.abort()
    const failure = await pending

    expect(failure).not.toBeInstanceOf(ApiError)
    expect((failure as DOMException).name).toBe('AbortError')
  })

  it('is a cancellation even when it comes after the time limit was set', async () => {
    fetchMock = stalledFetch()
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()

    const pending = api('/api/runs', { signal: controller.signal, timeoutMs: 60_000 }).catch((error: unknown) => error)
    controller.abort()

    expect(((await pending) as DOMException).name).toBe('AbortError')
  })

  it('is not mistaken for a network failure', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))

    const failure = await api('/api/runs').catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ApiError)
    expect((failure as ApiError).code).toBe('network_error')
  })

  it('hands the same signal to the second attempt after the session is renewed', async () => {
    saveSession('old-token', {
      userId: 'u1',
      workspaceId: 'w1',
      permissions: [],
      displayName: 'Maya',
      email: 'maya@demo.test',
      role: 'manager',
    })
    const signals: AbortSignal[] = []
    fetchMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/auth/refresh') {
        return Promise.resolve(json({ accessToken: 'new-token', userId: 'u1', workspaceId: 'w1', permissions: [] }))
      }
      if (init?.signal) signals.push(init.signal)
      // The first attempt is refused, the second never answers.
      return signals.length === 1 ? Promise.resolve(json({ code: 'token_expired' }, 401)) : stalledFetch()(path, init)
    })
    const controller = new AbortController()

    const pending = api('/api/runs', { signal: controller.signal }).catch((error: unknown) => error)
    await vi.waitFor(() => expect(signals).toHaveLength(2))
    expect(signals.every((signal) => !signal.aborted)).toBe(true)
    controller.abort()
    const failure = await pending

    // Both attempts hold the caller's signal, so cancelling reaches the one still in flight.
    expect(signals.every((signal) => signal.aborted)).toBe(true)
    expect((failure as DOMException).name).toBe('AbortError')
  })
})

describe('how long each kind of request may take', () => {
  function limits() {
    const timeout = vi.spyOn(AbortSignal, 'timeout')
    fetchMock.mockImplementation(() => Promise.resolve(json({})))
    return timeout
  }

  it('gives a read 20 seconds', async () => {
    const timeout = limits()
    await api('/api/runs')
    expect(timeout).toHaveBeenCalledWith(READ_TIMEOUT_MS)
    expect(READ_TIMEOUT_MS).toBe(20_000)
  })

  it('gives a write 120 seconds', async () => {
    const timeout = limits()
    await api('/api/goals', { method: 'POST', body: { title: 'Plan' } })
    expect(timeout).toHaveBeenCalledWith(WRITE_TIMEOUT_MS)
    expect(WRITE_TIMEOUT_MS).toBe(120_000)
  })

  it('gives an upload no limit', async () => {
    const timeout = limits()
    await api('/api/voice/transcriptions', { method: 'POST', form: new FormData() })
    expect(timeout).not.toHaveBeenCalled()
    expect(fetchMock.mock.calls[0]?.[1]).not.toHaveProperty('signal')
  })

  it('gives voice no limit', async () => {
    const timeout = limits()
    await api('/api/voice/voices')
    expect(timeout).not.toHaveBeenCalled()
  })

  it('lets a caller choose its own limit, or none', async () => {
    const timeout = limits()
    await api('/api/runs', { timeoutMs: 5_000 })
    await api('/api/runs', { timeoutMs: null })
    expect(timeout).toHaveBeenCalledTimes(1)
    expect(timeout).toHaveBeenCalledWith(5_000)
  })
})
