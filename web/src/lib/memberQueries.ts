// @find: team members, invite member, invitations, revoke invitation, resend invitation, accept invitation, password reset link, grant role, who can assign roles, owner role, Members page, Accept invite page, canGrantRole, grantableRoles, canManageMember, GrantGuard, DELETE /api/orgs/{id}/invitations/{invitationId}, POST /api/orgs/{id}/invitations, POST /api/users/{id}/password-reset-link, POST /api/invitations/accept-signed-in
// @what: Rules for who may hand out which role, plus the invitation and password-reset actions of the Members page.
// @flow: Used by routes/Members.tsx and routes/AcceptInvite.tsx; mirrors the identity service rule (GrantGuard).
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError, api } from './api'
import { enterWorkspace } from './accountQueries'
import type { Invitation, Member, Role } from './queries'

/*
 * Who may hand out which role, and the member and invitation actions built on that.
 *
 * The rule is the identity service's (GrantGuard): nobody gives a role carrying a permission they
 * do not hold, and only an owner gives the owner role or changes or removes an owner. The console
 * applies the same rule only to decide what to offer, so nobody is shown a choice that can only be
 * refused. The server stays the authority, and it also counts a demotion since sign-in.
 */

/** The system role above every other, as the server names it. */
export const OWNER_ROLE = 'owner'

/** The person handing out authority, as far as the rule needs: what they hold, and their role. */
export type Grantor = { permissions: readonly string[]; role: string | null }

/** A one-time link to choose a new password, as POST /api/users/{id}/password-reset-link returns it. */
export type ResetLink = { url: string; expiresAt: string }

/** What accepting an invitation returns: who joined which workspace, at which role. */
export type AcceptedInvitation = { userId: string; orgId: string; email: string; roleName: string }

const isOwnerRole = (role: Pick<Role, 'name' | 'system'>) => role.system && role.name === OWNER_ROLE

/**
 * Whether `grantor` holds every one of `codes`: the test for building a role from them, or for
 * starting a new role from one that carries them.
 */
export function holdsAll(codes: readonly string[], grantor: Grantor): boolean {
  const held = new Set(grantor.permissions)
  return codes.every((code) => held.has(code))
}

// @find: can grant role, may assign role, role permission check, owner only gives owner, Members page role picker
/** Whether `grantor` may give `role` to someone, themselves included. */
export function canGrantRole(role: Pick<Role, 'name' | 'system' | 'permissions'>, grantor: Grantor): boolean {
  if (isOwnerRole(role) && grantor.role !== OWNER_ROLE) return false
  return holdsAll(role.permissions, grantor)
}

// @find: roles to offer, grantable roles, role dropdown choices, Members page invite dialog
/** The roles `grantor` may give, in the order given. */
export function grantableRoles<R extends Pick<Role, 'name' | 'system' | 'permissions'>>(roles: readonly R[], grantor: Grantor): R[] {
  return roles.filter((role) => canGrantRole(role, grantor))
}

// @find: can change or remove member, manage member rule, owner protection, Members page
/**
 * Whether `grantor` may change or remove `member`.
 *
 * An owner only by an owner. Anyone else only by someone holding everything the member's role
 * carries; when that role is not known here (the roles list is not readable), the server decides.
 */
export function canManageMember(
  member: Pick<Member, 'role'>,
  roleByName: Readonly<Record<string, Pick<Role, 'permissions'> | undefined>>,
  grantor: Grantor,
): boolean {
  if (member.role === OWNER_ROLE) return grantor.role === OWNER_ROLE
  const role = roleByName[member.role]
  return role ? holdsAll(role.permissions, grantor) : true
}

// @find: account already exists, accept invitation error, sign in to accept, Accept invite page
/**
 * Whether accepting failed because the invited address already has an account. The person then
 * signs in and accepts as themselves instead of registering.
 */
export function isAccountExists(error: unknown): boolean {
  return error instanceof ApiError && (error.code === 'account_exists' || error.fields.reason === 'account_exists')
}

/** Where "Sign in to accept" goes: sign-in, then straight back to finish accepting this invitation. */
export function signInToAcceptPath(token: string): string {
  return `/sign-in?next=${encodeURIComponent(`/accept-invite?token=${encodeURIComponent(token)}&accept=1`)}`
}

/** A link the server gave as a path, made whole so it still works when pasted elsewhere. */
export function absoluteLink(url: string, origin: string = window.location.origin): string {
  return url.startsWith('/') ? `${origin}${url}` : url
}

// @find: revoke invitation, cancel invite, withdraw invitation, delete invitation; route: DELETE /api/orgs/{orgId}/invitations/{invitationId}; used by: Members page
/** Withdraws an invitation, so its link stops working at once. */
export function useRevokeInvitation(orgId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (invitationId: string) =>
      api<Invitation>(`/api/orgs/${orgId}/invitations/${encodeURIComponent(invitationId)}`, { method: 'DELETE' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['invitations', orgId] }),
  })
}

// @find: resend invitation, send invite again, new invite link; route: POST /api/orgs/{orgId}/invitations; used by: Members page
/**
 * Sends an invitation again: a new one to the same address and role, which replaces any that is
 * still open. The response carries the new link, shown once.
 */
export function useResendInvitation(orgId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (invitation: Pick<Invitation, 'invitationId' | 'email' | 'roleName'>) =>
      api<Invitation>(`/api/orgs/${orgId}/invitations`, {
        method: 'POST',
        body: { email: invitation.email, roleName: invitation.roleName },
      }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['invitations', orgId] }),
  })
}

// @find: password reset link, reset member password, one-time link; route: POST /api/users/{id}/password-reset-link; used by: Members page
/** Creates a one-time link a member can use to choose a new password. Only the newest link works. */
export function usePasswordResetLink() {
  return useMutation({
    mutationFn: (userId: string) =>
      api<ResetLink>(`/api/users/${encodeURIComponent(userId)}/password-reset-link`, { method: 'POST' }),
  })
}

// @find: accept invitation signed in, join workspace, accept invite; route: POST /api/invitations/accept-signed-in; used by: Accept invite page (AcceptInvite.tsx)
/**
 * Accepts an invitation as the account already signed in, then opens the workspace it joined.
 *
 * `entered` is false when joining worked but the session could not be moved to the workspace; the
 * person is a member either way, and signing in again opens it.
 */
export function useAcceptInvitationSignedIn() {
  return useMutation({
    mutationFn: async (token: string): Promise<AcceptedInvitation & { entered: boolean }> => {
      const accepted = await api<AcceptedInvitation>('/api/invitations/accept-signed-in', {
        method: 'POST',
        body: { token },
      })
      try {
        await enterWorkspace(accepted.orgId)
        return { ...accepted, entered: true }
      } catch {
        return { ...accepted, entered: false }
      }
    },
  })
}
