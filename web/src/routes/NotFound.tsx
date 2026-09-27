import { Card, EmptyState, Eyebrow } from '../components/ui'
import { isSignedIn } from '../lib/session'

/*
 * An address that matches no screen.
 *
 * A signed-in person sees this inside the usual navigation (App.tsx), so every other screen is
 * still one click away. The heading is the page's h1, because it is the only title the page has.
 */
export function NotFound() {
  const signedIn = isSignedIn()
  return (
    <div className="page">
      <Eyebrow>Page not found</Eyebrow>
      <Card>
        <EmptyState
          titleAs="h1"
          icon={
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="11" cy="11" r="6.5" stroke="currentColor" strokeWidth="1.6" />
              <path d="M16 16l4 4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
            </svg>
          }
          title="There is nothing at this address"
          body={
            signedIn
              ? 'The link may be out of date, or the item may have been removed. The Command Map shows everything currently in progress.'
              : 'The link may be out of date, or the page may have been removed.'
          }
          action={
            <a className="button button-primary" href="/">
              {signedIn ? 'Go to the Command Map' : 'Go to the home page'}
            </a>
          }
        />
      </Card>
    </div>
  )
}
