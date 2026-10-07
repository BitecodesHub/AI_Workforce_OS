import { useEffect, useRef } from 'react'
import { Button, Eyebrow, Notice } from '../ui'
import { roleLabel } from '../../lib/labels'
import type { WorkspaceChoice } from '../../lib/accountQueries'

/*
 * The step after signing in when the account has no single obvious workspace.
 *
 * A session starts without a workspace when the account belongs to several, or to none. Opening
 * the console then showed every screen as "your role does not include this", because a session
 * with no workspace carries no permissions. This asks instead: which workspace, or - with none -
 * how to get one.
 *
 * Each workspace is one button, so the choice is keyboard operable without a separate submit. The
 * heading takes focus when the step appears, so a screen reader hears that the screen changed.
 */

export function WorkspacePicker({
  workspaces,
  pending,
  error,
  onPick,
  onUseAnotherAccount,
}: {
  workspaces: WorkspaceChoice[]
  /** The workspace being opened, while that runs. */
  pending: string | null
  error: string | null
  onPick: (orgId: string) => void
  onUseAnotherAccount: () => void
}) {
  const heading = useRef<HTMLHeadingElement>(null)
  const none = workspaces.length === 0

  useEffect(() => {
    heading.current?.focus()
  }, [none])

  return (
    <section className="auth-pane auth-pane-form" aria-labelledby="workspace-title">
      <div className="auth-intro">
        <Eyebrow>Signed in</Eyebrow>
        <h1 id="workspace-title" className="auth-title" tabIndex={-1} ref={heading}>
          {none ? 'You are not a member of a workspace yet' : 'Choose a workspace'}
        </h1>
        <p className="auth-subtitle">
          {none
            ? 'Create a workspace of your own, or ask for an invitation to join one that already exists.'
            : 'Your account belongs to more than one workspace. Choose the one to open now; you can pick another next time you sign in.'}
        </p>
      </div>

      {error && (
        <Notice tone="warning" live>
          {error}
        </Notice>
      )}

      {none ? (
        <div className="auth-form">
          <a className="button button-primary auth-submit" href="/create-workspace?signedIn=1">
            Create a workspace
          </a>
          <p className="auth-switch">
            To join an existing workspace, ask its administrator to invite you. Opening their invitation link adds you to it.
          </p>
        </div>
      ) : (
        <ul className="auth-form" aria-label="Your workspaces" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {workspaces.map((workspace) => {
            const name = workspace.name?.trim() || 'Unnamed workspace'
            const role = roleLabel(workspace.role)
            return (
              <li key={workspace.orgId}>
                <Button
                  variant="outline"
                  className="auth-submit"
                  // A long workspace name wraps instead of running out of the card.
                  style={{ whiteSpace: 'normal', justifyContent: 'space-between', textAlign: 'left' }}
                  aria-label={`Open ${name}, as ${role}`}
                  loading={pending === workspace.orgId}
                  disabled={pending !== null && pending !== workspace.orgId}
                  onClick={() => onPick(workspace.orgId)}
                >
                  <span>{name}</span>
                  <span className="muted">{role}</span>
                </Button>
              </li>
            )
          })}
        </ul>
      )}

      <p className="auth-switch">
        <button type="button" className="button button-quiet" onClick={onUseAnotherAccount} disabled={pending !== null}>
          Use a different account
        </button>
      </p>
    </section>
  )
}
