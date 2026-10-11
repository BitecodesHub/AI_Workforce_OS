// @find: skills, playbooks, reusable instructions, apply a skill to a chat message, create skill, edit skill, duplicate skill, delete skill, ready-made skills, GET /api/skills, POST /api/skills, PUT /api/skills/{id}, DELETE /api/skills/{id}, POST /api/skills/{id}/duplicate, Skills page, canManageSkills
// @what: Reads and changes the workspace's skills: reusable playbooks a person applies to a chat message.
// @flow: Used by routes/Skills.tsx and components/skills/SkillEditorDialog.tsx (and the Chat composer's + menu); talks to the orchestrator through lib/api.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { QueryOptions } from './queries'

/*
 * A skill is a named set of instructions, written once and applied to any chat message: "Draft a
 * customer reply", "Weekly status from GitHub". Everyone who can chat may read and apply them;
 * only someone who can change agents (agent:update) may add, change, copy or remove one. The
 * ready-made ones (builtIn) come with the workspace.
 */

/** One skill as the orchestrator returns it. */
export type Skill = {
  id: string
  name: string
  description: string
  /** Markdown the agent follows when the skill is applied. */
  instructions: string
  /** The agent the message goes to when the skill is applied, or null to route as usual. */
  defaultAgentId: string | null
  defaultAgentName: string | null
  /** Connector server ids the skill relies on, such as "github" or "gmail". */
  requiredConnectors: string[]
  /** True for a ready-made skill that came with the workspace. */
  builtIn: boolean
  createdBy: string | null
  createdAt: string
  updatedAt: string
}

/** What POST /api/skills and PUT /api/skills/{id} take. */
export type SkillInput = {
  name: string
  description?: string
  instructions: string
  defaultAgentId?: string | null
  requiredConnectors?: string[]
}

/** The longest instructions the server accepts. */
export const SKILL_INSTRUCTIONS_MAX = 20_000

/** Fills in what an older or partial answer leaves out, so screens never read undefined. */
function skillRow(skill: Skill): Skill {
  return {
    ...skill,
    description: skill.description ?? '',
    instructions: skill.instructions ?? '',
    defaultAgentId: skill.defaultAgentId ?? null,
    defaultAgentName: skill.defaultAgentName ?? null,
    requiredConnectors: Array.isArray(skill.requiredConnectors) ? skill.requiredConnectors : [],
    builtIn: Boolean(skill.builtIn),
    createdBy: skill.createdBy ?? null,
  }
}

// @find: can manage skills, may create edit delete skills, agent:update
/** Whether the signed-in role may create, change, copy and delete skills. */
export function canManageSkills(can: (permission: string) => boolean): boolean {
  return can('agent:update')
}

// @find: list skills; route: GET /api/skills; used by: Skills page, Chat + menu
/** Every skill in the workspace (chat:use). */
export const useSkills = (options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['skills'],
    queryFn: async ({ signal }) => (await api<Skill[]>('/api/skills', { signal })).map(skillRow),
    enabled: options.enabled ?? true,
  })

// @find: one skill; route: GET /api/skills/{id}; used by: Chat when opened with ?skill=
/** One skill by id (chat:use). */
export const useSkill = (id: string | null | undefined, options: QueryOptions = {}) =>
  useQuery({
    queryKey: ['skills', id],
    queryFn: async ({ signal }) => skillRow(await api<Skill>(`/api/skills/${id}`, { signal })),
    enabled: Boolean(id) && (options.enabled ?? true),
  })

// @find: create skill; route: POST /api/skills; used by: SkillEditorDialog
/** Adds a skill (agent:update). A name already in use is refused with 409. */
export function useCreateSkill() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: SkillInput) => skillRow(await api<Skill>('/api/skills', { method: 'POST', body: input })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['skills'] }),
  })
}

// @find: update skill, edit skill; route: PUT /api/skills/{id}; used by: SkillEditorDialog
/** Changes a skill (agent:update). */
export function useUpdateSkill(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: SkillInput) =>
      skillRow(await api<Skill>(`/api/skills/${id}`, { method: 'PUT', body: input })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['skills'] }),
  })
}

// @find: delete skill; route: DELETE /api/skills/{id}; used by: Skills page
/** Removes a skill (agent:update). */
export function useDeleteSkill() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/api/skills/${id}`, { method: 'DELETE' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['skills'] }),
  })
}

// @find: duplicate skill, copy skill; route: POST /api/skills/{id}/duplicate; used by: Skills page
/** Copies a skill, ready-made ones included, into a new one that can be changed (agent:update). */
export function useDuplicateSkill() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (id: string) =>
      skillRow(await api<Skill>(`/api/skills/${id}/duplicate`, { method: 'POST' })),
    onSuccess: () => client.invalidateQueries({ queryKey: ['skills'] }),
  })
}
