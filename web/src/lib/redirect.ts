// @find: redirect, redirect to provider, go to provider, send browser to another address, OAuth connect, OAuth redirect, connect provider sign in, leave the console, window.location.assign, goToProvider, Connect dialog
// @what: Sends the whole browser window to another address, as its own function so tests can replace it.
// @flow: Used by components/connectors/ConnectDialog.tsx when connecting an OAuth connector.
/** Sends the whole browser window to another address. Its own function so tests can replace it. */
export function goToProvider(url: string): void {
  window.location.assign(url)
}
