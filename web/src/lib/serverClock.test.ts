// @find: tests for server clock, clock skew, serverNow, noteServerDate
import { afterEach, describe, expect, it, vi } from 'vitest'
import { noteServerDate, resetServerClock, serverNow } from './serverClock'

describe('server clock', () => {
  afterEach(() => {
    resetServerClock()
    vi.useRealTimers()
  })

  it('reads the server clock when this computer runs behind, so a new task does not sit at 0 ms', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-12T09:39:21.000Z'))
    noteServerDate('Mon, 12 Oct 2026 09:39:30 GMT', Date.now() - 100, Date.now())
    expect(serverNow() - Date.now()).toBeGreaterThan(9_000)
    expect(serverNow() - Date.now()).toBeLessThan(10_000)
  })

  it('ignores a difference within the header precision, a missing header and a slow round trip', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-12T09:39:30.000Z'))
    noteServerDate('Mon, 12 Oct 2026 09:39:30 GMT', Date.now(), Date.now())
    expect(serverNow()).toBe(Date.now())
    noteServerDate(null, Date.now(), Date.now())
    noteServerDate('Mon, 12 Oct 2026 09:45:00 GMT', Date.now() - 5_000, Date.now())
    expect(serverNow()).toBe(Date.now())
  })
})
