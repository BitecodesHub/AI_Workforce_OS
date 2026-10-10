// @find: member directory, workspace members, requester names, useMemberDirectory, team members list, loaded flag
// @what: Hook that loads the members list used to name requesters, only when the viewer may read members.
// @flow: Used by FlowMap, BoardList, RunSheet and GoalSheet
import { useMemo } from 'react'
import { useMembers } from '../../lib/queries'
import { can } from '../../lib/session'
import type { MemberDirectory } from './shared'

/*
 * The member list the orchestrator names requesters from. It is only asked for when the viewer
 * may read members, and it reports `loaded` only once the list has actually arrived, so a name
 * missing from a list that is still loading (or was never allowed) is never read as someone who
 * has left the workspace.
 */
// @find: hook member directory for requester names
export function useMemberDirectory(): MemberDirectory {
  const canRead = can('member:read')
  const query = useMembers({ enabled: canRead })
  const data = query.data
  const loaded = canRead && query.isSuccess
  return useMemo(
    () => ({ members: Object.fromEntries((data ?? []).map((member) => [member.userId, member])), loaded }),
    [data, loaded],
  )
}
