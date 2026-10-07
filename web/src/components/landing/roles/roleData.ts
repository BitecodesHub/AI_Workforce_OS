import type { TagTone } from '../../ui'

/*
 * Who can do what, as the platform actually ships it.
 *
 * Every code and every role below was checked against three sources, and must be kept in step
 * with them:
 *   - platform-core rbac/Permission.java, which fixes the 46 codes and their order (Permission.ALL);
 *   - identity-service PermissionSeeder.java, which composes the five system roles and gives each
 *     its description;
 *   - the route table in web/src/App.tsx, which decides which console screens a code opens.
 *
 * The public page must never claim a role can do more, or less, than it can. Where the seeder and
 * another screen disagree, the seeder is the truth, because it is what a new workspace receives.
 * A code is listed because it exists, but a plain-words claim needs a feature behind it: no
 * screen or endpoint closes a workspace, so workspace:delete appears in the map and nowhere in
 * the sentences.
 */

export type RoleId = 'owner' | 'admin' | 'manager' | 'employee' | 'viewer'

/** The 17 permission domains, in Permission.ALL order. */
export const PERMISSION_GROUPS: ReadonlyArray<{ domain: string; codes: readonly string[] }> = [
  { domain: 'workspace', codes: ['workspace:read', 'workspace:update', 'workspace:delete'] },
  { domain: 'member', codes: ['member:read', 'member:invite', 'member:update', 'member:remove'] },
  { domain: 'role', codes: ['role:read', 'role:create', 'role:update', 'role:delete'] },
  { domain: 'api_key', codes: ['api_key:read', 'api_key:manage'] },
  {
    domain: 'agent',
    codes: [
      'agent:read',
      'agent:create',
      'agent:update',
      'agent:delete',
      'agent:run',
      'agent:grant_tools',
      'agent:set_model_policy',
      'agent:set_approval_policy',
    ],
  },
  { domain: 'task', codes: ['task:read', 'task:create', 'task:cancel'] },
  { domain: 'run', codes: ['run:read', 'run:cancel', 'run:replay'] },
  { domain: 'chat', codes: ['chat:use'] },
  { domain: 'approval', codes: ['approval:read', 'approval:decide'] },
  { domain: 'knowledge', codes: ['knowledge:read', 'knowledge:query', 'knowledge:source_manage'] },
  { domain: 'integration', codes: ['integration:read', 'integration:connect', 'integration:disconnect'] },
  { domain: 'provider', codes: ['provider:read', 'provider:manage'] },
  { domain: 'budget', codes: ['budget:read', 'budget:manage'] },
  { domain: 'audit', codes: ['audit:read'] },
  { domain: 'analytics', codes: ['analytics:read'] },
  { domain: 'settings', codes: ['settings:read', 'settings:update'] },
  { domain: 'memory', codes: ['memory:read', 'memory:purge'] },
]

/** All 46 codes, in the same order. */
export const ALL_CODES: readonly string[] = PERMISSION_GROUPS.flatMap((group) => group.codes)

export const ROLE_ORDER: readonly RoleId[] = ['owner', 'admin', 'manager', 'employee', 'viewer']

export const ROLE_LABEL: Record<RoleId, string> = {
  owner: 'Owner',
  admin: 'Admin',
  manager: 'Manager',
  employee: 'Employee',
  viewer: 'Viewer',
}

export const ROLE_ARTICLE: Record<RoleId, 'a' | 'an'> = {
  owner: 'an',
  admin: 'an',
  manager: 'a',
  employee: 'an',
  viewer: 'a',
}

/** The compositions PermissionSeeder.seedSystemRoles() creates. */
export const ROLE_CODES: Record<RoleId, ReadonlySet<string>> = {
  owner: new Set(ALL_CODES),
  admin: new Set(ALL_CODES.filter((code) => code !== 'workspace:delete')),
  manager: new Set([
    'workspace:read',
    'member:read',
    'role:read',
    'agent:read',
    'agent:create',
    'agent:update',
    'agent:run',
    'agent:set_model_policy',
    'task:read',
    'task:create',
    'task:cancel',
    'run:read',
    'run:cancel',
    'chat:use',
    'approval:read',
    'approval:decide',
    'knowledge:read',
    'knowledge:query',
    'knowledge:source_manage',
    'integration:read',
    'provider:read',
    'budget:read',
    'analytics:read',
    'settings:read',
    'memory:read',
  ]),
  employee: new Set([
    'workspace:read',
    'member:read',
    'agent:read',
    'agent:run',
    'task:read',
    'task:create',
    'run:read',
    'chat:use',
    'knowledge:read',
    'knowledge:query',
    'integration:read',
    'approval:read',
  ]),
  viewer: new Set([
    'workspace:read',
    'member:read',
    'agent:read',
    'task:read',
    'run:read',
    'knowledge:read',
    'integration:read',
    'analytics:read',
  ]),
}

