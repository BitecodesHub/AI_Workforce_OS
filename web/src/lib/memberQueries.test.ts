import { describe, expect, it } from 'vitest'
import { ApiError } from './api'
import {
  absoluteLink,
  canGrantRole,
  canManageMember,
  grantableRoles,
  holdsAll,
  isAccountExists,
  signInToAcceptPath,
  type Grantor,
} from './memberQueries'

/* The grant rule as the console applies it, to decide which choices to offer. */

const ALL = ['workspace:read', 'workspace:delete', 'member:invite', 'member:update', 'agent:read', 'chat:use']

const owner = { name: 'owner', system: true, permissions: ALL }
const admin = { name: 'admin', system: true, permissions: ALL.filter((code) => code !== 'workspace:delete') }
const employee = { name: 'employee', system: true, permissions: ['workspace:read', 'chat:use'] }
const inviter = { name: 'inviter', system: false, permissions: ['workspace:read', 'member:invite'] }

const asOwner: Grantor = { permissions: owner.permissions, role: 'owner' }
const asAdmin: Grantor = { permissions: admin.permissions, role: 'admin' }
const asInviter: Grantor = { permissions: inviter.permissions, role: 'inviter' }

describe('canGrantRole', () => {
  it('offers the owner role only to an owner', () => {
    expect(canGrantRole(owner, asOwner)).toBe(true)
    expect(canGrantRole(owner, asAdmin)).toBe(false)
    // Even an account holding every permission is not an owner unless it holds the owner role.
    expect(canGrantRole(owner, { permissions: ALL, role: 'admin' })).toBe(false)
  })

  it('offers a role only when every permission it carries is held', () => {
    expect(canGrantRole(admin, asAdmin)).toBe(true)
    expect(canGrantRole(employee, asAdmin)).toBe(true)
    expect(canGrantRole(admin, asInviter)).toBe(false)
    expect(canGrantRole(employee, asInviter)).toBe(false)
    expect(canGrantRole(inviter, asInviter)).toBe(true)
  })

  it('treats a workspace role named owner as an ordinary role', () => {
    expect(canGrantRole({ name: 'owner', system: false, permissions: ['workspace:read'] }, asInviter)).toBe(true)
  })
})

describe('grantableRoles', () => {
  it('keeps the order and drops what cannot be given', () => {
    expect(grantableRoles([owner, admin, employee, inviter], asAdmin).map((role) => role.name)).toEqual([
      'admin',
      'employee',
      'inviter',
    ])
    expect(grantableRoles([owner, admin, employee], asOwner)).toHaveLength(3)
  })
})

describe('canManageMember', () => {
  const roleByName = { owner, admin, employee, inviter }

  it('leaves an owner to owners', () => {
    expect(canManageMember({ role: 'owner' }, roleByName, asAdmin)).toBe(false)
    expect(canManageMember({ role: 'owner' }, roleByName, asOwner)).toBe(true)
  })

  it('needs every permission the member holds, and lets the server decide when the role is unknown', () => {
    expect(canManageMember({ role: 'admin' }, roleByName, asInviter)).toBe(false)
    expect(canManageMember({ role: 'employee' }, roleByName, asAdmin)).toBe(true)
    expect(canManageMember({ role: 'auditor' }, roleByName, asInviter)).toBe(true)
    expect(canManageMember({ role: 'admin' }, {}, asInviter)).toBe(true)
  })
})

describe('holdsAll', () => {
  it('is true only when every code is held', () => {
    expect(holdsAll(['chat:use'], asAdmin)).toBe(true)
    expect(holdsAll(['chat:use', 'workspace:delete'], asAdmin)).toBe(false)
    expect(holdsAll([], asInviter)).toBe(true)
  })
})

describe('isAccountExists', () => {
  it('recognises the refusal for an address that already has an account', () => {
    expect(isAccountExists(new ApiError(409, 'already_exists', 'Sign in.', false, { reason: 'account_exists' }))).toBe(true)
    expect(isAccountExists(new ApiError(409, 'account_exists', 'Sign in.', false, {}))).toBe(true)
    expect(isAccountExists(new ApiError(409, 'conflict', 'Withdrawn.', false, { reason: 'revoked' }))).toBe(false)
    expect(isAccountExists(new Error('account_exists'))).toBe(false)
  })
})

describe('signInToAcceptPath', () => {
  it('returns to the invitation afterwards, marked to finish accepting', () => {
    const path = signInToAcceptPath('a-b_c')
    expect(path.startsWith('/sign-in?next=')).toBe(true)
    expect(new URLSearchParams(path.slice('/sign-in?'.length)).get('next')).toBe('/accept-invite?token=a-b_c&accept=1')
  })
})

describe('absoluteLink', () => {
  it('makes a path whole and leaves a full address alone', () => {
    expect(absoluteLink('/sign-in?reset=abc', 'https://console.example')).toBe('https://console.example/sign-in?reset=abc')
    expect(absoluteLink('https://elsewhere.example/x', 'https://console.example')).toBe('https://elsewhere.example/x')
  })
})
