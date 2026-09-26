import React from 'react'
import { Button, Card, DataTable, Dialog, Eyebrow, Input, Notice, PageHeader, Select, Tag, Textarea } from '../components/ui'
import { QueryState } from '../components/ui/QueryState'
import { ApiError } from '../lib/api'
import { useToast } from '../lib/toast'
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
import { can, profile } from '../lib/session'
import type { Column } from '../components/ui'
import type { Invitation, Member, Role } from '../lib/queries'

const STATUS_TONE = { active: 'success', invited: 'blue', suspended: 'warning', removed: 'neutral' } as const
const STATUS_LABEL = { active: 'Active', invited: 'Invited', suspended: 'Suspended', removed: 'Removed' } as const

const INVITATION_STATUS_TONE = { pending: 'blue', accepted: 'success', revoked: 'neutral', expired: 'warning' } as const

const ROLE_COLUMNS: Column<Role>[] = [
  { key: 'name', header: 'Role', render: (row) => <span className="mono">{row.name}</span> },
  { key: 'description', header: 'Description', render: (row) => <span className="muted">{row.description}</span> },
  { key: 'system', header: 'Built-in', render: (row) => <Tag tone={row.system ? 'neutral' : 'blue'}>{row.system ? 'Yes' : 'No'}</Tag> },
  { key: 'permissionVersion', header: 'Perm version', numeric: true, render: (row) => row.permissionVersion },
  { key: 'permissions', header: 'Permissions', numeric: true, render: (row) => row.permissions.length },
  { key: 'holders', header: 'Held by', numeric: true, render: (row) => row.holders },
]

const INVITATION_COLUMNS: Column<Invitation>[] = [
  { key: 'email', header: 'Email', render: (row) => <span className="mono">{row.email}</span> },
  { key: 'roleName', header: 'Role', render: (row) => <Tag tone="blue">{row.roleName}</Tag> },
  {
    key: 'status',
    header: 'Status',
    render: (row) => (
      <Tag tone={INVITATION_STATUS_TONE[row.status as keyof typeof INVITATION_STATUS_TONE] || 'neutral'}>
        {row.status}
      </Tag>
    ),
  },
  { key: 'expiresAt', header: 'Expires', render: (row) => <span className="muted">{timeAgo(row.expiresAt)}</span> },
]

