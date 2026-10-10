// @find: invite teammate during setup, invite member, invite by email, send invitation link, role picker, setup wizard, Set up your workspace, InviteInline, copy invite link, useInviteMember
// @what: Inline form on the setup page that invites a person by email and role and shows the invitation link to send.
// @flow: Used by the Setup page; calls useInviteMember and grantableRoles from lib/memberQueries; same request as the Members page.
import { useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Input, Notice, Select } from '../ui'
import { CopyButton } from '../ui/CopyButton'
import { ApiError, describeApiError } from '../../lib/api'
import { roleLabel } from '../../lib/labels'
import { absoluteLink, grantableRoles } from '../../lib/memberQueries'
import { useInviteMember, useRoles } from '../../lib/queries'
import type { Invitation } from '../../lib/queries'
import { profile } from '../../lib/session'

/*
 * Inviting someone without leaving setup: an email address and a role, the same request the
 * Members page makes, and the link to send them once it is made. Only the roles this person may
 * give are offered (lib/memberQueries.ts: grantableRoles).
 */

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

// @find: invite teammate inline, invite member form, email and role, InviteInline, setup invite step
export function InviteInline() {
  const me = profile()
  const orgId = me?.workspaceId ?? ''
  const roles = useRoles()
  const [email, setEmail] = useState('')
  const [roleName, setRoleName] = useState('')
  const [emailError, setEmailError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [created, setCreated] = useState<Invitation | null>(null)

  const permissionsKey = (me?.permissions ?? []).join(' ')
  const myRole = me?.role ?? null
  const offered = useMemo(
    () => grantableRoles(roles.data ?? [], { permissions: permissionsKey ? permissionsKey.split(' ') : [], role: myRole }),
    [roles.data, permissionsKey, myRole],
  )
  // The everyday role first: most people invited during setup do the work rather than run the workspace.
  const chosenRole = roleName || offered.find((role) => role.name === 'employee')?.name || offered[0]?.name || ''
  const invite = useInviteMember(orgId, email.trim(), chosenRole)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    const address = email.trim()
    if (!EMAIL.test(address)) {
      setEmailError('Enter an email address, such as sam@example.com.')
      return
    }
    setEmailError(null)
    try {
      const invitation = await invite.mutateAsync()
      setCreated(invitation)
      setEmail('')
    } catch (failure) {
      if (failure instanceof ApiError && failure.fields.email) setEmailError(failure.fields.email)
      else setError(describeApiError(failure, { email: 'Email', roleName: 'Role' }))
    }
  }

  return (
    <div className="stack" style={{ gap: 'var(--space-4)' }}>
      <form className="setup-invite-form" onSubmit={(event) => void submit(event)} noValidate>
        <Input
          label="Email"
          type="email"
          autoComplete="off"
          value={email}
          onChange={(event) => {
            setEmail(event.target.value)
            setEmailError(null)
          }}
          error={emailError ?? undefined}
          placeholder="sam@example.com"
        />
        <Select label="Role" value={chosenRole} onChange={(event) => setRoleName(event.target.value)} disabled={offered.length === 0}>
          {offered.map((role) => (
            <option key={role.id} value={role.name}>
              {roleLabel(role.name)}
            </option>
          ))}
        </Select>
        <Button type="submit" loading={invite.isPending} disabled={!email.trim() || !chosenRole}>
          Invite
        </Button>
      </form>
      {error && (
        <Notice tone="warning" live>
          {error}
        </Notice>
      )}
      {created && (
        <Notice tone="success" live>
          <span>
            {created.email} is invited as {roleLabel(created.roleName)}.{' '}
            {created.acceptUrl
              ? 'Send them the invitation link. It is not shown again once you leave this page. '
              : 'No link came back for this invitation. Create it again on Members. '}
            {created.acceptUrl && <CopyButton text={absoluteLink(created.acceptUrl)} label="Copy the invitation link" variant="text" />}
          </span>
        </Notice>
      )}
      <p className="caption" style={{ margin: 0 }}>
        <a className="link" href="/members">
          See everyone, and change roles, on Members
        </a>
      </p>
    </div>
  )
}
