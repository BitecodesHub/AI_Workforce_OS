// @find: entry server, server rendering, prerender, static html, renderToString, public pages html, crawlers without javascript, SEO, GEO, renderPage, buildPages
// @what: The build-time entry that renders the public pages (home, technical details, sign-in, not found) to HTML under Node and composes each page's document, plus the crawler files.
// @flow: Built by `vite build --ssr src/entry-server.tsx --outDir dist-ssr` and run by scripts/prerender.mjs; renders routes/Landing, Trust, SignIn and NotFound; uses src/seo and lib/seo

import type { ReactElement } from 'react'
import { renderToString } from 'react-dom/server'
import { StaticRouterProvider } from './lib/router'
import { DEFAULT_DESCRIPTION, SITE_NAME, SITE_URL, privateHead, publicHead } from './lib/seo'
import type { PageHead, PublicPage } from './lib/seo'
import { Landing } from './routes/Landing'
import { Trust } from './routes/Trust'
import { SignIn } from './routes/SignIn'
import { NotFound } from './routes/NotFound'
import { structuredDataFor } from './seo/structuredData'
import { chunkLinks, composeDocument } from './seo/document'
import type { ViteManifest } from './seo/document'
import { llmsFullTxt, llmsTxt, robotsTxt, sitemapXml } from './seo/crawlerFiles'

/*
 * Crawlers that do not run JavaScript, which includes most AI crawlers, would otherwise receive
 * an empty <div id="root">. At build time this module renders each public page to HTML, and the
 * prerender script writes one file per page into dist, which nginx serves at that page's address.
 *
 * The screens are imported directly, not through App: App loads every screen lazily, and a
 * server render would only produce the loading fallback. Each is wrapped in what it needs to
 * render under Node, a fixed router location and nothing else (none of them reads the data
 * client). Browser-only work in these screens happens in effects, which a server render never
 * runs; the reduced-motion and demo-account stores have server snapshots. So the HTML shows every
 * demo at its end state, the way a visitor who asked for reduced motion sees the page.
 */

/** The files the prerender writes, one per page: the three public pages, the not-found page and the empty shell every signed-in route loads. */
export type PageFile = 'index.html' | 'trust.html' | 'sign-in.html' | '404.html' | 'app.html'

/** One page to write: its file, its head, the screen to render (none for the app shell) and the lazy screen chunk whose styles it needs. */
type PageSpec = {
  file: PageFile
  page: PublicPage | 'not-found' | null
  head: PageHead
  render: (() => ReactElement) | null
  /** The source path of the lazily loaded screen, as the Vite manifest names it. */
  chunk: string | null
}

/** The pages, in the order they are written. */
function pageSpecs(site: string): PageSpec[] {
  return [
    {
      file: 'index.html',
      page: 'home',
      head: publicHead('home', site),
      render: () => (
        <StaticRouterProvider path="/">
          <Landing />
        </StaticRouterProvider>
      ),
      chunk: 'src/routes/Landing.tsx',
    },
    {
      file: 'trust.html',
      page: 'trust',
      head: publicHead('trust', site),
      render: () => (
        <StaticRouterProvider path="/trust">
          <Trust />
        </StaticRouterProvider>
      ),
      chunk: 'src/routes/Trust.tsx',
    },
    {
      file: 'sign-in.html',
      page: 'sign-in',
      head: publicHead('sign-in', site),
      render: () => (
        <StaticRouterProvider path="/sign-in">
          <SignIn />
        </StaticRouterProvider>
      ),
      chunk: null,
    },
    {
      file: '404.html',
      page: 'not-found',
      head: privateHead('Not found'),
      // As App renders an unknown address for a signed-out visitor.
      render: () => (
        <main id="main" tabIndex={-1}>
          <NotFound />
        </main>
      ),
      chunk: null,
    },
    {
      file: 'app.html',
      page: null,
      // The app names the tab after the screen as soon as it runs.
      head: { title: SITE_NAME, description: DEFAULT_DESCRIPTION, canonical: null, index: false },
      render: null,
      chunk: null,
    },
  ]
}

// @find: render page to html string
/** The markup of one screen, rendered under Node. */
export function renderScreen(render: () => ReactElement): string {
  return renderToString(render())
}

/** What the prerender script hands over from the client build. */
export type BuildInputs = {
  /** dist/index.html as Vite wrote it. */
  template: string
  /** dist/.vite/manifest.json, parsed. */
  manifest: ViteManifest
  /** The Latin typeface file to preload (an absolute path), or null. */
  font: string | null
  /** The public address; defaults to the one this build was configured with. */
  site?: string
}

// @find: build pages, prerendered documents
/** Every page's finished HTML, by file name. */
export function buildPages(inputs: BuildInputs): Record<PageFile, string> {
  const site = inputs.site ?? SITE_URL
  const pages = {} as Record<PageFile, string>
  for (const spec of pageSpecs(site)) {
    const links = spec.chunk ? chunkLinks(inputs.manifest, [spec.chunk]) : { styles: [], modules: [] }
    const isPublic = spec.page === 'home' || spec.page === 'trust' || spec.page === 'sign-in'
    pages[spec.file] = composeDocument(inputs.template, {
      head: spec.head,
      site,
      markup: spec.render ? renderScreen(spec.render) : '',
      ...(spec.page ? { page: spec.page } : {}),
      structuredData: isPublic ? structuredDataFor(spec.page as PublicPage, site) : null,
      styles: links.styles,
      modules: links.modules,
      // The shell paints nothing in the typeface before the app runs, so it skips the preload.
      font: spec.render ? inputs.font : null,
    })
  }
  return pages
}

// @find: crawler files for build, robots sitemap llms in dist
/** robots.txt, sitemap.xml, llms.txt and llms-full.txt for the public address, by file name. */
export function buildCrawlerFiles(site: string = SITE_URL, lastmod?: string | null): Record<string, string> {
  return {
    'robots.txt': robotsTxt(site),
    'sitemap.xml': sitemapXml(site, lastmod ?? null),
    'llms.txt': llmsTxt(site),
    'llms-full.txt': llmsFullTxt(site),
  }
}

export { SITE_URL }
export { latinFontFile } from './seo/document'
