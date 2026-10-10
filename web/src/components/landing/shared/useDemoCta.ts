// @find: demo cta, try the demo button, see it work, demo accounts, /sign-in, useDemoCta
// @what: Hook choosing the demo button label and link depending on whether demo accounts exist.
// @flow: Used by Hero, LandingBar, FinalCta
import { hasDemoAccounts, useDemoAccounts } from '../../../lib/demo'
import { CTA } from './landingFacts'

/*
 * The public pages' demo button: "Try the demo", to the sign-in page's demo accounts, only once
 * this site has said it offers them; otherwise "See it work", to the simulated demos on the page.
 *
 * The safe variant is what renders first, while the site has not answered yet, so a visitor on a
 * site without demo accounts never sees an offer appear and then be withdrawn. Pair the link with
 * useInPageLink's handler: it scrolls to #demos and leaves /sign-in to the browser.
 */

export type DemoCta = {
  label: string
  href: string
  /** True when the button leads to real demo accounts rather than the demos on this page. */
  accounts: boolean
}

// @find: useDemoCta hook, demo button, demo accounts
export function useDemoCta(): DemoCta {
  const demo = useDemoAccounts()
  return hasDemoAccounts(demo) ? { ...CTA.demo, accounts: true } : { ...CTA.seeItWork, accounts: false }
}
