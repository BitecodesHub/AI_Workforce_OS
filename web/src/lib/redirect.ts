/** Sends the whole browser window to another address. Its own function so tests can replace it. */
export function goToProvider(url: string): void {
  window.location.assign(url)
}
