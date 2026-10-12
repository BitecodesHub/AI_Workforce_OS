// @find: server clock, clock skew, time offset, server time, elapsed time wrong, timer shows 0 ms, serverNow, noteServerDate
// @what: Keeps the difference between this computer's clock and the server's, so elapsed times and "how long ago" read from the server's clock.
// @flow: noteServerDate is called by lib/api.ts for every API response; serverNow is read by lib/useNow.ts

/*
 * Times on screen ("Working 12 s", "5 minutes ago") compare a server timestamp with the current
 * time. A computer whose clock runs behind the server's would show a running task as "0 ms" until
 * its clock caught up, and one running ahead would add the difference to every timer. Each API
 * response carries the server's Date header; the offset learnt from it is applied to every reading
 * of the current time.
 */

/** Milliseconds to add to this computer's clock to get the server's. */
let offsetMs = 0

/** Differences smaller than this are left alone: the Date header has one-second precision. */
const IGNORE_BELOW_MS = 1_500

/**
 * Learns the server's clock from one response's Date header, taking the request's midpoint as the
 * moment the server stamped it. Ignored when the header is missing or unreadable, or when the
 * round trip took so long that the midpoint says little.
 *
 * @param dateHeader the response's Date header
 * @param sentAt this computer's time when the request was sent (ms)
 * @param receivedAt this computer's time when the response arrived (ms)
 */
export function noteServerDate(dateHeader: string | null, sentAt: number, receivedAt: number): void {
  if (!dateHeader || receivedAt - sentAt > 3_000) return
  const server = Date.parse(dateHeader)
  if (Number.isNaN(server)) return
  // The header is truncated to the second, so on average it reads half a second early.
  const difference = server + 500 - (sentAt + receivedAt) / 2
  offsetMs = Math.abs(difference) < IGNORE_BELOW_MS ? 0 : Math.round(difference)
}

/** The current time on the server's clock, in milliseconds since the epoch. */
export function serverNow(): number {
  return Date.now() + offsetMs
}

/** For tests: forgets the learnt difference. */
export function resetServerClock(): void {
  offsetMs = 0
}
