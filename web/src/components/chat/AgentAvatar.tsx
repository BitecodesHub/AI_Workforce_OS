// @find: agent avatar, agent picture, agent icon, re-export
// @what: Re-export of the shared AgentAvatar component kept for older chat imports.
// @flow: Real component is components/ui/AgentAvatar.
/*
 * The component itself moved to components/ui/AgentAvatar.tsx (WP3), so the Orchestrator sheets
 * can use it too. This file stays in place as a thin re-export, so every existing import under
 * components/chat/ keeps working unchanged.
 */
export { AgentAvatar } from '../ui/AgentAvatar'
