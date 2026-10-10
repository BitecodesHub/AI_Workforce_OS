// @find: agent grants, tool server grants, what each agent may do, gated tools, outbound destructive, DemoAgentSeeder, SandboxServerRegistry, AGENT_GRANTS
// @what: One plain sentence per agent and tool server grant, with whether a person must approve.
// @flow: Used by AgentsSection
import type { AgentId, ToolServer } from '../shared/landingFacts'

/*
 * What each demo agent may do with each tool server, in one plain sentence per grant.
 *
 * Checked against two sources, which must stay in step with this file:
 *   - orchestrator-service DemoAgentSeeder.java, which gives each agent its servers, its allowed
 *     tools (an empty list means the whole server) and its scopes;
 *   - mcp-core SandboxServerRegistry.java, which fixes every tool's side-effect class. OUTBOUND and
 *     DESTRUCTIVE tools always wait for a person, whatever any policy says; READ and WRITE tools
 *     run directly unless a policy adds a gate.
 *
 * "gated" is true when at least one tool the agent holds on that server waits for a person.
 */

export type AgentGrant = { server: ToolServer; sentence: string; gated: boolean }

export const AGENT_GRANTS: Record<AgentId, ReadonlyArray<AgentGrant>> = {
  hr: [
    { server: 'gmail', sentence: 'Reads and drafts emails on its own. Sending one waits for approval.', gated: true },
    {
      server: 'calendar',
      sentence: 'Adds events to the calendar on its own. Deleting one waits for approval.',
      gated: true,
    },
  ],
  eng: [
    // The grant covers the whole github server with repo:write, and create_issue and update_issue
    // are WRITE tools, so issues change without approval. Saying only "reads" would understate it.
    { server: 'github', sentence: 'Reads pull requests to sum them up, and opens or updates issues on its own.', gated: false },
    { server: 'jira', sentence: 'Updates tickets on its own.', gated: false },
    { server: 'slack', sentence: 'Reads channels on its own. Posting a message waits for approval.', gated: true },
  ],
  research: [
    // The drive server offers list_files, get_file and create_file only. There is no sharing
    // tool, so nothing on this server is gated and no sharing step exists to wait on.
    { server: 'drive', sentence: 'Reads files and creates new documents. It has no way to share them.', gated: false },
  ],
  support: [
    { server: 'gmail', sentence: 'Drafts every reply from your handbook. Each one waits for approval before it is sent.', gated: true },
    { server: 'slack', sentence: 'Posting a message waits for approval.', gated: true },
  ],
}

/** The grant shown first: the first gated one, or the first grant when none is gated. */
// @find: defaultGrant, default tool server for agent
export function defaultGrant(agent: AgentId): ToolServer | null {
  const grants = AGENT_GRANTS[agent]
  return (grants.find((grant) => grant.gated) ?? grants[0])?.server ?? null
}
