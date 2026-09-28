import { describe, expect, it } from 'vitest'
import { SCHEDULE_EXAMPLES, scheduleDebounceKey, scheduleStatus } from './scheduleModel'

describe('scheduleStatus', () => {
  it('reads an enabled schedule as Active, whatever its paused reason once said', () => {
    expect(scheduleStatus({ enabled: true, pausedReason: null })).toEqual({ tone: 'success', label: 'Active' })
    expect(scheduleStatus({ enabled: true, pausedReason: 'Paused after 3 failed runs' })).toEqual({
      tone: 'success',
      label: 'Active',
    })
  })

  it('reads a disabled schedule with no reason as plain Paused', () => {
    expect(scheduleStatus({ enabled: false, pausedReason: null })).toEqual({ tone: 'neutral', label: 'Paused' })
    expect(scheduleStatus({ enabled: false, pausedReason: '' })).toEqual({ tone: 'neutral', label: 'Paused' })
  })

  it('reads a disabled schedule with a reason as Paused after failures, carrying the reason', () => {
    expect(scheduleStatus({ enabled: false, pausedReason: 'Paused after 3 failed runs' })).toEqual({
      tone: 'warning',
      label: 'Paused after failures',
      note: 'Paused after 3 failed runs',
    })
  })

  it('trims a reason before deciding whether one was given', () => {
    expect(scheduleStatus({ enabled: false, pausedReason: '   ' })).toEqual({ tone: 'neutral', label: 'Paused' })
  })
})

describe('SCHEDULE_EXAMPLES', () => {
  it('offers five distinct example phrases', () => {
    expect(SCHEDULE_EXAMPLES).toHaveLength(5)
    expect(new Set(SCHEDULE_EXAMPLES).size).toBe(5)
  })
})

describe('scheduleDebounceKey', () => {
  it('is the same for text that differs only in surrounding or repeated whitespace', () => {
    expect(scheduleDebounceKey('every weekday at 9am')).toBe(scheduleDebounceKey('  every   weekday at 9am  '))
  })

  it('is the same regardless of letter case', () => {
    expect(scheduleDebounceKey('Every Weekday at 9am')).toBe(scheduleDebounceKey('every weekday at 9am'))
  })

  it('differs once the words actually differ', () => {
    expect(scheduleDebounceKey('every weekday at 9am')).not.toBe(scheduleDebounceKey('every weekday at 10am'))
  })

  it('is empty for text that is only whitespace', () => {
    expect(scheduleDebounceKey('   ')).toBe('')
  })
})
