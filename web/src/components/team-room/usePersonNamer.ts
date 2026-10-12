// @find: person namer, name a requester, You, member name, Someone, team room people, usePersonNamer
// @what: Hook that names people in the team room: "You" for the viewer, a member's name when the role may read members, otherwise "Someone".
// @flow: Used by the Team room page and its components; reads the session profile and useMemberDirectory.
import { useMemo } from 'react'
import { useMemberDirectory } from '../orchestrator/useMemberDirectory'
import { personNamer } from '../../lib/teamRoomQueries'
import type { PersonNamer } from '../../lib/teamRoomQueries'
import { profile } from '../../lib/session'

// @find: usePersonNamer hook, name people in the team room
/**
 * A PersonNamer for the signed-in viewer. The member list is only asked for when the role may read
 * members (useMemberDirectory); without it everyone but the viewer is "Someone", never a guess.
 */
export function usePersonNamer(): PersonNamer {
  const { members } = useMemberDirectory()
  const me = profile()?.userId ?? null
  return useMemo(() => {
    const names: Record<string, string> = {}
    for (const [userId, member] of Object.entries(members)) names[userId] = member.displayName
    return personNamer(me, names)
  }, [members, me])
}
