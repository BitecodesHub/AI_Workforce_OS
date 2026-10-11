// @find: context chips, knowledge chips, repository chip, skill chip, remove attached document, remove repo, remove skill, sent message context
// @what: Chips above the text box for what the "+" menu attached (documents, a repository, a skill), and the same labels on a sent message.
// @flow: Used by Composer (editable) and UserBubble (read-only); model in composerContext.
import type { ReactNode } from 'react'
import { repoLabel } from './composerContext'
import type { ComposerContext, SentContext } from './composerContext'
import './plusMenu.css'

function RemoveIcon() {
  return (
    <svg width="9" height="9" viewBox="0 0 9 9" fill="none" aria-hidden="true">
      <path d="M1 1l7 7M8 1L1 8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  )
}

const KIND_ICON: Record<'knowledge' | 'repo' | 'skill', ReactNode> = {
  knowledge: (
    <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path d="M3 2.5h7l3 3v8H3z M10 2.5v3h3" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
    </svg>
  ),
  repo: (
    <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <circle cx="4.5" cy="3.5" r="1.5" stroke="currentColor" strokeWidth="1.4" />
      <circle cx="4.5" cy="12.5" r="1.5" stroke="currentColor" strokeWidth="1.4" />
      <circle cx="11.5" cy="5.5" r="1.5" stroke="currentColor" strokeWidth="1.4" />
      <path d="M4.5 5v6M11.5 7c0 2.5-3 2.5-6 4" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  ),
  skill: (
    <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path d="M9 1.8 3.5 9h4l-1 5.2L12.5 7h-4z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
    </svg>
  ),
}

type Chip = { key: string; kind: 'knowledge' | 'repo' | 'skill'; label: string; title: string; spoken: string; onRemove?: () => void }

function ChipList({ chips, label, align }: { chips: Chip[]; label: string; align?: 'end' }) {
  if (chips.length === 0) return null
  return (
    <ul className="chat-context-chips" aria-label={label} data-align={align}>
      {chips.map((chip) => (
        <li key={chip.key}>
          <span className="chat-context-chip" data-kind={chip.kind} title={chip.title}>
            <span className="chat-context-chip-icon">{KIND_ICON[chip.kind]}</span>
            <span className="chat-context-chip-label">{chip.label}</span>
            {chip.onRemove && (
              <button type="button" aria-label={`Remove ${chip.spoken}`} title={`Remove ${chip.spoken}`} onClick={chip.onRemove}>
                <RemoveIcon />
              </button>
            )}
          </span>
        </li>
      ))}
    </ul>
  )
}

// @find: ContextChips, composer context chips with remove buttons
/** What the "+" menu attached to the message being written, each with a remove button. */
export function ContextChips({
  context,
  onChange,
}: {
  context: ComposerContext
  onChange: (next: ComposerContext) => void
}) {
  const chips: Chip[] = []
  if (context.skill) {
    const skill = context.skill
    chips.push({
      key: `skill-${skill.id}`,
      kind: 'skill',
      label: `Skill: ${skill.name}`,
      title: skill.defaultAgentName ? `${skill.name}, goes to ${skill.defaultAgentName} unless you mention someone` : skill.name,
      spoken: `skill ${skill.name}`,
      onRemove: () => onChange({ ...context, skill: null }),
    })
  }
  if (context.repo) {
    const label = repoLabel(context.repo)
    chips.push({
      key: 'repo',
      kind: 'repo',
      label,
      title: `GitHub repository ${label}`,
      spoken: `repository ${label}`,
      onRemove: () => onChange({ ...context, repo: null }),
    })
  }
  for (const item of context.knowledge) {
    chips.push({
      key: `k-${item.sourceId}-${item.documentId ?? 'all'}`,
      kind: 'knowledge',
      label: item.title,
      title: item.documentId ? `Document: ${item.title}` : `All documents in ${item.title}`,
      spoken: item.title,
      onRemove: () =>
        onChange({
          ...context,
          knowledge: context.knowledge.filter((k) => !(k.sourceId === item.sourceId && k.documentId === item.documentId)),
        }),
    })
  }
  return <ChipList chips={chips} label="Attached to this message" />
}

// @find: SentContextChips, context labels on a sent message
/** The same labels on a message already sent, without remove buttons. */
export function SentContextChips({ context }: { context: SentContext }) {
  const chips: Chip[] = []
  if (context.skill) {
    chips.push({ key: 'skill', kind: 'skill', label: `Skill: ${context.skill.name}`, title: context.skill.name, spoken: context.skill.name })
  }
  if (context.repo) {
    const label = repoLabel(context.repo)
    chips.push({ key: 'repo', kind: 'repo', label, title: `GitHub repository ${label}`, spoken: label })
  }
  context.knowledge.forEach((item, index) => {
    const title = item.title ?? 'Workspace document'
    chips.push({ key: `k-${index}`, kind: 'knowledge', label: title, title, spoken: title })
  })
  return <ChipList chips={chips} label="Sent with this message" align="end" />
}
