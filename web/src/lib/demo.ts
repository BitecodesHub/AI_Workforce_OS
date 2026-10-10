// @find: demo accounts, try the demo, sign in demo, demo data, GET /api/auth/demo-accounts, useDemoAccounts, hasDemoAccounts
// @what: Finds out once per page load whether this site offers demo accounts so the Try the demo buttons are honest.
// @flow: Used by the public pages and the sign-in page; calls identity-service demo-accounts endpoint
import { useSyncExternalStore } from 'react'

/*
 * Whether this site offers demo accounts, asked once per page load and shared by every caller.
 *
 * The public pages' main button says "Try the demo" and leads to the sign-in page, which is only
 * honest where demo accounts exist. They exist on a local or launcher install with demo data
 * switched on; a deployed site, or an install started without demo data, has none - identity
 * then answers 404 (the endpoint is switched off) or an empty list. Everything that is not a
 * list of accounts, a failed request included, counts as none, so the buttons fall back to the
 * simulated demos on the page, which work everywhere.
 *
 * Until the answer arrives the state is 'loading', and callers render that exactly like "none":
 * the safe variant shows first and the stronger offer replaces it, never the other way round.
 *
 * The request is made once and remembered for the life of the page. Hero, the sticky bar, the
 * closing call to action and the role explorer all ask; one request answers them all.
 */

export type DemoAccount = {
  email: string
  displayName: string
  role: string
  describes: string
}

export type DemoAccountsState = {
  status: 'loading' | 'ready'
  accounts: readonly DemoAccount[]
  /** The shared demo password, published deliberately by identity; null when there is none. */
  password: string | null
}

export const DEMO_ACCOUNTS_URL = '/api/auth/demo-accounts'

const LOADING: DemoAccountsState = { status: 'loading', accounts: [], password: null }
const NONE: DemoAccountsState = { status: 'ready', accounts: [], password: null }

let state: DemoAccountsState = LOADING
let request: Promise<void> | null = null
// Bumped by the test reset, so an answer to a request from before it is ignored.
let generation = 0
const listeners = new Set<() => void>()

function isAccount(value: unknown): value is DemoAccount {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Record<string, unknown>
  return typeof candidate.email === 'string' && typeof candidate.role === 'string'
}

// @find: parse demo accounts, demo account list
/** Reads the endpoint's answer; anything unexpected is "no demo accounts", never an error. */
export function parseDemoAccounts(body: unknown): DemoAccountsState {
  if (typeof body !== 'object' || body === null) return NONE
  const { accounts, password } = body as { accounts?: unknown; password?: unknown }
  const list = Array.isArray(accounts) ? accounts.filter(isAccount) : []
  if (list.length === 0) return NONE
  return {
    status: 'ready',
    accounts: list.map((account) => ({
      email: account.email,
      displayName: typeof account.displayName === 'string' ? account.displayName : account.email,
      role: account.role,
      describes: typeof account.describes === 'string' ? account.describes : '',
    })),
    password: typeof password === 'string' ? password : null,
  }
}

function settle(asked: number, next: DemoAccountsState) {
  if (asked !== generation) return
  state = next
  for (const listener of listeners) listener()
}

function load() {
  if (request) return
  const asked = generation
  request = fetch(DEMO_ACCOUNTS_URL, { headers: { Accept: 'application/json' } })
    // A 404 means the endpoint is switched off, which is the same as an empty list.
    .then((response) => (response.ok ? response.json() : null))
    .then((body: unknown) => settle(asked, parseDemoAccounts(body)))
    .catch(() => settle(asked, NONE))
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  load()
  return () => {
    listeners.delete(listener)
  }
}

function snapshot(): DemoAccountsState {
  return state
}

function serverSnapshot(): DemoAccountsState {
  return LOADING
}

// @find: use demo accounts; route: GET /api/auth/demo-accounts; used by: Sign in page, public home page
/** The demo accounts this site offers, starting as 'loading' with none. */
export function useDemoAccounts(): DemoAccountsState {
  return useSyncExternalStore(subscribe, snapshot, serverSnapshot)
}

/** True only once the answer is in and lists at least one account. */
export function hasDemoAccounts(demo: DemoAccountsState): boolean {
  return demo.status === 'ready' && demo.accounts.length > 0
}

/** Forgets the remembered answer, so each test starts from a fresh page load. */
export function resetDemoAccountsForTests() {
  generation += 1
  state = LOADING
  request = null
  listeners.clear()
}
