import React from 'react'
import {
  Button,
  Card,
  ConfirmDialog,
  DataTable,
  Dialog,
  Eyebrow,
  Input,
  Notice,
  PageHeader,
  Select,
  Tag,
  Textarea,
  Time,
} from '../components/ui'
import { QueryState } from '../components/ui/QueryState'
// Imported from its own module, not the ../components/ui barrel: this screen is lazy-loaded, and
// the barrel is also part of the main bundle, so going through it created a circular chunk
// dependency (Rollup warned of a "broken execution order").
import { FilterBar, FilterEmpty } from '../components/ui/FilterBar'
import { ApiError, describeApiError } from '../lib/api'
import { formatCount, sentenceCase } from '../lib/format'
import { roleLabel, statusLabel } from '../lib/labels'
import { useToast } from '../lib/toast'
import { useListFilter } from '../lib/useListFilter'
import { useNow } from '../lib/useNow'
import {
  useCreateRole,
  useDeleteRoleMutation,
  useInviteMember,
  useInvitations,
  useMembers,
  usePermissionCatalogue,
  useRemoveMember,
  useRoles,
  useUpdateMemberRole,
  useUpdateRoleMutation,
} from '../lib/queries'
import { ROLE_CHANGE_DELAY_COPY, can, profile } from '../lib/session'
import type { Column } from '../components/ui'
import type { Invitation, Member, PermissionInfo, Role } from '../lib/queries'

/*
 * Members and roles.
 *
 * Who belongs to the workspace, the links that let someone join, and the roles that decide what
 * each person can do. Every control is shown only to a role that can use it; everyone else sees
 * the same lists, read-only.
 *
 * Timing matters here and is stated plainly. A role change, a new role composition and a removal
 * all reach a signed-in person when their session renews (ROLE_CHANGE_DELAY_COPY), not at once.
 */

const OWNER_RULE = 'A workspace must keep at least one owner. The last owner cannot be demoted or removed.'

const ROLE_FIELD_LABELS = { name: 'Role name', description: 'Description', permissions: 'Permissions' }
const INVITE_FIELD_LABELS = { email: 'Email address', roleName: 'Role' }

/** Built-in roles first, most powerful first; a workspace's own roles after them, by name. */
const ROLE_RANK: Record<string, number> = { owner: 0, admin: 1, manager: 2, employee: 3, viewer: 4 }
const byRoleRank = (a: string, b: string) =>
  (ROLE_RANK[a] ?? 99) - (ROLE_RANK[b] ?? 99) || roleLabel(a).localeCompare(roleLabel(b))

const MEMBER_FACETS = { role: (member: Member) => member.role }
const memberText = (member: Member) => `${member.displayName} ${member.email} ${roleLabel(member.role)}`

const withFullStop = (text: string) => (/[.?]$/.test(text) ? text : `${text}.`)
const asSentence = (text: string) => withFullStop(text.charAt(0).toUpperCase() + text.slice(1))

function isPast(iso: string | null | undefined, now: number): boolean {
  if (!iso) return false
  const time = new Date(iso).getTime()
  return !Number.isNaN(time) && time <= now
}

/** A pending invitation whose deadline has passed is expired, whatever its stored status says. */
function invitationStatus(invitation: Invitation, now: number): string {
  const status = invitation.status.toLowerCase()
  return status === 'pending' && isPast(invitation.expiresAt, now) ? 'expired' : status
}

function InvitationTiming({ invitation, now }: { invitation: Invitation; now: number }) {
  const status = invitationStatus(invitation, now)
  if (status === 'accepted') {
    return (
      <span className="muted">
        Accepted{invitation.acceptedAt ? <> <Time iso={invitation.acceptedAt} /></> : null}
      </span>
    )
  }
  if (status === 'pending') {
    return (
      <span className="muted">
        Expires <Time iso={invitation.expiresAt} />
      </span>
    )
  }
  if (status === 'expired') {
    return (
      <span className="muted">
        Expired <Time iso={invitation.expiresAt} />
      </span>
    )
  }
  return <span className="muted">—</span>
}

/**
 * A one-time invitation link with a way to copy it. Where the clipboard is unavailable or
 * refused, the link is selected instead so it can be copied from the keyboard. The result is said
 * beside the button: a toast would sit behind the dialog's backdrop.
 */
