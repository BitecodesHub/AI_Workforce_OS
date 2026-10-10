// @find: add people to chat, share conversation, private conversation, conversation members, invite to chat, who can read, Add people dialog, canUseChat, chat permission, role
// @what: Dialog to choose who else can read a private conversation.
// @flow: Used by ThreadHeader.
import { useState } from 'react'
import { Button, Dialog, Notice } from '../ui'
import { QueryState } from '../ui/QueryState'
import { describeApiError } from '../../lib/api'
import { useAddConversationPeople, useConversationPeople, useRemoveConversationPerson } from '../../lib/chatQueries'
import { useMembers, useRoles } from '../../lib/queries'
import type { Role } from '../../lib/queries'
import { can, profile } from '../../lib/session'

/** The built-in roles that cannot open Chat (PermissionSeeder), for a person who cannot read the roles. */
const BUILT_IN_WITHOUT_CHAT = new Set(['viewer'])

// @find: AddPeopleDialog, add people dialog, add people to chat, share conversation, private conversation, conversation members
/**
 * Whether a member with `role` can open Chat, and so read a conversation they are added to.
 * Uses the workspace's roles when they could be read (a custom role is judged by its own
 * permissions), else what the built-in roles hold. A custom role nobody here can read is assumed
 * to have it, because refusing everyone on a guess would be worse.
 */
export function canUseChat(role: string, roles: readonly Pick<Role, 'name' | 'permissions'>[] | undefined): boolean {
  const found = roles?.find((candidate) => candidate.name === role)
  if (found) return found.permissions.includes('chat:use')
  return !BUILT_IN_WITHOUT_CHAT.has(role)
}

/*
 * Who else can read a private conversation: everyone in the workspace is listed, those already
 * added have a Remove button, and ticking people and choosing Add gives them access. The person
 * who started the conversation always has it and is not listed.
 */
export function AddPeopleDialog({
  open,
  conversationId,
  onClose,
}: {
  open: boolean
  conversationId: string | null
  onClose: () => void
}) {
  const members = useMembers({ enabled: open })
  const roles = useRoles({ enabled: open && can('role:read') })
  const added = useConversationPeople(conversationId, open)
  const add = useAddConversationPeople()
  const remove = useRemoveConversationPerson()
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const [failure, setFailure] = useState<string | null>(null)
  const me = profile()?.userId

  const already = new Set(added.data ?? [])
  const candidates = (members.data ?? []).filter((member) => member.userId !== me && member.status === 'active')

  function toggle(userId: string) {
    setPicked((current) => {
      const next = new Set(current)
      if (next.has(userId)) next.delete(userId)
      else next.add(userId)
      return next
    })
  }

  async function submit() {
    if (!conversationId || picked.size === 0) return
    setFailure(null)
    try {
      await add.mutateAsync({ id: conversationId, userIds: [...picked] })
      setPicked(new Set())
    } catch (error) {
      setFailure(describeApiError(error))
    }
  }

  async function withdraw(userId: string) {
    if (!conversationId) return
    setFailure(null)
    try {
      await remove.mutateAsync({ id: conversationId, userId })
    } catch (error) {
      setFailure(describeApiError(error))
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      eyebrow="Private conversation"
      title="Add people"
      description="People you add can read this conversation and the work it started. Everyone else cannot see it exists."
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Done
          </Button>
          <Button onClick={() => void submit()} loading={add.isPending} disabled={picked.size === 0}>
            Add {picked.size > 0 ? picked.size : ''}
          </Button>
        </>
      }
      error={failure}
    >
      {open && (
      <QueryState query={members} permission="member:read" what="the people in this workspace" rows={3}>
        {() =>
          candidates.length === 0 ? (
            <Notice tone="info">There is nobody else in this workspace to add yet.</Notice>
          ) : (
            <ul className="stack" style={{ gap: 'var(--space-2)', listStyle: 'none', padding: 0, margin: 0 }}>
              {candidates.map((member) => (
                <li key={member.userId} className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)' }}>
                  {!already.has(member.userId) && !canUseChat(member.role, roles.data) ? (
                    // Listed, so nobody wonders where they went, but not offered: their role
                    // cannot open Chat, so they could never read it.
                    <span>
                      {member.displayName}{' '}
                      <span className="caption muted">
                        Cannot be added: the {member.role} role cannot open Chat.
                      </span>
                    </span>
                  ) : already.has(member.userId) ? (
                    <>
                      <span>
                        {member.displayName} <span className="caption muted">can read this</span>
                      </span>
                      <Button variant="outline" onClick={() => void withdraw(member.userId)}>
                        Remove {member.displayName}
                      </Button>
                    </>
                  ) : (
                    <label className="question-option">
                      <input
                        type="checkbox"
                        checked={picked.has(member.userId)}
                        onChange={() => toggle(member.userId)}
                      />
                      <span className="question-option-label">
                        {member.displayName} <span className="caption muted">{member.email}</span>
                      </span>
                    </label>
                  )}
                </li>
              ))}
            </ul>
          )
        }
      </QueryState>
      )}
    </Dialog>
  )
}
