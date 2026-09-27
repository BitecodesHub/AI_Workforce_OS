import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  formatCompactTokens,
  formatCount,
  formatDate,
  formatDateTime,
  formatDuration,
  formatElapsed,
  formatMoney,
  formatRelative,
  formatRunElapsed,
  nameList,
  sentenceCase,
  shortId,
  truncateWords,
} from './format'
import { useNow } from './useNow'

/*
 * Instants are built from local date parts, so the expected strings hold in any time zone the
 * tests run in.
 */
const NOW = new Date(2026, 8, 27, 15, 4, 0).getTime()
const SECOND = 1000
const MINUTE = 60 * SECOND
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

const at = (offsetMs: number) => new Date(NOW + offsetMs).toISOString()

describe('formatDateTime and formatDate', () => {
  it('shows day, short month, year and a twelve-hour time', () => {
    expect(formatDateTime(at(0))).toBe('27 Sep 2026, 3:04 pm')
    expect(formatDate(at(0))).toBe('27 Sep 2026')
  })

  it('handles midnight and noon', () => {
    expect(formatDateTime(new Date(2026, 0, 5, 0, 7).toISOString())).toBe('5 Jan 2026, 12:07 am')
    expect(formatDateTime(new Date(2026, 11, 31, 12, 0).toISOString())).toBe('31 Dec 2026, 12:00 pm')
  })

  it('shows an em dash for nothing or nonsense', () => {
    expect(formatDateTime(null)).toBe('—')
    expect(formatDateTime(undefined)).toBe('—')
    expect(formatDateTime('')).toBe('—')
    expect(formatDate('not a date')).toBe('—')
  })
})

describe('formatRelative', () => {
  it('reads just now below 45 seconds and a minute at 45', () => {
    expect(formatRelative(at(-44 * SECOND), NOW)).toBe('just now')
    expect(formatRelative(at(-45 * SECOND), NOW)).toBe('1 minute ago')
  })

  it('rolls 59 minutes into an hour only when it gets there', () => {
    expect(formatRelative(at(-59 * MINUTE), NOW)).toBe('59 minutes ago')
    expect(formatRelative(at(-60 * MINUTE), NOW)).toBe('1 hour ago')
    expect(formatRelative(at(-3 * HOUR), NOW)).toBe('3 hours ago')
    expect(formatRelative(at(-23 * HOUR), NOW)).toBe('23 hours ago')
  })

  it('counts days up to six, then shows the date', () => {
    expect(formatRelative(at(-1 * DAY), NOW)).toBe('1 day ago')
    expect(formatRelative(at(-2 * DAY), NOW)).toBe('2 days ago')
    expect(formatRelative(at(-6 * DAY), NOW)).toBe('6 days ago')
    expect(formatRelative(at(-8 * DAY), NOW)).toBe('19 Sep 2026')
  })

  it('never calls a future time just now', () => {
    expect(formatRelative(at(30 * SECOND), NOW)).toBe('in less than a minute')
    expect(formatRelative(at(5 * MINUTE), NOW)).toBe('in 5 minutes')
    expect(formatRelative(at(3 * HOUR), NOW)).toBe('in 3 hours')
    expect(formatRelative(at(23 * HOUR), NOW)).toBe('in 23 hours')
    expect(formatRelative(at(6 * DAY), NOW)).toBe('in 6 days')
    expect(formatRelative(at(17 * DAY), NOW)).toBe('on 14 Oct 2026')
  })

  it('tolerates a server clock a few seconds ahead', () => {
    expect(formatRelative(at(5 * SECOND), NOW)).toBe('just now')
  })

  it('shows an em dash without a time', () => {
    expect(formatRelative(null, NOW)).toBe('—')
  })
})

describe('formatDuration', () => {
  it('reads zero and negative lengths as zero', () => {
    expect(formatDuration(0)).toBe('0 ms')
    expect(formatDuration(-500)).toBe('0 ms')
  })

  it('uses milliseconds, then seconds', () => {
    expect(formatDuration(850)).toBe('850 ms')
    expect(formatDuration(5234)).toBe('5.2 s')
    expect(formatDuration(12_000)).toBe('12 s')
    expect(formatDuration(59_900)).toBe('59 s')
  })

  it('uses minutes and hours with padded remainders', () => {
    expect(formatDuration(60 * SECOND)).toBe('1 min 00 s')
    expect(formatDuration(4 * MINUTE + 5 * SECOND)).toBe('4 min 05 s')
    expect(formatDuration(3600 * SECOND)).toBe('1 h 00 min')
    expect(formatDuration(2 * HOUR + 5 * MINUTE)).toBe('2 h 05 min')
    expect(formatDuration(84_817 * SECOND)).toBe('23 h 33 min')
  })

  it('uses days and hours from a day on', () => {
    expect(formatDuration(86_400 * SECOND)).toBe('1 d 0 h')
    expect(formatDuration(1969 * MINUTE + 7 * SECOND)).toBe('1 d 8 h')
    expect(formatDuration(3 * DAY + 4 * HOUR)).toBe('3 d 4 h')
  })
})

