import type { CSSProperties } from 'react'
import { categoryTone } from '../../lib/labels'
import { initials } from '../../lib/session'

/*
 * An agent's initials in a circle tinted by its category, so a glance at a thread or a card tells
 * two agents apart the same way the rest of the console already does (Tag's category tones).
 *
 * Moved here from components/chat/AgentAvatar.tsx so the Orchestrator sheets can use it too;
 * components/chat/AgentAvatar.tsx stays in place as a thin re-export.
 */

const TONE_VAR: Record<string, string> = {
  operations: 'var(--category-operations)',
  engineering: 'var(--category-engineering)',
  growth: 'var(--category-growth)',
  support: 'var(--category-support)',
  neutral: 'var(--muted)',
}

export function AgentAvatar({
  name,
  category,
  fallback = false,
  size = 'md',
  quietInitials = false,
}: {
  name: string
  category?: string | null | undefined
  /** The General Employee: the workspace's default for anything no specialist covers. It carries
      no category tint of its own, so it reads in the same neutral colours as any other avatar. */
  fallback?: boolean
  size?: 'md' | 'sm'
  /** Leave the circle blank when its initials would only repeat the name printed beside it ("HR" and "HR"). */
  quietInitials?: boolean
}) {
  const text = quietInitials && initials(name).toLowerCase() === name.trim().toLowerCase() ? '' : initials(name)
  const sizeClass = size === 'sm' ? ' avatar-sm' : ''
  if (fallback) {
    return (
      <span className={`avatar avatar-fallback${sizeClass}`} aria-hidden="true">
        {text}
      </span>
    )
  }
  const colour = TONE_VAR[categoryTone(category)] ?? TONE_VAR.neutral
  const style = { '--chat-avatar-colour': colour } as CSSProperties
  return (
    <span className={`avatar chat-avatar${sizeClass}`} style={style} aria-hidden="true">
      {text}
    </span>
  )
}