function InviteLink({ url, inputId }: { url: string; inputId: string }) {
  const [copyState, setCopyState] = React.useState<'idle' | 'copied' | 'manual'>('idle')

  const copy = async () => {
    try {
      if (!navigator.clipboard) throw new Error('Clipboard unavailable')
      await navigator.clipboard.writeText(url)
      setCopyState('copied')
    } catch {
      const input = document.getElementById(inputId)
      if (input instanceof HTMLInputElement) {
        input.focus()
        input.select()
      }
      setCopyState('manual')
    }
  }

  return (
    <div className="stack" style={{ gap: 'var(--space-2)' }}>
      <div className="row" style={{ gap: 'var(--space-3)', alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <div style={{ flex: '1 1 240px', minWidth: 0 }}>
          <Input
            id={inputId}
            label="Invitation link"
            value={url}
            readOnly
            className="input mono"
            onFocus={(event) => event.currentTarget.select()}
          />
        </div>
        <Button variant="outline" onClick={() => void copy()}>
          Copy link
        </Button>
      </div>
      <p className="caption" role="status">
        {copyState === 'copied' && 'Link copied.'}
        {copyState === 'manual' && 'The link is selected. Copy it with your keyboard.'}
      </p>
    </div>
  )
}

/** The permission catalogue in groups by resource, in the catalogue's own order. */
function groupPermissions(catalogue: PermissionInfo[]): Array<[string, PermissionInfo[]]> {
  const groups = new Map<string, PermissionInfo[]>()
  for (const permission of catalogue) {
    const group = groups.get(permission.resource)
    if (group) group.push(permission)
    else groups.set(permission.resource, [permission])
  }
  return [...groups.entries()]
}

function PermissionPicker({
  catalogue,
  selected,
  onChange,
}: {
  catalogue: PermissionInfo[] | undefined
  selected: string[]
  onChange: (next: string[]) => void
}) {
  const hintId = React.useId()
  const groups = React.useMemo(() => groupPermissions(catalogue ?? []), [catalogue])
  const toggle = (code: string, on: boolean) =>
    onChange(on ? [...selected, code] : selected.filter((existing) => existing !== code))

  return (
    <fieldset className="field" style={{ minWidth: 0 }} aria-describedby={selected.length === 0 ? hintId : undefined}>
      <legend className="field-label">
        Permissions <span className="field-optional">({formatCount(selected.length)} selected)</span>
      </legend>
      {catalogue === undefined ? (
        <p className="muted">Loading the permissions…</p>
      ) : (
        <div className="stack permission-list">
          {groups.map(([resource, permissions]) => (
            <div key={resource} className="stack permission-group">
              <p className="caption">{sentenceCase(resource)}</p>
              {permissions.map((permission) => (
                <label
                  key={permission.code}
                  title={permission.code}
                  style={{ display: 'flex', alignItems: 'flex-start', gap: 'var(--space-2)' }}
                >
                  <input
                    type="checkbox"
                    checked={selected.includes(permission.code)}
                    onChange={(event) => toggle(permission.code, event.target.checked)}
                    style={{ marginTop: '2px' }}
                  />
                  <span>{permission.description}</span>
                  {permission.administrative && <Tag tone="warning">Administrative</Tag>}
                </label>
              ))}
            </div>
          ))}
        </div>
      )}
      {selected.length === 0 && (
        <p className="caption field-hint" id={hintId}>
          Choose at least one permission.
        </p>
      )}
    </fieldset>
  )
}

type RoleDraft = {
  mode: 'create' | 'edit'
  roleId: string | null
  name: string
  description: string
  permissions: string[]
  /** The role a new one was started from, or '' for a blank role. */
  startFrom: string
}

const copyName = (name: string) => `${name}-copy`.slice(0, 60)

function roleErrorMessage(error: unknown, action: 'save' | 'delete'): string {
  if (error instanceof ApiError) {
    if (error.code === 'already_exists') return 'A role with that name already exists.'
    if (error.code === 'immutable_resource') {
      return action === 'delete'
        ? 'Built-in roles cannot be deleted.'
        : 'Built-in roles cannot be edited. Copy it to start a role of your own.'
    }
    if (error.code === 'resource_in_use') return 'Somebody still holds this role. Reassign them first.'
  }
  return describeApiError(error, ROLE_FIELD_LABELS)
}

function memberErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.code === 'last_owner_protected') return OWNER_RULE
  return describeApiError(error, { roleName: 'New role' })
}

