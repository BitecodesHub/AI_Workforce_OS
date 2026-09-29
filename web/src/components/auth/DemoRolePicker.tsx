import { useState, type CSSProperties } from 'react'
import { Spinner } from '../ui'
import { roleLabel } from '../../lib/labels'

/*
 * The demo accounts, as one row of roles.
 *
 * Five tall cards made the sign-in card longer than any screen; one row of role tiles says the same
 * thing in a glance. Each tile is a real action: one press signs that role in. What the role may do
 * is shown beneath the row as the pointer or the keyboard reaches a tile, and is always part of the
 * tile's description for a screen reader, so nothing depends on hovering.
 *
 * While one role signs in, the others stay focusable but inert (aria-disabled rather than
 * disabled), so keyboard focus is never dropped to the top of the page mid-action.
 */

export type DemoAccount = {
  email: string
  displayName: string
  role: string
  describes: string
}

/*
 * One colour per role, not one per permission level: with only five tiles a glance is the whole
 * interaction, and two of them reading as the same colour (owner/admin both blue, employee/viewer
 * both grey, as this used to map) looks like an unstyled pair, not a deliberate choice. Every
 * value is an existing token - the agent-category colours double as a five-way palette here - so
 * this adds no new colour to the product; it is not the tone this app uses to grade a role's
 * reach (see roleData.ts's own ROLE_TONE, which pairs owner and admin on purpose because between
 * the two of them only workspace:delete differs).
 */
const ROLE_COLOUR: Record<string, string> = {
  owner: 'var(--blue)',
  admin: 'var(--category-growth)',
  manager: 'var(--green)',
  employee: 'var(--category-support)',
  viewer: 'var(--muted)',
}

/** The role a first-time evaluator learns the most from: it can approve an agent's action. */
const SUGGESTED_ROLE = 'manager'

/**
 * The first two letters of a demo account's given name - not first-plus-last-word initials, the
 * usual convention for a real person (see initials() in lib/session.ts for that one). Every demo
 * account here is named "<given name> <role>" on purpose, so the role reads in the account list
 * without a separate column; but that makes the surname the role itself, and a role tile already
 * repeats the role as its own label right below the avatar. First-plus-last initials would then
 * often double a letter (Arjun Admin, Maya Manager, Eli Employee, Vik Viewer all do), which reads
 * as a typo, not as a badge - so this takes the given name on its own instead.
 */
export function initialsOf(name: string): string {
  const first = name.trim().split(/\s+/).find(Boolean) ?? ''
  return first.slice(0, 2).toUpperCase()
}

export type DemoRolePickerProps = {
  accounts: DemoAccount[]
  password: string | null
  /** The address being signed in, if a demo sign-in is under way. */
  pending: string | null
  /** True while any sign-in is under way, including the form's own. */
  busy: boolean
  onPick: (account: DemoAccount) => void
}

export function DemoRolePicker({ accounts, password, pending, busy, onPick }: DemoRolePickerProps) {
  const [preview, setPreview] = useState<DemoAccount | null>(null)
  const pendingAccount = accounts.find((account) => account.email === pending) ?? null
  const shown = pendingAccount ?? preview
  const suggested = accounts.some((account) => account.role === SUGGESTED_ROLE)

  return (
    <section className="auth-demo" aria-labelledby="auth-demo-title">
      <h2 id="auth-demo-title" className="auth-divider">
        Or explore with a demo role
      </h2>

      <ul className="auth-roles" style={{ '--role-count': accounts.length } as CSSProperties}>
        {accounts.map((account) => {
          const descriptionId = `auth-role-${account.role}-description`
          const isPending = pending === account.email
          return (
            <li key={account.email}>
              <button
                type="button"
                className="auth-role"
                style={{ '--role-colour': ROLE_COLOUR[account.role] ?? 'var(--muted)' } as CSSProperties}
                aria-label={`Sign in as ${account.displayName}, ${roleLabel(account.role)}`}
                aria-describedby={descriptionId}
                aria-disabled={busy || undefined}
                aria-busy={isPending || undefined}
                data-suggested={account.role === SUGGESTED_ROLE || undefined}
                onClick={() => {
                  if (!busy) onPick(account)
                }}
                onMouseEnter={() => setPreview(account)}
                onMouseLeave={() => setPreview(null)}
                onFocus={() => setPreview(account)}
                onBlur={() => setPreview(null)}
              >
                <span className="auth-role-avatar" aria-hidden="true">
                  {isPending ? <Spinner size={16} /> : initialsOf(account.displayName)}
                </span>
                <span className="auth-role-name">{roleLabel(account.role)}</span>
              </button>
              <span id={descriptionId} className="visually-hidden">
                {account.describes}.
              </span>
            </li>
          )
        })}
      </ul>

      {/* Two lines are always reserved, so moving across the row never makes the card jump. */}
      <p className="auth-role-caption">
        {shown ? (
          <>
            <span className="auth-role-caption-name">
              {pendingAccount ? `Signing in as ${shown.displayName}` : shown.displayName}
            </span>
            {!pendingAccount && <> · {shown.describes}</>}
          </>
        ) : suggested ? (
          <>One press signs you in to a shared demo workspace. Start with Manager to approve an agent&apos;s action.</>
        ) : (
          <>One press signs you in to a shared demo workspace, as that role.</>
        )}
      </p>

      {/* Stated outright. A shared demo password everybody knows, dressed up as a secret, is
          worse than one nobody pretends about. */}
      {password && (
        <p className="auth-role-password">
          All share the password <code className="auth-code">{password}</code>. Local and test environments only.
        </p>
      )}
    </section>
  )
}
