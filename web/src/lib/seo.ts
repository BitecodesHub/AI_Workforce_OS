// @find: seo, search engines, page title, meta description, canonical link, robots noindex, link preview, open graph, twitter card, site address, SITE_URL, PUBLIC_PAGES, headFor, applyPageHead, data-prerendered
// @what: One source for what each page tells search engines and link previews (title, description, canonical address, index or noindex), and the writer that keeps the document head in step as the app moves between screens.
// @flow: Used by App.tsx on every route change and by src/entry-server.tsx, which scripts/prerender.mjs runs at build time to write the public pages as HTML

/*
 * Search engines and AI answer engines read three public pages: the home page, the technical
 * details for IT teams and the sign-in page. Everything else is the signed-in app, which must
 * never be indexed: those screens are marked noindex here and, for crawlers that do not run
 * JavaScript, by nginx (X-Robots-Tag) and in the HTML shell the build writes for them.
 *
 * The same strings are written into the HTML at build time and into the head by App.tsx when the
 * app takes over, so the tab title never changes as the page loads and a crawler that runs
 * JavaScript sees the same answer as one that does not.
 */

/** The product's name, as every title ends and every link preview names the site. */
export const SITE_NAME = 'AI Workforce OS'

/** The public address the canonical links, the sitemap and the link previews use unless the build names another. */
export const DEFAULT_SITE_URL = 'https://ai-workforce.bitecodes.com'

// @find: normalise site url, VITE_SITE_URL, build argument SITE_URL
/** An http(s) origin without a trailing slash, or the default address when the value is missing or is not one. */
export function normaliseSiteUrl(value: string | undefined | null): string {
  const trimmed = (value ?? '').trim().replace(/\/+$/, '')
  return /^https?:\/\/[a-z0-9.-]+(:\d+)?$/i.test(trimmed) ? trimmed : DEFAULT_SITE_URL
}

/**
 * The public address, from VITE_SITE_URL at build time (infra/launcher/web.Dockerfile passes the
 * SITE_URL build argument through), else the default.
 */
export const SITE_URL = normaliseSiteUrl(import.meta.env.VITE_SITE_URL as string | undefined)

/**
 * One sentence that says what the product is, for answer engines to quote: the structured data,
 * llms.txt and the link preview of the home page all use it.
 */
export const PRODUCT_DEFINITION =
  'AI Workforce OS is a web app that gives a business its own team of AI assistants: they draft emails, sort customer questions, prepare reports and keep routine work moving, and nothing is sent, posted or deleted until a person approves it.'

/**
 * The link-preview image (public/og-image.png): the brand mark, without text, 1200 by 630 pixels.
 * Link previews need its absolute address, which absoluteUrl(OG_IMAGE.path) gives.
 */
export const OG_IMAGE = {
  path: '/og-image.png',
  width: 1200,
  height: 630,
  type: 'image/png',
  alt: 'AI Workforce OS logo: a blue L-shaped mark with a square dot on a pale blue background',
} as const

/** The description of every page that has none of its own: the signed-in screens and the not-found page. */
export const DEFAULT_DESCRIPTION = 'AI assistants that do routine work and ask before anything is sent.'

/** The public pages a search engine may index. */
export type PublicPage = 'home' | 'trust' | 'sign-in'

/** What a public page tells search engines: its part of the title, its description and its canonical path. */
export type PageMeta = {
  /** The screen's part of the tab title; the full title adds " · AI Workforce OS". */
  screen: string
  /** The meta description, at most about 160 characters so a results page shows all of it. */
  description: string
  /** The canonical path on SITE_URL. "/home" shows the same page as "/", so both name "/". */
  path: string
}

// @find: public pages meta, home title, trust title, sign-in title, descriptions
/** The three public pages. App.tsx's tab titles read these, so the title written at build time is the one the app keeps. */
export const PUBLIC_PAGES: Readonly<Record<PublicPage, PageMeta>> = {
  home: {
    screen: 'AI employees for your business',
    description:
      'AI assistants that draft emails, sort customer questions and prepare reports for your business. Nothing is sent or deleted until a person says yes.',
    path: '/',
  },
  trust: {
    screen: 'Security and technical details',
    description:
      'For IT and security teams: how AI Workforce OS keeps agents in check, with an approval gate, provider failover, a tamper-evident audit chain and cited answers.',
    path: '/trust',
  },
  'sign-in': {
    screen: 'Sign in',
    description: 'Sign in to your AI Workforce OS workspace, or create a workspace for your company.',
    path: '/sign-in',
  },
}

