// @find: redirect to provider, send browser to another address, OAuth connect, goToProvider, Connect dialog
// @what: Sends the whole browser window to another address, as its own function so tests can replace it.
// @flow: Used by components/connectors/ConnectDialog.tsx.
// @find: redirect, go to provider, OAuth redirect, leave the console, window location assign, connect provider sign in
// @what: Sends the whole browser window to another address (a function of its own so tests can replace it).
// @flow: Used when connecting an OAuth connector
// @find: go to provider, redirect browser to OAuth sign in page
// @find: go to provider, leave to provider sign-in page, window.location.assign; used by: Connect dialog (connectors)
/** Sends the whole browser window to another address. Its own function so tests can replace it. */
export function goToProvider(url: string): void {
  window.location.assign(url)
}