describe('formatElapsed and formatRunElapsed', () => {
  it('measures to now when there is no end', () => {
    expect(formatElapsed(at(-130 * SECOND), null, NOW)).toBe('2 min 10 s')
    expect(formatElapsed(at(-10 * MINUTE), at(-5 * MINUTE), NOW)).toBe('5 min 00 s')
    expect(formatElapsed(null, null, NOW)).toBe('—')
  })

  it('says what a run is still doing', () => {
    expect(formatRunElapsed({ status: 'running', startedAt: at(-130 * SECOND) }, NOW)).toBe('Running 2 min 10 s')
    expect(formatRunElapsed({ status: 'waiting_approval', startedAt: at(-(23 * HOUR + 5 * MINUTE)) }, NOW)).toBe(
      'Waiting 23 h 05 min',
    )
    expect(
      formatRunElapsed({ status: 'completed', startedAt: at(-90 * SECOND), completedAt: at(-30 * SECOND) }, NOW),
    ).toBe('1 min 00 s')
  })

  it('does not keep a finished run ticking when it has no end time', () => {
    expect(formatRunElapsed({ status: 'failed', startedAt: at(-HOUR), completedAt: null }, NOW)).toBe('—')
  })
})

describe('numbers', () => {
  it('groups counts in en-AU style', () => {
    expect(formatCount(1_234_567)).toBe('1,234,567')
    expect(formatCount(0)).toBe('0')
    expect(formatCount(null)).toBe('—')
  })

  it('shortens token counts to three significant figures', () => {
    expect(formatCompactTokens(950)).toBe('950')
    expect(formatCompactTokens(12_500)).toBe('12.5K')
    expect(formatCompactTokens(131_072)).toBe('131K')
    expect(formatCompactTokens(1_048_576)).toBe('1.05M')
    expect(formatCompactTokens(999_999)).toBe('1M')
  })
})

describe('formatMoney', () => {
  it('shows US dollars with cents and grouping', () => {
    expect(formatMoney(3)).toBe('US$3.00')
    expect(formatMoney(0)).toBe('US$0.00')
    expect(formatMoney(1204.5)).toBe('US$1,204.50')
  })

  it('keeps the digits of amounts under a cent', () => {
    expect(formatMoney(0.0042)).toBe('US$0.0042')
    expect(formatMoney(0.001)).toBe('US$0.001')
    expect(formatMoney(0.00001)).toBe('Under US$0.0001')
  })

  it('keeps up to four places under a dollar, so a price is never rounded to cents', () => {
    expect(formatMoney(0.075)).toBe('US$0.075')
    expect(formatMoney(0.5)).toBe('US$0.50')
    expect(formatMoney(0.12)).toBe('US$0.12')
    expect(formatMoney(0.01)).toBe('US$0.01')
    expect(formatMoney(0.1234)).toBe('US$0.1234')
    expect(formatMoney(2.075)).toBe('US$2.08')
  })

  it('shows an em dash without an amount', () => {
    expect(formatMoney(null)).toBe('—')
  })
})

describe('nameList', () => {
  it('joins names in prose and counts the rest past the limit', () => {
    expect(nameList([])).toBe('')
    expect(nameList(['A'])).toBe('A')
    expect(nameList(['A', 'B'])).toBe('A and B')
    expect(nameList(['A', 'B', 'C'])).toBe('A, B and C')
    expect(nameList(['A', 'B', 'C', 'D', 'E'])).toBe('A, B, C and 2 more')
  })
})

describe('text', () => {
  it('turns codes into sentence case', () => {
    expect(sentenceCase('waiting_approval')).toBe('Waiting approval')
    expect(sentenceCase('HALF_OPEN')).toBe('Half open')
    expect(sentenceCase('run.fail')).toBe('Run fail')
    expect(sentenceCase('toolCall')).toBe('Tool call')
    expect(sentenceCase('')).toBe('')
  })

  it('truncates at a word boundary', () => {
    expect(truncateWords('Short enough', 80)).toBe('Short enough')
    expect(truncateWords('Summarise the unread messages in the support inbox today', 30)).toBe(
      'Summarise the unread messages…',
    )
    expect(truncateWords('Supercalifragilistic', 5)).toBe('Super…')
  })

  it('shortens ids to eight characters', () => {
    expect(shortId('3f2a9c1e-7b44-4d0e-9a51-0c2d1e6f7a88')).toBe('3f2a9c1e')
    expect(shortId(null)).toBe('')
  })
})

describe('useNow', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('ticks on its interval from one shared timer, and stops on unmount', () => {
    vi.useFakeTimers({ now: NOW })
    const setIntervalSpy = vi.spyOn(globalThis, 'setInterval')
    const clearIntervalSpy = vi.spyOn(globalThis, 'clearInterval')

    const first = renderHook(() => useNow(30_000))
    const second = renderHook(() => useNow(30_000))
    expect(first.result.current).toBe(NOW)
    expect(setIntervalSpy).toHaveBeenCalledTimes(1)

    act(() => {
      vi.advanceTimersByTime(30_000)
    })
    expect(first.result.current).toBe(NOW + 30_000)
    expect(second.result.current).toBe(NOW + 30_000)

    first.unmount()
    expect(clearIntervalSpy).not.toHaveBeenCalled()
    second.unmount()
    expect(clearIntervalSpy).toHaveBeenCalledTimes(1)
  })

  it('skips ticks while the tab is hidden and catches up when it returns', () => {
    vi.useFakeTimers({ now: NOW })
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true)
    const { result, unmount } = renderHook(() => useNow(10_000))

    act(() => {
      vi.advanceTimersByTime(30_000)
    })
    expect(result.current).toBe(NOW)

    hidden.mockReturnValue(false)
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(result.current).toBe(NOW + 30_000)
    unmount()
  })
})
