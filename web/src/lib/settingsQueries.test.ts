// @find: tests for settings, time zones, validateWorkspaceForm, validateNotifications, timeZoneChoices, workspace name, webhook, Settings page
// @what: Unit tests for the time zone helpers and the settings form validation.
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  SECRET_MAX,
  SECRET_MIN,
  WORKSPACE_NAME_MAX,
  browserTimeZone,
  isKnownTimeZone,
  timeInZone,
  timeZoneChoices,
  validateNotifications,
  validateWorkspaceForm,
} from './settingsQueries'

afterEach(() => vi.restoreAllMocks())

describe('time zones', () => {
  it('lists the zones the browser knows, with UTC, sorted and without repeats', () => {
    const zones = timeZoneChoices()

    expect(zones.length).toBeGreaterThan(100)
    expect(zones).toContain('Australia/Melbourne')
    expect(zones).toContain('UTC')
    expect(new Set(zones).size).toBe(zones.length)
    expect(zones).toEqual([...zones].sort((a, b) => a.localeCompare(b)))
  })

  it('always includes the zones asked for, so the current value is a choice', () => {
    const zones = timeZoneChoices('Pacific/Made_Up', null, undefined)
    expect(zones).toContain('Pacific/Made_Up')
    expect(isKnownTimeZone('Pacific/Made_Up', zones)).toBe(true)
  })

  it('falls back to a short list when the browser cannot list its zones', () => {
    vi.spyOn(Intl, 'supportedValuesOf').mockImplementation(() => {
      throw new RangeError('unsupported')
    })
    const zones = timeZoneChoices()
    expect(zones).toContain('Australia/Melbourne')
    expect(zones).toContain('UTC')
    expect(zones.length).toBeLessThan(100)
  })

  it('defaults to the browser zone, or UTC when it will not say', () => {
    expect(browserTimeZone()).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone)
    vi.spyOn(Intl, 'DateTimeFormat').mockImplementation(() => {
      throw new Error('no formatter')
    })
    expect(browserTimeZone()).toBe('UTC')
  })

  it('accepts only a zone that is exactly one of the choices', () => {
    const zones = timeZoneChoices()
    expect(isKnownTimeZone('Australia/Melbourne', zones)).toBe(true)
    expect(isKnownTimeZone('australia/melbourne', zones)).toBe(false)
    expect(isKnownTimeZone('Melbourne', zones)).toBe(false)
    expect(isKnownTimeZone('', zones)).toBe(false)
  })

  it('shows the time in a zone, and nothing for a zone it cannot format', () => {
    expect(timeInZone('Australia/Melbourne', new Date('2026-10-04T00:00:00Z'))).toMatch(/\d/)
    expect(timeInZone('Not/AZone')).toBeNull()
  })
})

describe('validateWorkspaceForm', () => {
  const zones = timeZoneChoices()

  it('passes a name and a listed zone', () => {
    expect(validateWorkspaceForm({ name: 'Acme Operations', timezone: 'Australia/Melbourne' }, zones)).toEqual({})
  })

  it('refuses a name that is empty or only spaces', () => {
    for (const name of ['', '   ']) {
      expect(validateWorkspaceForm({ name, timezone: 'UTC' }, zones).name).toBe('Enter a name for the workspace.')
    }
  })

  it('refuses a name longer than the service accepts, counted after trimming', () => {
    const long = 'x'.repeat(WORKSPACE_NAME_MAX + 1)
    expect(validateWorkspaceForm({ name: long, timezone: 'UTC' }, zones).name).toContain(String(WORKSPACE_NAME_MAX))
    expect(validateWorkspaceForm({ name: ` ${'x'.repeat(WORKSPACE_NAME_MAX)} `, timezone: 'UTC' }, zones).name).toBeUndefined()
  })

  it('refuses a zone that is not in the list, in words', () => {
    const errors = validateWorkspaceForm({ name: 'Acme', timezone: 'Nowhere/Land' }, zones)
    expect(errors.timezone).toBe('Choose a time zone from the list, such as Australia/Melbourne.')
    expect(errors.name).toBeUndefined()
  })
})

describe('validateNotifications', () => {
  const base = { webhookUrl: 'https://example.com/hook', secret: '', removeSecret: false, events: ['approvalRaised'] }

  it('passes a complete address with an event ticked', () => {
    expect(validateNotifications(base)).toEqual({})
  })

  it('allows an empty address, which turns notifications off, whatever else is set', () => {
    expect(validateNotifications({ ...base, webhookUrl: '', events: [] })).toEqual({})
    expect(validateNotifications({ ...base, webhookUrl: '   ', events: [] })).toEqual({})
  })

  it('refuses text that is not a web address', () => {
    for (const webhookUrl of ['hooks.example.com', 'ftp://example.com/x', 'https://', 'https://has space.com']) {
      expect(validateNotifications({ ...base, webhookUrl }).webhookUrl).toContain('https://')
    }
  })

  it('checks the secret against the service’s limits, and only while it is being set', () => {
    expect(validateNotifications({ ...base, secret: 'x'.repeat(SECRET_MIN - 1) }).secret).toContain(String(SECRET_MIN))
    expect(validateNotifications({ ...base, secret: 'x'.repeat(SECRET_MAX + 1) }).secret).toContain(String(SECRET_MAX))
    expect(validateNotifications({ ...base, secret: 'x'.repeat(SECRET_MIN) }).secret).toBeUndefined()
    expect(validateNotifications({ ...base, secret: 'short', removeSecret: true }).secret).toBeUndefined()
  })

  it('asks for at least one event when there is an address to send to', () => {
    expect(validateNotifications({ ...base, events: [] }).events).toContain('at least one event')
  })
})
