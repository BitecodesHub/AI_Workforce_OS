import type { ReactElement } from 'react'

/*
 * The icons the public page uses, drawn as 16px line SVGs in the current text colour.
 *
 * Every icon is decorative: it is hidden from assistive technology and never the only carrier of
 * meaning, so the text beside it always says what it means. No Unicode glyph stands in for one.
 */

export type IconName =
  | 'check'
  | 'dash'
  | 'x'
  | 'lock'
  | 'pause'
  | 'play'
  | 'arrow-right'
  | 'link'
  | 'link-broken'
  | 'document'
  | 'route'
  | 'gate'
  | 'clock'
  | 'slash'
  | 'pencil'
  | 'reset'

export type IconProps = { name: IconName; size?: 14 | 16 | 18; className?: string }

const SHAPES: Record<IconName, ReactElement> = {
  check: <path d="M3.5 8.5 6.5 11.5 12.5 4.5" />,
  dash: <path d="M4 8h8" />,
  x: <path d="M4.5 4.5l7 7M11.5 4.5l-7 7" />,
  lock: (
    <>
      <rect x="3.5" y="7" width="9" height="6.5" rx="1.5" />
      <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" />
    </>
  ),
  pause: <path d="M6 4v8M10 4v8" />,
  play: <path d="M5.5 3.8v8.4L12 8z" />,
  'arrow-right': <path d="M3 8h10M9 4l4 4-4 4" />,
  link: (
    <>
      <path d="M6.8 9.2a2.6 2.6 0 0 0 3.7 0l2-2a2.6 2.6 0 0 0-3.7-3.7l-.9.9" />
      <path d="M9.2 6.8a2.6 2.6 0 0 0-3.7 0l-2 2a2.6 2.6 0 0 0 3.7 3.7l.9-.9" />
    </>
  ),
  'link-broken': (
    <>
      <path d="M8.6 4.3l.8-.8a2.6 2.6 0 0 1 3.7 3.7l-.8.8" />
      <path d="M7.4 11.7l-.8.8a2.6 2.6 0 0 1-3.7-3.7l.8-.8" />
      <path d="M2.5 5.5h2M5.5 2.5v2M13.5 10.5h-2M10.5 13.5v-2" />
    </>
  ),
  document: (
    <>
      <path d="M4 2.5h5l3 3v8H4z" />
      <path d="M9 2.5v3h3M6 8.5h4M6 11h4" />
    </>
  ),
  route: (
    <>
      <circle cx="4" cy="4" r="1.5" />
      <circle cx="12" cy="12" r="1.5" />
      <path d="M5.5 4h4a2 2 0 0 1 0 4h-3a2 2 0 0 0 0 4h4" />
    </>
  ),
  gate: <path d="M3 13.5v-11M13 13.5v-11M3 6h10M3 10h10" />,
  clock: (
    <>
      <circle cx="8" cy="8" r="5.5" />
      <path d="M8 5v3.2l2 1.3" />
    </>
  ),
  slash: (
    <>
      <circle cx="8" cy="8" r="5.5" />
      <path d="M4.1 11.9l7.8-7.8" />
    </>
  ),
  pencil: <path d="M10.5 3 13 5.5 6 12.5 3 13l.5-3zM9 4.5 11.5 7" />,
  reset: (
    <>
      <path d="M3.5 8a4.5 4.5 0 1 0 1.32-3.18" />
      <path d="M4.8 2.3v2.6h2.6" />
    </>
  ),
}

export function Icon({ name, size = 14, className }: IconProps): ReactElement {
  return (
    <svg
      className={className ? `lp-icon ${className}` : 'lp-icon'}
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {SHAPES[name]}
    </svg>
  )
}
