import type { ReactNode } from 'react'
import { ApiError, describeApiError } from '../../lib/api'
import { formatDateTime } from '../../lib/format'
import { Button, EmptyState, ErrorState, LoadingState, Notice, PermissionState } from './index'

/*
 * The four states of any screen that loads data, rendered the same way everywhere.
 *
 * Before this, every screen designed only the loaded state. Loading showed a blank page, failure
 * showed a blank page, and a role without permission showed fabricated sample data - three
 * different situations that looked identical, or worse, looked like success.
 */

type QueryLike<T> = {
  data: T | undefined
  error: unknown
  isLoading: boolean
  refetch: () => unknown
  /** When the query last succeeded, in epoch ms. Used only by `keepData`'s stale banner. */
  dataUpdatedAt?: number
}

/**
 * The record is not there: a 404, or the 400 the platform returns when an id in the address is
 * not an id at all (a truncated or mistyped link). Retrying cannot change either.
 */
function isMissingRecord(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false
  return error.status === 404 || (error.status === 400 && error.code === 'malformed_request')
}

export function QueryState<T>({
  query,
  permission,
  what,
  isEmpty,
  empty,
  rows,
  notFound,
  keepData,
  children,
}: {
  query: QueryLike<T>
  /** The permission this data needs, named in the message when the role lacks it. */
  permission: string
  /** What is being shown, as a phrase: "the approvals queue". */
  what: string
  isEmpty?: (data: T) => boolean
  empty?: ReactNode
  rows?: number
  /** The way out when the record does not exist, usually a BackLink to its list. */
  notFound?: ReactNode
  /**
   * Keeps showing the last good data behind a stale banner when a poll fails, instead of losing
   * the screen to the error state. Meant for a screen that polls: a one-shot query has nothing
   * behind the error worth keeping.
   */
  keepData?: boolean
  children: (data: T) => ReactNode
}) {
  if (query.isLoading) return <LoadingState rows={rows ?? 4} label={`Loading ${what}`} />

  if (query.error) {
    if (keepData && query.data !== undefined) {
      const updatedAt = query.dataUpdatedAt
      return (
        <>
          <div className="stale-banner">
            <Notice tone="warning" live>
              Lost connection.{' '}
              {updatedAt !== undefined
                ? `Showing what was loaded at ${formatDateTime(new Date(updatedAt).toISOString())}.`
                : 'Showing what was loaded before.'}{' '}
              Retrying.{' '}
              <Button variant="quiet" onClick={() => query.refetch()}>
                Retry now
              </Button>
            </Notice>
          </div>
          {children(query.data)}
        </>
      )
    }
    const error = query.error
    if (error instanceof ApiError && error.isPermissionDenied) {
      return <PermissionState permission={permission} what={what} />
    }
    if (isMissingRecord(error)) {
      return (
        <EmptyState
          icon={<EmptyIcon kind="search" />}
          title="This item does not exist"
          body="It may have been removed, or the link is incomplete."
          action={notFound}
        />
      )
    }
    // describeApiError names the fields a validation failure is about, and adds the request
    // reference to a server failure so it can be quoted when reporting it.
    const retryable = error instanceof ApiError ? error.retryable || error.status === 0 : true
    return <ErrorState message={describeApiError(error)} retryable={retryable} onRetry={() => query.refetch()} />
  }

  if (query.data === undefined) return <LoadingState rows={rows ?? 4} label={`Loading ${what}`} />
  if (isEmpty && empty && isEmpty(query.data)) return <>{empty}</>
  return <>{children(query.data)}</>
}

/** Back to the list a detail screen belongs to. */
export function BackLink({ href, label }: { href: string; label: string }) {
  return (
    <a className="back-link" href={href}>
      <svg width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
        <path d="M7.5 2.5L4 6l3.5 3.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      {label}
    </a>
  )
}

/** The empty-state icon used across lists, kept in one place. */
export function EmptyIcon({ kind }: { kind: 'agent' | 'inbox' | 'document' | 'task' | 'search' }) {
  const paths: Record<string, ReactNode> = {
    agent: (
      <>
        <circle cx="12" cy="8.5" r="3.5" stroke="currentColor" strokeWidth="1.6" />
        <path d="M5 19.5c1.2-3.2 3.8-5 7-5s5.8 1.8 7 5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      </>
    ),
    inbox: <path d="M4 13l2.5-7h11L20 13v5H4v-5zm0 0h5l1 2h4l1-2h5" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />,
    document: (
      <>
        <path d="M7 3.5h7l4 4V20H7V3.5z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
        <path d="M14 3.5V8h4M9.5 12.5h5M9.5 15.5h5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      </>
    ),
    task: (
      <>
        <rect x="4.5" y="4.5" width="15" height="15" rx="3" stroke="currentColor" strokeWidth="1.6" />
        <path d="M8.5 12l2.5 2.5 4.5-5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
      </>
    ),
    search: (
      <>
        <circle cx="11" cy="11" r="6" stroke="currentColor" strokeWidth="1.6" />
        <path d="M15.5 15.5l4 4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      </>
    ),
  }
  return (
    <svg width="28" height="28" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      {paths[kind]}
    </svg>
  )
}

export { EmptyState, PermissionState }
