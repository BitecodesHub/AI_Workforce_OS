// @find: sign in page frame, auth layout, signed-out screens, login shell, create workspace page, accept invitation page, brand header, footer links, AuthShell
// @what: Shared frame (brand, atmosphere, footer links) around every signed-out screen.
// @flow: Wraps sign in, sign up and invitation pages; renders Brand
import type { ReactNode } from 'react'
import { Brand } from '../layout/Brand'

/*
 * The frame every signed-out screen shares: sign in, create a workspace, accept an invitation.
 *
 * The brand sits centred above the content and a short row of links centred below it, so each of
 * these screens reads as one balanced composition around its card, whatever that card holds. The
 * atmosphere behind it is light and depth only; the card itself carries no resting shadow.
 */

export type AuthShellProps = {
  children: ReactNode
  /** Links under the card, such as the way back to the home page. */
  footer?: ReactNode
}

// @find: AuthShell, sign in layout, login page frame
export function AuthShell({ children, footer }: AuthShellProps) {
  return (
    <main id="main" className="auth-page">
      <div className="auth-atmosphere" aria-hidden="true" />
      <div className="auth-grid" aria-hidden="true" />
      <div className="auth-header">
        <Brand />
      </div>
      <div className="auth-body">{children}</div>
      {footer && (
        <nav className="auth-footer" aria-label="More">
          {footer}
        </nav>
      )}
    </main>
  )
}
