// @find: tests for requester label, who asked, You, Someone, member directory, UNKNOWN_REQUESTER
// @what: Unit tests for naming the person who requested a goal.
// @flow: Exercises requesterLabel in shared.ts
import { describe, expect, it } from 'vitest'
import type { Member } from '../../lib/queries'
import { requesterLabel, UNKNOWN_REQUESTER } from './shared'

const ava: Member = { userId: 'u-ava', displayName: 'Ava Owner', email: 'ava@example.test', role: 'owner', status: 'active' }
const loaded = { members: { [ava.userId]: ava }, loaded: true }
const loading = { members: {}, loaded: false }

describe('requesterLabel', () => {
  it('says You for the viewer, even before the member list loads', () => {
    expect(requesterLabel({ source: 'chat', requestedBy: 'u-me' }, loading, 'u-me')).toBe('You')
  })

  it('names a member from the loaded list', () => {
    expect(requesterLabel({ source: 'manual', requestedBy: 'u-ava' }, loaded, 'u-me')).toBe('Ava Owner')
  })

  it('says Former member only when the loaded list genuinely lacks the id', () => {
    expect(requesterLabel({ source: 'manual', requestedBy: 'u-gone' }, loaded, 'u-me')).toBe('Former member')
  })

  it('never claims a former member while the list is loading, failed or not readable', () => {
    expect(requesterLabel({ source: 'manual', requestedBy: 'u-ava' }, loading, 'u-me')).toBe(UNKNOWN_REQUESTER)
  })

  it('reads a scheduled goal as Scheduled, and a missing requester as Someone', () => {
    expect(requesterLabel({ source: 'schedule', requestedBy: 'u-ava' }, loaded, 'u-me')).toBe('Scheduled')
    expect(requesterLabel({ source: 'manual', requestedBy: null }, loaded, 'u-me')).toBe(UNKNOWN_REQUESTER)
  })
})