/** Verbatim from PermissionSeeder. */
export const ROLE_DESCRIPTION: Record<RoleId, string> = {
  owner: 'Full control of the workspace, and the only role that can add or remove owners.',
  admin: 'Manages people, agents, integrations and settings.',
  manager: 'Runs agents, assigns work and approves actions.',
  employee: 'Asks questions and hands routine work to agents.',
  viewer: 'Reads dashboards and traces without changing anything.',
}

export const ROLE_SUMMARY: Record<RoleId, string> = {
  owner: 'Everything, and the only role that can add or remove owners.',
  admin: 'Everything except adding, changing or removing owners.',
  manager: 'Runs agents, approves actions, cancels runs and tasks, and edits agents. Cannot change who may.',
  employee: 'Asks questions in chat and hands routine work to agents. Can see approvals, cannot decide them.',
  viewer: 'Reads dashboards and traces. Changes nothing.',
}

/** The same tones the sign-in page uses for its demo accounts. */
export const ROLE_TONE: Record<RoleId, TagTone> = {
  owner: 'blue',
  admin: 'blue',
  manager: 'success',
  employee: 'neutral',
  viewer: 'neutral',
}

/** Plain-language rows; a role has the capability only when it holds every listed code. */
export const CAPABILITIES: ReadonlyArray<{ label: string; codes: readonly string[] }> = [
  { label: 'Read runs and traces', codes: ['run:read'] },
  { label: 'Read analytics', codes: ['analytics:read'] },
  { label: 'Ask questions and hand over work', codes: ['chat:use', 'task:create'] },
  { label: 'See approvals', codes: ['approval:read'] },
  { label: 'Approve or reject agent actions', codes: ['approval:decide'] },
  { label: 'Cancel runs and edit agents', codes: ['run:cancel', 'agent:update'] },
  { label: 'Change who may do what', codes: ['member:invite', 'role:update'] },
]

/**
 * The console's screens and the code each route in App.tsx requires. A null code means the
 * screen is open to every signed-in member.
 */
export const CONSOLE_AREAS: ReadonlyArray<{ label: string; code: string | null }> = [
  { label: 'Command Map', code: null },
  { label: 'Agents', code: 'agent:read' },
  { label: 'Tasks', code: 'task:read' },
  { label: 'Approvals', code: 'approval:read' },
  { label: 'Chat', code: 'chat:use' },
  { label: 'Knowledge', code: 'knowledge:read' },
  { label: 'Connectors', code: 'integration:read' },
  { label: 'Model routing', code: 'provider:read' },
  { label: 'Members', code: 'member:read' },
  { label: 'Audit log', code: 'audit:read' },
  { label: 'Analytics', code: 'analytics:read' },
]

export function holdsAll(role: RoleId, codes: readonly string[]): boolean {
  const held = ROLE_CODES[role]
  return codes.every((code) => held.has(code))
}

/** Whether a role can open a console area. */
export function canOpenArea(role: RoleId, area: { code: string | null }): boolean {
  return area.code === null || ROLE_CODES[role].has(area.code)
}

/** How many of the console areas a role can open. */
export function countAreas(role: RoleId): number {
  return CONSOLE_AREAS.filter((area) => canOpenArea(role, area)).length
}

/**
 * What role a holds that role b does not (added), and what b holds that a does not (removed),
 * each in Permission.ALL order.
 */
export function compareRoles(a: RoleId, b: RoleId): { added: string[]; removed: string[] } {
  const codesA = ROLE_CODES[a]
  const codesB = ROLE_CODES[b]
  return {
    added: ALL_CODES.filter((code) => codesA.has(code) && !codesB.has(code)),
    removed: ALL_CODES.filter((code) => codesB.has(code) && !codesA.has(code)),
  }
}
