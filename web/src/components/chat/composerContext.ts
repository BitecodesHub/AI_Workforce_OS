// @find: composer context, plus menu attachments, knowledge chip, repository chip, skill chip, message payload, documentIds, repo, skillId, skill draft from conversation
// @what: Pure model for what the composer's "+" menu attaches besides files: chosen documents, a repository and a skill, plus the payload sent and a skill drafted from a conversation.
// @flow: Used by Composer, PlusMenu, ContextChips and Chat.tsx (send payload, "Create skill from this conversation").

/*
 * A message can carry, besides its words, files and mentions:
 *   - knowledge: workspace sources, or single documents in them, to ground it (searched first);
 *   - repo: one GitHub repository, with an optional branch and path, the work is about;
 *   - skill: one playbook whose instructions the run follows (its default agent takes the message
 *     unless somebody is mentioned).
 * The backend reads these as `knowledge`, `repo` and `skillId` on POST .../messages.
 */

export type KnowledgeChoice = {
  sourceId: string
  /** One document in the source, or null for the whole source. */
  documentId: string | null
  title: string
}

export type RepoChoice = {
  owner: string
  name: string
  branch: string | null
  path: string | null
}

export type SkillChoice = {
  id: string
  name: string
  defaultAgentId: string | null
  defaultAgentName: string | null
}

export type ComposerContext = {
  knowledge: KnowledgeChoice[]
  repo: RepoChoice | null
  skill: SkillChoice | null
}

export const EMPTY_CONTEXT: ComposerContext = { knowledge: [], repo: null, skill: null }

/** The most documents one message may be grounded in; the server refuses more. */
export const MAX_KNOWLEDGE = 20

export type ContextPayload = {
  knowledge?: { sourceId: string; documentId: string | null; title: string }[]
  repo?: RepoChoice
  skillId?: string
}

export function hasContext(context: ComposerContext | null | undefined): boolean {
  return Boolean(context && (context.knowledge.length > 0 || context.repo || context.skill))
}

// @find: build message payload from composer context
/** The fields added to the send body; empty when nothing is attached, so old payloads stay as they were. */
export function contextPayload(context: ComposerContext | null | undefined): ContextPayload {
  if (!context) return {}
  return {
    ...(context.knowledge.length > 0
      ? { knowledge: context.knowledge.map(({ sourceId, documentId, title }) => ({ sourceId, documentId, title })) }
      : {}),
    ...(context.repo ? { repo: context.repo } : {}),
    ...(context.skill ? { skillId: context.skill.id } : {}),
  }
}

const sameKnowledge = (a: KnowledgeChoice, b: Pick<KnowledgeChoice, 'sourceId' | 'documentId'>) =>
  a.sourceId === b.sourceId && a.documentId === b.documentId

export function isKnowledgeChosen(
  context: ComposerContext,
  choice: Pick<KnowledgeChoice, 'sourceId' | 'documentId'>,
): boolean {
  return context.knowledge.some((existing) => sameKnowledge(existing, choice))
}

// @find: toggle knowledge choice, choose document or source
/**
 * Adds a source or document, or removes it when already chosen. Choosing a whole source drops the
 * single documents of it already chosen (the source covers them); choosing a document of a source
 * already chosen whole is a no-op. Never more than MAX_KNOWLEDGE.
 */
export function toggleKnowledge(context: ComposerContext, choice: KnowledgeChoice): ComposerContext {
  if (isKnowledgeChosen(context, choice)) {
    return { ...context, knowledge: context.knowledge.filter((existing) => !sameKnowledge(existing, choice)) }
  }
  if (choice.documentId && context.knowledge.some((k) => k.sourceId === choice.sourceId && k.documentId === null)) {
    return context
  }
  const kept =
    choice.documentId === null ? context.knowledge.filter((k) => k.sourceId !== choice.sourceId) : context.knowledge
  if (kept.length >= MAX_KNOWLEDGE) return context
  return { ...context, knowledge: [...kept, choice] }
}

// @find: repository chip label, owner/repo@branch/path
/** "owner/repo@branch/path", as the chip shows it. */
export function repoLabel(repo: RepoChoice): string {
  let label = `${repo.owner}/${repo.name}`
  if (repo.branch) label += `@${repo.branch}`
  if (repo.path) label += `${repo.branch ? '/' : ':'}${repo.path}`
  return label
}

/** Strips slashes at either end and treats blank as none. */
export function normalisePath(path: string | null | undefined): string | null {
  const trimmed = (path ?? '').trim().replace(/^\/+|\/+$/g, '')
  return trimmed === '' ? null : trimmed
}

// @find: draft skill from conversation, create skill from this conversation
/**
 * A first draft of a skill from a conversation: named after it, its instructions built from what
 * the person asked - the first request as the goal and later messages as refinements - so the
 * playbook can be repeated on another day with other inputs. Meant to be edited before saving.
 */
export function draftSkillFromConversation(
  title: string,
  requests: readonly string[],
): { name: string; description: string; instructions: string } {
  const asked = requests.map((text) => text.trim()).filter((text) => text.length > 0)
  const name = (title.trim() || asked[0] || 'New skill').replace(/\s+/g, ' ').slice(0, 80)
  const first = asked[0] ?? 'Describe what this playbook should do.'
  const refinements = asked.slice(1, 8)
  const lines = [
    '## Goal',
    first,
    '',
    '## Steps',
    '1. Read the request and any attached documents, files or repository first.',
    '2. Ask one short question if something essential is missing; otherwise proceed.',
    ...refinements.map((text, index) => `${index + 3}. ${text.replace(/\s*\n\s*/g, ' ')}`),
    `${refinements.length + 3}. Check the result against the goal before answering.`,
    '',
    '## Output',
    'A clear answer in plain words, with headings where they help. Say plainly what could not be done.',
  ]
  return {
    name,
    description: `Drafted from the conversation "${name}".`.slice(0, 500),
    instructions: lines.join('\n').slice(0, 20_000),
  }
}

// @find: context shown on a sent message, context chips in thread
export type SentContext = {
  knowledge: { sourceId: string; documentId?: string; title?: string }[]
  repo: (RepoChoice & { label?: string }) | null
  skill: { id: string; name: string } | null
}

/** What a sent message's `detail.context` says was attached, read defensively. */
export function sentContextOf(detail: Record<string, unknown> | null | undefined): SentContext | null {
  const raw = detail?.context
  if (!raw || typeof raw !== 'object') return null
  const context = raw as Record<string, unknown>
  const knowledge = Array.isArray(context.knowledge)
    ? (context.knowledge as unknown[]).filter(
        (item): item is { sourceId: string; documentId?: string; title?: string } =>
          Boolean(item) && typeof item === 'object' && typeof (item as { sourceId?: unknown }).sourceId === 'string',
      )
    : []
  const repoRaw = context.repo as Record<string, unknown> | undefined
  const repo =
    repoRaw && typeof repoRaw.owner === 'string' && typeof repoRaw.name === 'string'
      ? {
          owner: repoRaw.owner,
          name: repoRaw.name,
          branch: typeof repoRaw.branch === 'string' ? repoRaw.branch : null,
          path: typeof repoRaw.path === 'string' ? repoRaw.path : null,
        }
      : null
  const skillRaw = context.skill as Record<string, unknown> | undefined
  const skill =
    skillRaw && typeof skillRaw.id === 'string' && typeof skillRaw.name === 'string'
      ? { id: skillRaw.id, name: skillRaw.name }
      : null
  if (knowledge.length === 0 && !repo && !skill) return null
  return { knowledge, repo, skill }
}