function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '—'
  const seconds = Math.round((Date.now() - new Date(iso).getTime()) / 1000)
  if (seconds < 45) return 'just now'
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? '' : 's'} ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`
  const days = Math.round(hours / 24)
  return `${days} day${days === 1 ? '' : 's'} ago`
}

export function Members() {
  const orgId = profile()?.workspaceId ?? ''
  const { data: members, isLoading: membersLoading, error: membersError, refetch: refetchMembers } = useMembers()
  const { data: roles, isLoading: rolesLoading, error: rolesError, refetch: refetchRoles } = useRoles()
  const { data: permissions } = usePermissionCatalogue()
  const createRole = useCreateRole()
  const updateRole = useUpdateRoleMutation()
  const deleteRole = useDeleteRoleMutation()
  const { error: toastError, success: toastSuccess } = useToast()

  const canInvite = can('member:invite')
  const canUpdateMember = can('member:update')
  const canRemoveMember = can('member:remove')
  const canManageRoles = can('role:create') // Creating a role is the gate; editing/deleting check their own action.

  const { data: invitations } = useInvitations(orgId)

  const [inviteDialogOpen, setInviteDialogOpen] = React.useState(false)
  const [createRoleDialogOpen, setCreateRoleDialogOpen] = React.useState(false)
  const [editRoleDialogOpen, setEditRoleDialogOpen] = React.useState(false)
  const [deleteRoleDialogOpen, setDeleteRoleDialogOpen] = React.useState(false)
  const [roleChangeDialogOpen, setRoleChangeDialogOpen] = React.useState(false)
  const [removeMemberDialogOpen, setRemoveMemberDialogOpen] = React.useState(false)
  const [editingRole, setEditingRole] = React.useState<Role | null>(null)
  const [deletingRole, setDeletingRole] = React.useState<Role | null>(null)
  const [changingMember, setChangingMember] = React.useState<Member | null>(null)
  const [removingMember, setRemovingMember] = React.useState<Member | null>(null)
  const [newRoleName, setNewRoleName] = React.useState('')

  const [createName, setCreateName] = React.useState('')
  const [createDescription, setCreateDescription] = React.useState('')
  const [createPermissions, setCreatePermissions] = React.useState<string[]>([])

  const [editName, setEditName] = React.useState('')
  const [editDescription, setEditDescription] = React.useState('')
  const [editPermissions, setEditPermissions] = React.useState<string[]>([])

  const [inviteEmail, setInviteEmail] = React.useState('')
  const [inviteRoleName, setInviteRoleName] = React.useState('')
  const [createdInvitation, setCreatedInvitation] = React.useState<Invitation | null>(null)

  const inviteMember = useInviteMember(orgId, inviteEmail, inviteRoleName)
  const updateMemberRole = useUpdateMemberRole()
  const removeMember = useRemoveMember()

  const memberColumns: Column<Member>[] = React.useMemo(() => {
    const columns: Column<Member>[] = [
      { key: 'displayName', header: 'Name', render: (row) => row.displayName },
      { key: 'email', header: 'Email', render: (row) => <span className="mono">{row.email}</span> },
      { key: 'role', header: 'Role', render: (row) => <Tag tone="blue">{row.role}</Tag> },
      {
        key: 'status',
        header: 'Status',
        render: (row) => <Tag tone={STATUS_TONE[row.status as keyof typeof STATUS_TONE] || 'neutral'}>
          {STATUS_LABEL[row.status as keyof typeof STATUS_LABEL] || row.status}
        </Tag>,
      },
      { key: 'joinedAt', header: 'Joined', render: (row) => <span className="muted">{row.joinedAt ? timeAgo(row.joinedAt) : '—'}</span> },
      { key: 'lastSignInAt', header: 'Last sign-in', render: (row) => <span className="muted">{row.lastSignInAt ? timeAgo(row.lastSignInAt) : 'Never'}</span> },
    ]
    if (canUpdateMember || canRemoveMember) {
      columns.push({
        key: 'actions',
        header: 'Actions',
        render: (row) => (
          <div className="row" style={{ gap: 'var(--space-2)' }}>
            {canUpdateMember && (
              <Button
                variant="outline"
                onClick={() => { setChangingMember(row); setNewRoleName(row.role); setRoleChangeDialogOpen(true); }}
              >
                Change role
              </Button>
            )}
            {canRemoveMember && row.status !== 'removed' && (
              <Button variant="danger" onClick={() => { setRemovingMember(row); setRemoveMemberDialogOpen(true); }}>
                Remove
              </Button>
            )}
          </div>
        ),
      })
    }
    return columns
  }, [canUpdateMember, canRemoveMember])

  const handleInvite = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      const invitation = await inviteMember.mutateAsync()
      setCreatedInvitation(invitation)
      toastSuccess('Invitation created')
    } catch (err) {
      toastError(err instanceof ApiError ? err.message : 'Failed to create invitation')
    }
  }

  const handleChangeRole = async () => {
    if (!changingMember) return
    try {
      await updateMemberRole.mutateAsync({ userId: changingMember.userId, roleName: newRoleName })
      toastSuccess('Role updated')
      setRoleChangeDialogOpen(false)
      setChangingMember(null)
    } catch (err) {
      if (err instanceof ApiError && err.code === 'last_owner_protected') {
        toastError('A workspace must keep at least one owner')
      } else {
        toastError(err instanceof ApiError ? err.message : 'Failed to update the member’s role')
      }
    }
  }

  const handleRemoveMember = async () => {
    if (!removingMember) return
    try {
      await removeMember.mutateAsync(removingMember.userId)
      toastSuccess('Member removed')
      setRemoveMemberDialogOpen(false)
      setRemovingMember(null)
    } catch (err) {
      if (err instanceof ApiError && err.code === 'last_owner_protected') {
        toastError('A workspace must keep at least one owner')
      } else {
        toastError(err instanceof ApiError ? err.message : 'Failed to remove the member')
      }
    }
  }

  const handleCreateRole = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      await createRole.mutateAsync({ name: createName, description: createDescription, permissions: createPermissions })
      toastSuccess('Role created')
      setCreateRoleDialogOpen(false)
      setCreateName('')
      setCreateDescription('')
      setCreatePermissions([])
    } catch (err) {
      toastError(err instanceof ApiError ? err.message : 'Failed to create role')
    }
  }

  const handleUpdateRole = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!editingRole) return
    try {
      await updateRole.mutateAsync({ id: editingRole.id, name: editName, description: editDescription, permissions: editPermissions })
      toastSuccess('Role updated')
      setEditRoleDialogOpen(false)
      setEditingRole(null)
    } catch (err) {
      if (err instanceof ApiError && err.code === 'role_builtin') {
        toastError('Built-in roles cannot be edited')
      } else {
        toastError(err instanceof ApiError ? err.message : 'Failed to update role')
      }
    }
  }

  const handleDeleteRole = async () => {
    if (!deletingRole) return
    try {
      await deleteRole.mutateAsync(deletingRole.id)
      toastSuccess('Role deleted')
      setDeleteRoleDialogOpen(false)
      setDeletingRole(null)
    } catch (err) {
      if (err instanceof ApiError && err.code === 'role_has_holders') {
        toastError('Cannot delete a role that is still assigned to members')
      } else if (err instanceof ApiError && err.code === 'role_builtin') {
        toastError('Built-in roles cannot be deleted')
      } else {
        toastError(err instanceof ApiError ? err.message : 'Failed to delete role')
      }
    }
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Who can do what"
        title="Members and roles"
        description="Roles are composed from permissions and edited here. A change takes effect the next time somebody signs in."
        action={canInvite ? <Button onClick={() => { setCreatedInvitation(null); setInviteDialogOpen(true); }}>Invite someone</Button> : undefined}
      />

      <Notice tone="warning">
        A workspace must keep at least one owner. The last owner cannot be demoted or removed.
      </Notice>

      <QueryState
        query={{ data: members, isLoading: membersLoading, error: membersError, refetch: refetchMembers }}
        permission="member:read"
        what="members"
        isEmpty={(members) => members.length === 0}
        empty={
          <div style={{ marginTop: 'var(--space-6)' }}>
            <Card as="section">
              <Eyebrow>People</Eyebrow>
              <p className="muted">No members found.</p>
            </Card>
          </div>
        }
        rows={6}
      >
        {(members) => (
          <>
            <section style={{ marginTop: 'var(--space-6)' }}>
              <Card as="section">
                <Eyebrow>People</Eyebrow>
                <DataTable
                  columns={memberColumns}
                  rows={members}
                  getKey={(row) => row.userId}
                  caption="Members of this workspace and the role each one holds."
                />
              </Card>
            </section>

            {canInvite && (
              <section style={{ marginTop: 'var(--space-6)' }}>
                <Card as="section">
                  <Eyebrow>Pending invitations</Eyebrow>
                  {!invitations || invitations.length === 0 ? (
                    <p className="muted">No open invitations.</p>
                  ) : (
                    <DataTable
                      columns={INVITATION_COLUMNS}
                      rows={invitations}
                      getKey={(row) => row.invitationId}
                      caption="Invitations sent from this workspace, whether or not they have been accepted yet."
                    />
                  )}
                </Card>
              </section>
            )}

            <section style={{ marginTop: 'var(--space-6)' }}>
              <Card as="section">
                <Eyebrow>Roles</Eyebrow>
                <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
                  Built-in roles are shared by every workspace and cannot be edited. Copy one to make a
                  role of your own.
                </p>
                <QueryState
                  query={{ data: roles, isLoading: rolesLoading, error: rolesError, refetch: refetchRoles }}
                  permission="role:read"
                  what="roles"
                  isEmpty={(roles) => roles.length === 0}
                  empty={<p className="muted">No roles configured.</p>}
                  rows={4}
                >
                  {(roles) => (
                    <>
                      <DataTable
                        columns={ROLE_COLUMNS}
                        rows={roles}
                        getKey={(row) => row.id}
                        caption="Roles available in this workspace, with the number of permissions each carries."
                      />
                      {canManageRoles && (
                        <div style={{ marginTop: 'var(--space-5)', display: 'flex', gap: 'var(--space-3)', flexWrap: 'wrap' }}>
                          <Button onClick={() => setCreateRoleDialogOpen(true)}>Create role</Button>
                        </div>
                      )}
                    </>
                  )}
                </QueryState>
              </Card>
            </section>
          </>
        )}
      </QueryState>

      <Dialog
        open={inviteDialogOpen}
        onClose={() => { setInviteDialogOpen(false); setInviteEmail(''); setInviteRoleName(''); }}
        eyebrow="Invite someone"
        title="Invite a member"
        description="There is no email sending in this platform. Instead, this creates a one-time link you copy and send yourself."
        footer={
          createdInvitation ? (
            <Button variant="primary" onClick={() => { setInviteDialogOpen(false); setInviteEmail(''); setInviteRoleName(''); }}>
              Done
            </Button>
          ) : (
            <div className="dialog-footer">
              <Button variant="outline" onClick={() => setInviteDialogOpen(false)}>Cancel</Button>
              <Button
                variant="primary"
                onClick={handleInvite}
                disabled={inviteMember.isPending || !inviteEmail || !inviteRoleName}
              >
                {inviteMember.isPending ? 'Creating…' : 'Create invitation'}
              </Button>
            </div>
          )
        }
      >
        {createdInvitation ? (
          <div className="stack" style={{ gap: 'var(--space-4)' }}>
            <Notice tone="success">
              Invitation created for {createdInvitation.email}. Copy this link and send it to them - it
              will not be shown again.
            </Notice>
            <Input label="Accept-invitation link" value={createdInvitation.acceptUrl ?? ''} readOnly />
          </div>
        ) : (
          <form onSubmit={handleInvite} className="stack" style={{ gap: 'var(--space-4)' }}>
            <Input
              label="Email address"
              type="email"
              value={inviteEmail}
              onChange={(e) => setInviteEmail(e.target.value)}
              required
            />
            <Select
              label="Role"
              value={inviteRoleName}
              onChange={(e) => setInviteRoleName(e.target.value)}
              required
            >
              <option value="" disabled>Choose a role…</option>
              {roles?.map((role) => (
                <option key={role.id} value={role.name}>{role.name}</option>
              ))}
            </Select>
          </form>
        )}
      </Dialog>

      <Dialog
        open={roleChangeDialogOpen}
        onClose={() => { setRoleChangeDialogOpen(false); setChangingMember(null); }}
        eyebrow="Change role"
        title={`Change ${changingMember?.displayName ?? ''}'s role`}
        footer={
          <div className="dialog-footer">
            <Button variant="outline" onClick={() => { setRoleChangeDialogOpen(false); setChangingMember(null); }}>Cancel</Button>
            <Button variant="primary" onClick={handleChangeRole} disabled={updateMemberRole.isPending || !newRoleName}>
              {updateMemberRole.isPending ? 'Saving…' : 'Save'}
            </Button>
          </div>
        }
      >
        <Select label="New role" value={newRoleName} onChange={(e) => setNewRoleName(e.target.value)}>
          {roles?.map((role) => (
            <option key={role.id} value={role.name}>{role.name}</option>
          ))}
        </Select>
      </Dialog>

      <Dialog
        open={removeMemberDialogOpen}
        onClose={() => { setRemoveMemberDialogOpen(false); setRemovingMember(null); }}
        eyebrow="Remove member"
        title={`Remove ${removingMember?.displayName ?? ''}?`}
        description="They lose access to this workspace immediately. The record of their past membership is kept."
        footer={
          <div className="dialog-footer">
            <Button variant="outline" onClick={() => { setRemoveMemberDialogOpen(false); setRemovingMember(null); }}>Cancel</Button>
            <Button variant="danger" onClick={handleRemoveMember} disabled={removeMember.isPending}>
              {removeMember.isPending ? 'Removing…' : 'Remove member'}
            </Button>
          </div>
        }
      >
        <p className="muted">This cannot be undone from here; they would need to be invited again.</p>
      </Dialog>

      <Dialog
        open={createRoleDialogOpen}
        onClose={() => setCreateRoleDialogOpen(false)}
        eyebrow="New role"
        title="Create a custom role"
        description="Select the permissions this role should include. Built-in roles cannot be changed; create a new one instead."
        footer={
          <div className="dialog-footer">
            <Button variant="outline" onClick={() => setCreateRoleDialogOpen(false)}>Cancel</Button>
            <Button variant="primary" onClick={handleCreateRole} disabled={createRole.isPending}>
              {createRole.isPending ? 'Creating…' : 'Create role'}
            </Button>
          </div>
        }
      >
        <form onSubmit={handleCreateRole} className="stack" style={{ gap: 'var(--space-4)' }}>
          <Input label="Role name" value={createName} onChange={(e) => setCreateName(e.target.value)} required />
          <Textarea label="Description" value={createDescription} onChange={(e) => setCreateDescription(e.target.value)} rows={3} />
          <div className="field">
            <label className="field-label">Permissions</label>
            <div className="stack" style={{ gap: 'var(--space-2)', maxHeight: '200px', overflow: 'auto' }}>
              {permissions?.map((perm) => (
                <label key={perm.code} style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-2)', fontSize: '13px' }}>
                  <input
                    type="checkbox"
                    checked={createPermissions.includes(perm.code)}
                    onChange={(e) => setCreatePermissions(e.target.checked ? [...createPermissions, perm.code] : createPermissions.filter(p => p !== perm.code))}
                  />
                  <span className="mono">{perm.code}</span>
                  <span className="muted">{perm.description}</span>
                  {perm.administrative && <Tag tone="warning">Admin</Tag>}
                </label>
              ))}
            </div>
          </div>
        </form>
      </Dialog>

      <Dialog
        open={editRoleDialogOpen}
        onClose={() => { setEditRoleDialogOpen(false); setEditingRole(null); }}
        eyebrow="Edit role"
        title={`Edit ${editingRole?.name ?? ''}`}
        description="Change the name, description, or permissions for this role. Built-in roles cannot be edited."
        footer={
          <div className="dialog-footer">
            <Button variant="outline" onClick={() => { setEditRoleDialogOpen(false); setEditingRole(null); }}>Cancel</Button>
            <Button variant="primary" onClick={handleUpdateRole} disabled={updateRole.isPending}>
              {updateRole.isPending ? 'Saving…' : 'Save changes'}
            </Button>
          </div>
        }
      >
        <form onSubmit={handleUpdateRole} className="stack" style={{ gap: 'var(--space-4)' }}>
          <Input label="Role name" value={editName} onChange={(e) => setEditName(e.target.value)} required disabled={editingRole?.system} />
          <Textarea label="Description" value={editDescription} onChange={(e) => setEditDescription(e.target.value)} rows={3} disabled={editingRole?.system} />
          {editingRole?.system && <p className="caption" style={{ color: 'var(--color-warning)' }}>Built-in role: name cannot be changed.</p>}
          <div className="field">
            <label className="field-label">Permissions</label>
            <div className="stack" style={{ gap: 'var(--space-2)', maxHeight: '200px', overflow: 'auto' }}>
              {permissions?.map((perm) => (
                <label key={perm.code} style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-2)', fontSize: '13px' }}>
                  <input
                    type="checkbox"
                    checked={editPermissions.includes(perm.code)}
                    onChange={(e) => setEditPermissions(e.target.checked ? [...editPermissions, perm.code] : editPermissions.filter(p => p !== perm.code))}
                    disabled={editingRole?.system}
                  />
                  <span className="mono">{perm.code}</span>
                  <span className="muted">{perm.description}</span>
                  {perm.administrative && <Tag tone="warning">Admin</Tag>}
                </label>
              ))}
            </div>
          </div>
        </form>
      </Dialog>

      <Dialog
        open={deleteRoleDialogOpen}
        onClose={() => { setDeleteRoleDialogOpen(false); setDeletingRole(null); }}
        eyebrow="Delete role"
        title={`Delete ${deletingRole?.name ?? ''}?`}
        description="This action cannot be undone. Members with this role will need to be reassigned."
        footer={
          <div className="dialog-footer">
            <Button variant="outline" onClick={() => { setDeleteRoleDialogOpen(false); setDeletingRole(null); }}>Cancel</Button>
            <Button variant="danger" onClick={handleDeleteRole} disabled={deleteRole.isPending}>
              {deleteRole.isPending ? 'Deleting…' : 'Delete role'}
            </Button>
          </div>
        }
      >
        {deletingRole?.system && <p className="muted" style={{ color: 'var(--color-warning)' }}>This is a built-in role and cannot be deleted.</p>}
        {deletingRole && deletingRole.holders > 0 && <p className="muted" style={{ color: 'var(--color-danger)' }}>{deletingRole.holders} member(s) currently hold this role.</p>}
      </Dialog>
    </div>
  )
}