/** Everything the head says about one page. */
export type PageHead = {
  /** The full document title. */
  title: string
  description: string
  /** The absolute canonical address, or null for a page that is not indexed. */
  canonical: string | null
  /** False for every signed-in screen and the not-found page. */
  index: boolean
}

// @find: full page title, title with product name
/** The document title for a screen: "Approvals · AI Workforce OS". */
export function fullTitle(screen: string): string {
  return `${screen} · ${SITE_NAME}`
}

// @find: canonical url, absolute address
/** The absolute address of a path on the public site. */
export function absoluteUrl(path: string, site: string = SITE_URL): string {
  return `${site}${path.startsWith('/') ? path : `/${path}`}`
}

// @find: head for page, public page head, noindex head
/** The head of a public page, indexed with its canonical address. */
export function publicHead(page: PublicPage, site: string = SITE_URL): PageHead {
  const meta = PUBLIC_PAGES[page]
  return { title: fullTitle(meta.screen), description: meta.description, canonical: absoluteUrl(meta.path, site), index: true }
}

/** The head of a page that is not indexed: a signed-in screen or the not-found page. */
export function privateHead(screen: string): PageHead {
  return { title: fullTitle(screen), description: DEFAULT_DESCRIPTION, canonical: null, index: false }
}

// @find: public page for route pattern
/** The public page a route pattern shows, or null for every other route. App.tsx passes "/home" for a signed-out visit to "/". */
export function publicPageFor(pattern: string): PublicPage | null {
  if (pattern === '/home') return 'home'
  if (pattern === '/trust') return 'trust'
  if (pattern === '/sign-in') return 'sign-in'
  return null
}

/** Sets, or with null removes, the head element `<tag attr="key" ...>` and its `field` value. */
function writeHeadElement(tag: 'meta' | 'link', attr: 'name' | 'property' | 'rel', key: string, field: 'content' | 'href', value: string | null): void {
  const selector = `${tag}[${attr}="${key}"]`
  let element = document.head.querySelector<HTMLElement>(selector)
  if (value === null) {
    element?.remove()
    return
  }
  if (!element) {
    element = document.createElement(tag)
    element.setAttribute(attr, key)
    document.head.appendChild(element)
  }
  element.setAttribute(field, value)
}

// @find: apply page head, update meta description, update canonical, set noindex on client
/**
 * Brings the description, canonical link, robots rule and link-preview text in the document head
 * in step with the page shown. The title is written separately (router setPageTitle), because
 * the signed-in shell adds a waiting count to it. A crawler that runs JavaScript reads these.
 */
export function applyPageHead(head: PageHead): void {
  if (typeof document === 'undefined') return
  writeHeadElement('meta', 'name', 'description', 'content', head.description)
  writeHeadElement('link', 'rel', 'canonical', 'href', head.canonical)
  writeHeadElement('meta', 'name', 'robots', 'content', head.index ? null : 'noindex')
  writeHeadElement('meta', 'property', 'og:title', 'content', head.title)
  writeHeadElement('meta', 'property', 'og:description', 'content', head.description)
  writeHeadElement('meta', 'property', 'og:url', 'content', head.canonical)
  writeHeadElement('meta', 'name', 'twitter:title', 'content', head.title)
  writeHeadElement('meta', 'name', 'twitter:description', 'content', head.description)
}

// @find: prerendered mark, data-prerendered, skip entrance animation, leave prerendered page
/**
 * The build marks a page it wrote ahead of time with data-prerendered on <html>, and the landing
 * styles skip their first entrance there, so content already on screen does not fade out and in
 * again when the app takes over. After the first move to another page the mark has done its job.
 */
export function clearPrerenderedMark(): void {
  if (typeof document === 'undefined') return
  document.documentElement.removeAttribute('data-prerendered')
}
