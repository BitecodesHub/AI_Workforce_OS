import type { CSSProperties } from 'react'
import { categoryTone } from '../../lib/labels'
import { initials } from '../../lib/session'

/*
 * An agent's initials in a circle tinted by its category, so a glance at the thread tells two
 * agents apart the same way the rest of the console already does (Tag's category tones).
 */

const TONE_VAR: Record<string, string> = {
  operations: 'var(--category-operations)',
  engineering: 'var(--category-engineering)',
  growth: 'var(--category-growth)',
  support: 'var(--category-support)',
  neutral: 'var(--muted)',
}

export function AgentAvatar({ name, category }: { name: string; category?: string | null | undefined }) {
  const colour = TONE_VAR[categoryTone(category)] ?? TONE_VAR.neutral
  const style = { '--chat-avatar-colour': colour } as CSSProperties
  return (
    <span className="avatar chat-avatar" style={style} aria-hidden="true">
      {initials(name)}
    </span>
  )
}