export function Members() {
  const me = profile()
  const myId = me?.userId ?? null
  const orgId = me?.workspaceId ?? ''
  const toast = useToast()
  const now = useNow()

  const canInvite = can('member:invite')
  const canUpdateMember = can('member:update')
  const canRemoveMember = can('member:remove')
  const canManageMembers = canUpdateMember || canRemoveMember
  const canReadRoles = can('role:read')
  const canCreateRole = can('role:create')
  const canUpdateRole = can('role:update')
  const canDeleteRole = can('role:delete')
  const canActOnRoles = canCreateRole || canUpdateRole || canDeleteRole

  const membersQuery = useMembers()
  // Each list is requested only by a role allowed to read it, so nobody else makes a request that
  // can only be refused.
  const rolesQuery = useRoles({ enabled: canReadRoles })
  const roles = rolesQuery.data
  const { data: catalogue } = usePermissionCatalogue({ enabled: canCreateRole || canUpdateRole })
  const invitationsQuery = useInvitations(orgId, { enabled: canInvite })

  const createRole = useCreateRole()
  const updateRole = useUpdateRoleMutation()
  const deleteRole = useDeleteRoleMutation()
  const updateMemberRole = useUpdateMemberRole()
  const removeMember = useRemoveMember()

  const filter = useListFilter({ rows: membersQuery.data, text: memberText, facets: MEMBER_FACETS })

  // ---- Invitations --------------------------------------------------------------------------
  const [inviteOpen, setInviteOpen] = React.useState(false)
  const [inviteEmail, setInviteEmail] = React.useState('')
  const [inviteRoleName, setInviteRoleName] = React.useState('')
  const [inviteError, setInviteError] = React.useState<string | null>(null)
  const [inviteFieldErrors, setInviteFieldErrors] = React.useState<{ email?: string | undefined; roleName?: string | undefined }>({})
  const [createdInvitation, setCreatedInvitation] = React.useState<Invitation | null>(null)
  // The last link created on this visit, kept on the page after its dialog closes so a stray
  // Escape or click outside the dialog cannot lose a link that is never shown again.
  const [keptInvitation, setKeptInvitation] = React.useState<Invitation | null>(null)
  const dialogLinkId = React.useId()
  const pageLinkId = React.useId()
  const inviteMember = useInviteMember(orgId, inviteEmail.trim(), inviteRoleName)

  // ---- People -------------------------------------------------------------------------------
  const [changing, setChanging] = React.useState<Member | null>(null)
  const [newRoleName, setNewRoleName] = React.useState('')
  const [changeError, setChangeError] = React.useState<string | null>(null)
  const [removing, setRemoving] = React.useState<Member | null>(null)
  const [removeError, setRemoveError] = React.useState<string | null>(null)

  // ---- Roles --------------------------------------------------------------------------------
  const [roleDraft, setRoleDraft] = React.useState<RoleDraft | null>(null)
  const [roleError, setRoleError] = React.useState<string | null>(null)
  const [deletingRole, setDeletingRole] = React.useState<Role | null>(null)
  const [deleteError, setDeleteError] = React.useState<string | null>(null)

  // When the link replaces the form, focus moves to it, selected, ready to copy.
  React.useEffect(() => {
    if (!createdInvitation) return
    const input = document.getElementById(dialogLinkId)
    if (input instanceof HTMLInputElement) {
      input.focus()
      input.select()
    }
  }, [createdInvitation, dialogLinkId])

  const sortedRoles = React.useMemo(() => [...(roles ?? [])].sort((a, b) => byRoleRank(a.name, b.name)), [roles])
  const roleByName = React.useMemo(() => Object.fromEntries((roles ?? []).map((role) => [role.name, role])), [roles])

  const memberColumns = React.useMemo<Column<Member>[]>(() => {
    const columns: Column<Member>[] = [
      {
        key: 'displayName',
        header: 'Name',
        sortValue: (row) => row.displayName,
        render: (row) => (
          <span>
            {row.displayName}
            {row.userId === myId && <span className="muted"> (you)</span>}
          </span>
        ),
      },
      { key: 'email', header: 'Email', sortValue: (row) => row.email, render: (row) => <span className="mono">{row.email}</span> },
      {
        key: 'role',
        header: 'Role',
        sortValue: (row) => ROLE_RANK[row.role] ?? 99,
        render: (row) => <Tag tone="blue" title={roleLabel(row.role)}>{roleLabel(row.role)}</Tag>,
      },
      {
        key: 'status',
        header: 'Status',
        render: (row) => {
          const status = statusLabel('member', row.status)
          return <Tag tone={status.tone}>{status.label}</Tag>
        },
      },
      {
        key: 'joinedAt',
        header: 'Joined',
        sortValue: (row) => row.joinedAt ?? null,
        render: (row) => <Time iso={row.joinedAt} className="muted" />,
      },
      {
        key: 'lastSignInAt',
        header: 'Last sign-in',
        sortValue: (row) => row.lastSignInAt ?? null,
        render: (row) => (row.lastSignInAt ? <Time iso={row.lastSignInAt} className="muted" /> : <span className="muted">Never</span>),
      },
    ]
    if (canUpdateMember || canRemoveMember) {
      columns.push({
        key: 'actions',
        header: 'Actions',
        render: (row) => (
          <div className="row action-group">
            {canUpdateMember && (
              <Button
                variant="outline"
                className="button-sm"
                aria-label={`Change role for ${row.displayName}`}
                onClick={() => {
                  setChanging(row)
                  setNewRoleName(row.role)
                  setChangeError(null)
                }}
              >
                Change role
              </Button>
            )}
            {canRemoveMember && row.status !== 'removed' && (
              <Button
                variant="danger"
                className="button-sm"
                aria-label={row.userId === myId ? 'Remove yourself' : `Remove ${row.displayName}`}
                onClick={() => {
                  setRemoving(row)
                  setRemoveError(null)
                }}
              >
                Remove
              </Button>
            )}
          </div>
        ),
      })
    }
    return columns
  }, [canUpdateMember, canRemoveMember, myId])

  const roleColumns = React.useMemo<Column<Role>[]>(() => {
    const columns: Column<Role>[] = [
      { key: 'name', header: 'Role', render: (row) => <span title={row.name}>{roleLabel(row.name)}</span> },
      { key: 'description', header: 'Description', render: (row) => <span className="muted">{row.description || '—'}</span> },
      {
        key: 'system',
        header: 'Type',
        render: (row) => <Tag tone={row.system ? 'neutral' : 'blue'}>{row.system ? 'Built-in' : 'Custom'}</Tag>,
      },
      { key: 'permissions', header: 'Permissions', numeric: true, render: (row) => formatCount(row.permissions.length) },
      { key: 'holders', header: 'Held by', numeric: true, render: (row) => formatCount(row.holders) },
    ]
    if (canCreateRole || canUpdateRole || canDeleteRole) {
      columns.push({
        key: 'actions',
        header: 'Actions',
        render: (row) => {
          const name = roleLabel(row.name)
          return (
            <div className="row action-group">
              {canUpdateRole && !row.system && (
                <Button
                  variant="outline"
                  className="button-sm"
                  aria-label={`Edit role ${name}`}
                  onClick={() => {
                    setRoleDraft({
                      mode: 'edit',
                      roleId: row.id,
                      name: row.name,
                      description: row.description,
                      permissions: [...row.permissions],
                      startFrom: '',
                    })
                    setRoleError(null)
                  }}
                >
                  Edit
                </Button>
              )}
              {canCreateRole && (
                <Button
                  variant="outline"
                  className="button-sm"
                  aria-label={`Copy role ${name}`}
                  onClick={() => {
                    setRoleDraft({
                      mode: 'create',
                      roleId: null,
                      name: copyName(row.name),
                      description: row.description,
                      permissions: [...row.permissions],
                      startFrom: row.id,
                    })
                    setRoleError(null)
                  }}
                >
                  Copy
                </Button>
              )}
              {canDeleteRole && !row.system && (
                <Button
                  variant="danger"
                  className="button-sm"
                  aria-label={`Delete role ${name}`}
                  onClick={() => {
                    setDeletingRole(row)
                    setDeleteError(null)
                  }}
                >
                  Delete
                </Button>
              )}
            </div>
          )
        },
      })
    }
    return columns
  }, [canCreateRole, canUpdateRole, canDeleteRole])

  const invitationColumns = React.useMemo<Column<Invitation>[]>(
    () => [
      { key: 'email', header: 'Email', render: (row) => <span className="mono">{row.email}</span> },
      { key: 'roleName', header: 'Role', render: (row) => <Tag tone="blue">{roleLabel(row.roleName)}</Tag> },
      {
        key: 'status',
        header: 'Status',
        render: (row) => {
          const status = statusLabel('invitation', invitationStatus(row, now))
          return <Tag tone={status.tone} title={status.label}>{status.label}</Tag>
        },
      },
      { key: 'expiresAt', header: 'When', render: (row) => <InvitationTiming invitation={row} now={now} /> },
    ],
    [now],
  )

  // ---- Handlers -----------------------------------------------------------------------------

  const openInvite = () => {
    setCreatedInvitation(null)
    setInviteError(null)
    setInviteFieldErrors({})
    setInviteOpen(true)
  }

  const closeInvite = () => {
    // A link that was created stays on the page, in the invitations card, until it is hidden.
    if (createdInvitation) setKeptInvitation(createdInvitation)
    setInviteOpen(false)
    setCreatedInvitation(null)
    setInviteEmail('')
    setInviteRoleName('')
    setInviteError(null)
    setInviteFieldErrors({})
  }

  const handleInvite = async (event: React.FormEvent) => {
    event.preventDefault()
    setInviteError(null)
    setInviteFieldErrors({})
    try {
      const invitation = await inviteMember.mutateAsync()
      setCreatedInvitation(invitation)
      setKeptInvitation(null)
    } catch (err) {
      const fields = err instanceof ApiError ? err.fields : {}
      const email = fields.email ? asSentence(fields.email) : undefined
      const roleName = fields.roleName ? asSentence(fields.roleName) : undefined
      setInviteFieldErrors({ email, roleName })
      setInviteError(email || roleName ? null : describeApiError(err, INVITE_FIELD_LABELS))
    }
  }

  const closeChange = () => {
    setChanging(null)
    setChangeError(null)
  }

  const handleChangeRole = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!changing || !newRoleName || newRoleName === changing.role) return
    setChangeError(null)
    try {
      await updateMemberRole.mutateAsync({ userId: changing.userId, roleName: newRoleName })
      toast.success(`Role updated. ${ROLE_CHANGE_DELAY_COPY}`)
      closeChange()
    } catch (err) {
      setChangeError(memberErrorMessage(err))
    }
  }

  const closeRemove = () => {
    setRemoving(null)
    setRemoveError(null)
  }

  const handleRemoveMember = async () => {
    if (!removing) return
    setRemoveError(null)
    try {
      await removeMember.mutateAsync(removing.userId)
      toast.success(removing.userId === myId ? 'You were removed from this workspace.' : `${removing.displayName} was removed.`)
      closeRemove()
    } catch (err) {
      setRemoveError(memberErrorMessage(err))
    }
  }

  const openCreateRole = () => {
    setRoleDraft({ mode: 'create', roleId: null, name: '', description: '', permissions: [], startFrom: '' })
    setRoleError(null)
  }

  const closeRoleDraft = () => {
    setRoleDraft(null)
    setRoleError(null)
  }

  const changeStartFrom = (roleId: string) => {
    setRoleDraft((draft) => {
      if (!draft) return draft
      const source = roles?.find((role) => role.id === roleId)
      if (!source) return { ...draft, startFrom: '', permissions: draft.startFrom ? [] : draft.permissions }
      return {
        ...draft,
        startFrom: roleId,
        name: draft.name.trim() ? draft.name : copyName(source.name),
        description: draft.description.trim() ? draft.description : source.description,
        permissions: [...source.permissions],
      }
    })
  }

  const rolePending = createRole.isPending || updateRole.isPending
  const roleDraftValid = Boolean(roleDraft && roleDraft.name.trim() && roleDraft.permissions.length > 0)

  const handleSaveRole = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!roleDraft || !roleDraftValid) return
    setRoleError(null)
    const input = {
      name: roleDraft.name.trim(),
      description: roleDraft.description.trim(),
      permissions: roleDraft.permissions,
    }
    try {
      if (roleDraft.mode === 'edit' && roleDraft.roleId) {
        await updateRole.mutateAsync({ id: roleDraft.roleId, ...input })
        toast.success(`Role updated. ${ROLE_CHANGE_DELAY_COPY}`)
      } else {
        await createRole.mutateAsync(input)
        toast.success(`Role ${input.name} created. Give it to someone with Change role, or when you invite them.`)
      }
      closeRoleDraft()
    } catch (err) {
      setRoleError(roleErrorMessage(err, 'save'))
    }
  }

  const closeDeleteRole = () => {
    setDeletingRole(null)
    setDeleteError(null)
  }

  const handleDeleteRole = async () => {
    if (!deletingRole) return
    setDeleteError(null)
    try {
      await deleteRole.mutateAsync(deletingRole.id)
      toast.success(`Role ${roleLabel(deletingRole.name)} deleted.`)
      closeDeleteRole()
    } catch (err) {
      setDeleteError(roleErrorMessage(err, 'delete'))
    }
  }

  // ---- Derived for the dialogs --------------------------------------------------------------

  const changingSelf = changing !== null && changing.userId === myId
  const roleUnchanged = changing !== null && newRoleName === changing.role
  const chosenRole = roleByName[newRoleName]
  const inviteRole = roleByName[inviteRoleName]
  const removingSelf = removing !== null && removing.userId === myId
  const editedRole = roleDraft?.roleId ? roles?.find((role) => role.id === roleDraft.roleId) : undefined
  const deletingHolders = deletingRole?.holders ?? 0

  const roleOptions = sortedRoles.map((role) => (
    <option key={role.id} value={role.name}>
      {roleLabel(role.name)}
    </option>
  ))

  const facetRoles = React.useMemo(
    () => [...new Set((membersQuery.data ?? []).map((member) => member.role))].sort(byRoleRank),
    [membersQuery.data],
  )

  return (
    <div className="page admin-members">
      <PageHeader
        eyebrow="Who can do what"
        title="Members and roles"
        description={`Roles are composed from permissions. ${ROLE_CHANGE_DELAY_COPY}`}
        action={canInvite ? <Button onClick={openInvite}>Invite someone</Button> : undefined}
      />

      {canManageMembers && <Notice tone="info">{OWNER_RULE}</Notice>}

      <section style={{ marginTop: 'var(--space-6)' }}>
        <Card as="section">
          <Eyebrow as="h2">People</Eyebrow>
          <QueryState
            query={membersQuery}
            permission="member:read"
            what="the member list"
            isEmpty={(members) => members.length === 0}
            empty={<p className="muted">No members found.</p>}
            rows={6}
          >
            {() => (
              <>
                <FilterBar
                  searchLabel="Search people"
                  placeholder="Name or email"
                  query={filter.query}
                  onQueryChange={filter.setQuery}
                  facets={[
                    {
                      param: 'role',
                      label: 'Role',
                      options: facetRoles.map((role) => ({
                        value: role,
                        label: roleLabel(role),
                        count: filter.counts.role?.[role] ?? 0,
                      })),
                      selected: filter.selected.role ?? [],
                      onToggle: (value) => filter.toggle('role', value),
                    },
                  ]}
                  shown={filter.filtered.length}
                  total={filter.total}
                  active={filter.active}
                  onClear={filter.clear}
                />
                {filter.filtered.length === 0 ? (
                  <FilterEmpty onClear={filter.clear} what="members" />
                ) : (
                  <DataTable
                    columns={memberColumns}
                    rows={filter.filtered}
                    getKey={(row) => row.userId}
                    caption="Members of this workspace and the role each one holds."
                  />
                )}
              </>
            )}
          </QueryState>
        </Card>
      </section>

      {canInvite && (
        <section style={{ marginTop: 'var(--space-6)' }}>
          <Card as="section">
            <Eyebrow as="h2">Invitations</Eyebrow>
            {keptInvitation?.acceptUrl && (
              <div className="stack" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-5)' }}>
                <Notice tone="success">
                  The link for {keptInvitation.email} stays here until you leave this page. It is not
                  shown again after that.
                </Notice>
                <InviteLink url={keptInvitation.acceptUrl} inputId={pageLinkId} />
                <div>
                  <Button variant="quiet" className="button-sm" onClick={() => setKeptInvitation(null)}>
                    Hide the link
                  </Button>
                </div>
              </div>
            )}
            <QueryState
              query={invitationsQuery}
              permission="member:invite"
              what="the invitations list"
              isEmpty={(rows) => rows.length === 0}
              empty={<p className="muted">No invitations yet. Invite someone to create a link for them.</p>}
              rows={3}
            >
              {(rows) => (
                <DataTable
                  columns={invitationColumns}
                  rows={rows}
                  getKey={(row) => row.invitationId}
                  caption="Invitations created in this workspace, newest first, with where each one stands."
                />
              )}
            </QueryState>
          </Card>
        </section>
      )}

      {canReadRoles && (
        <section style={{ marginTop: 'var(--space-6)' }}>
          <Card as="section">
            <Eyebrow as="h2">Roles</Eyebrow>
            <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
              Built-in roles cannot be edited.{canCreateRole ? ' Copy one to start a role of your own.' : ''}
            </p>
            <QueryState
              query={rolesQuery}
              permission="role:read"
              what="the roles list"
              isEmpty={(rows) => rows.length === 0}
              empty={<p className="muted">No roles configured.</p>}
              rows={4}
            >
              {() => (
                <>
                  <DataTable
                    columns={roleColumns}
                    rows={sortedRoles}
                    getKey={(row) => row.id}
                    caption="Roles available in this workspace, with the number of permissions each carries and how many people hold it."
                  />
                  {canCreateRole && (
                    <div style={{ marginTop: 'var(--space-5)', display: 'flex', gap: 'var(--space-3)', flexWrap: 'wrap' }}>
                      <Button onClick={openCreateRole}>Create role</Button>
                    </div>
                  )}
                  {!canActOnRoles && (
                    <p className="caption" style={{ marginTop: 'var(--space-4)' }}>
                      Only owners and admins can create, edit or delete roles.
                    </p>
                  )}
                </>
              )}
            </QueryState>
          </Card>
        </section>
      )}

      <Dialog
        open={inviteOpen}
        onClose={closeInvite}
        eyebrow="Invite someone"
        title={createdInvitation ? 'Send this link' : 'Invite a member'}
        description={
          createdInvitation
            ? undefined
            : 'The platform does not send email. This creates a one-time link that you copy and send yourself.'
        }
        dismissible={!inviteMember.isPending}
        // A stray click beside the dialog must not throw away a link that is shown only once.
        closeOnBackdrop={!createdInvitation}
        error={inviteError}
        footer={
          createdInvitation ? (
            <Button variant="primary" onClick={closeInvite}>
              Done
            </Button>
          ) : (
            <>
              <Button variant="outline" onClick={closeInvite} disabled={inviteMember.isPending}>
                Cancel
              </Button>
              <Button
                variant="primary"
                type="submit"
                form="invite-form"
                loading={inviteMember.isPending}
                disabled={!inviteEmail.trim() || !inviteRoleName}
              >
                Create invitation
              </Button>
            </>
          )
        }
      >
        {createdInvitation ? (
          <div className="stack" style={{ gap: 'var(--space-4)' }}>
            <Notice tone="success" live>
              Invitation created for {createdInvitation.email} as {roleLabel(createdInvitation.roleName)}.
              Send them this link. It is not shown again once you leave this page.
            </Notice>
            {createdInvitation.acceptUrl ? (
              <InviteLink url={createdInvitation.acceptUrl} inputId={dialogLinkId} />
            ) : (
              <p className="muted">The platform did not return a link for this invitation. Create it again.</p>
            )}
            <p className="caption">
              The link works once and expires <Time iso={createdInvitation.expiresAt} />.
            </p>
          </div>
        ) : (
          <form id="invite-form" onSubmit={handleInvite} className="stack" style={{ gap: 'var(--space-4)' }}>
            <Input
              label="Email address"
              type="email"
              value={inviteEmail}
              onChange={(event) => {
                setInviteEmail(event.target.value)
                setInviteFieldErrors((errors) => ({ ...errors, email: undefined }))
              }}
              required
              maxLength={320}
              autoComplete="off"
              error={inviteFieldErrors.email}
              hint="Inviting an address that already has an open invitation replaces that invitation."
            />
            <Select
              label="Role"
              value={inviteRoleName}
              onChange={(event) => {
                setInviteRoleName(event.target.value)
                setInviteFieldErrors((errors) => ({ ...errors, roleName: undefined }))
              }}
              required
              error={inviteFieldErrors.roleName}
              hint={inviteRole?.description || undefined}
            >
              <option value="" disabled>
                Choose a role…
              </option>
              {roleOptions}
            </Select>
          </form>
        )}
      </Dialog>

      <Dialog
        open={changing !== null}
        onClose={closeChange}
        eyebrow="Change role"
        title={changingSelf ? 'Change your own role' : `Change ${changing?.displayName ?? ''}'s role`}
        description={ROLE_CHANGE_DELAY_COPY}
        dismissible={!updateMemberRole.isPending}
        error={changeError}
        footer={
          <>
            <Button variant="outline" onClick={closeChange} disabled={updateMemberRole.isPending}>
              Cancel
            </Button>
            <Button
              variant="primary"
              type="submit"
              form="change-role-form"
              loading={updateMemberRole.isPending}
              disabled={roleUnchanged || !newRoleName}
            >
              Save role
            </Button>
          </>
        }
      >
        <form id="change-role-form" onSubmit={handleChangeRole} className="stack" style={{ gap: 'var(--space-4)' }}>
          {changing?.role === 'owner' && <Notice tone="info">{OWNER_RULE}</Notice>}
          {changingSelf && (
            <Notice tone="warning">
              This is your own role. If the new role cannot change members' roles, you will not be
              able to change it back yourself.
            </Notice>
          )}
          <Select
            label="New role"
            value={newRoleName}
            onChange={(event) => setNewRoleName(event.target.value)}
            hint={roleUnchanged ? 'Choose a different role.' : chosenRole?.description || undefined}
          >
            {roleOptions}
          </Select>
        </form>
      </Dialog>

      <ConfirmDialog
        open={removing !== null}
        onClose={closeRemove}
        onConfirm={handleRemoveMember}
        eyebrow="Remove member"
        title={removingSelf ? 'Remove yourself?' : `Remove ${removing?.displayName ?? ''}?`}
        description={
          removingSelf
            ? 'You lose access to this workspace within 15 minutes, when your session renews.'
            : 'They lose access to this workspace within 15 minutes, when their session renews. The record of their membership is kept.'
        }
        confirmLabel={removingSelf ? 'Remove myself' : 'Remove member'}
        cancelLabel="Cancel"
        tone="danger"
        loading={removeMember.isPending}
        error={removeError}
      >
        <div className="stack" style={{ gap: 'var(--space-4)' }}>
          {removing?.role === 'owner' && <Notice tone="info">{OWNER_RULE}</Notice>}
          <p className="muted">This cannot be undone from the console.</p>
        </div>
      </ConfirmDialog>

      <Dialog
        open={roleDraft !== null}
        onClose={closeRoleDraft}
        eyebrow={roleDraft?.mode === 'edit' ? 'Edit role' : 'New role'}
        title={roleDraft?.mode === 'edit' ? `Edit ${roleLabel(editedRole?.name ?? roleDraft.name)}` : 'Create a role'}
        description={
          roleDraft?.mode === 'edit'
            ? `Changes apply to everyone who holds this role (${formatCount(editedRole?.holders ?? 0)}). ${ROLE_CHANGE_DELAY_COPY}`
            : 'Choose the permissions this role carries. Start from an existing role to adjust it rather than build it from nothing.'
        }
        dismissible={!rolePending}
        error={roleError}
        footer={
          <>
            <Button variant="outline" onClick={closeRoleDraft} disabled={rolePending}>
              Cancel
            </Button>
            <Button variant="primary" type="submit" form="role-form" loading={rolePending} disabled={!roleDraftValid}>
              {roleDraft?.mode === 'edit' ? 'Save changes' : 'Create role'}
            </Button>
          </>
        }
      >
        {roleDraft && (
          <form id="role-form" onSubmit={handleSaveRole} className="stack" style={{ gap: 'var(--space-4)' }}>
            {roleDraft.mode === 'create' && (
              <Select
                label="Start from"
                value={roleDraft.startFrom}
                onChange={(event) => changeStartFrom(event.target.value)}
              >
                <option value="">A blank role</option>
                {sortedRoles.map((role) => (
                  <option key={role.id} value={role.id}>
                    {roleLabel(role.name)}
                  </option>
                ))}
              </Select>
            )}
            <Input
              label="Role name"
              value={roleDraft.name}
              onChange={(event) => {
                const name = event.target.value
                setRoleDraft((draft) => (draft ? { ...draft, name } : draft))
              }}
              required
              maxLength={60}
              autoComplete="off"
            />
            <Textarea
              label="Description"
              optional
              value={roleDraft.description}
              onChange={(event) => {
                const description = event.target.value
                setRoleDraft((draft) => (draft ? { ...draft, description } : draft))
              }}
              rows={2}
              maxLength={300}
            />
            <PermissionPicker
              catalogue={catalogue}
              selected={roleDraft.permissions}
              onChange={(permissions) => setRoleDraft((draft) => (draft ? { ...draft, permissions } : draft))}
            />
          </form>
        )}
      </Dialog>

      <Dialog
        open={deletingRole !== null}
        onClose={closeDeleteRole}
        eyebrow="Delete role"
        title={`Delete ${roleLabel(deletingRole?.name)}?`}
        description={deletingHolders > 0 ? undefined : 'Nobody holds this role. Deleting it cannot be undone.'}
        dismissible={!deleteRole.isPending}
        error={deleteError}
        footer={
          <>
            <Button variant="outline" onClick={closeDeleteRole} disabled={deleteRole.isPending} data-autofocus>
              {deletingHolders > 0 ? 'Close' : 'Keep it'}
            </Button>
            <Button variant="danger" onClick={() => void handleDeleteRole()} loading={deleteRole.isPending} disabled={deletingHolders > 0}>
              Delete role
            </Button>
          </>
        }
      >
        {deletingHolders > 0 ? (
          <p style={{ color: 'var(--danger)' }}>
            {deletingHolders === 1 ? 'One member holds this role.' : `${formatCount(deletingHolders)} members hold this role.`}{' '}
            Reassign them first, with Change role in the People list.
          </p>
        ) : null}
      </Dialog>
    </div>
  )
}
