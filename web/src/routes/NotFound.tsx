import { Card, EmptyState, Eyebrow } from '../components/ui'

export function NotFound() {
  return (
    <div className="page">
      <Eyebrow>Page not found</Eyebrow>
      <Card>
        <EmptyState
          icon={
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="11" cy="11" r="6.5" stroke="currentColor" strokeWidth="1.6" />
              <path d="M16 16l4 4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
            </svg>
          }
          title="There is nothing at this address"
          body="The link may be out of date, or the item may have been removed. The Command Map shows everything currently in progress."
          action={
            <a className="button button-primary" href="/">
              Go to the Command Map
            </a>
          }
        />
      </Card>
    </div>
  )
}
