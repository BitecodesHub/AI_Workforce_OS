// @find: prerender script, build public pages, static html for crawlers, write dist pages, robots sitemap llms into dist, SEO build step, GEO, build:public
// @what: Node-only build step: runs the server build of src/entry-server.tsx and writes each public page, the not-found page, the app shell and the crawler files into dist.
// @flow: Run by `npm run build` and `npm run build:public` (infra/launcher/web.Dockerfile) after `vite build` and `vite build --ssr`; reads dist/index.html, dist/.vite/manifest.json and dist-ssr/entry-server.js

import { readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

/*
 * Needs Node and the two Vite builds, nothing else: no browser, so it runs in the web image's
 * build stage as it is. The pages it writes, and where nginx serves them:
 *
 *   index.html   the home page, at / and /home
 *   trust.html   the technical details, at /trust
 *   sign-in.html the sign-in page, at /sign-in
 *   404.html     the not-found page, for any unknown address (status 404, noindex)
 *   app.html     the empty shell, for every signed-in route (noindex)
 *
 * Flat files rather than trust/index.html, so neither nginx nor `vite preview` answers /trust
 * with a redirect to /trust/. The crawler files in dist (copied from web/public by Vite) are
 * written again for the address this build names, with a sitemap lastmod only when SITE_LASTMOD
 * gives a real date that is not in the future (a warning names a value that is refused). The Vite manifest is removed afterwards so it is not published.
 */

const WEB = dirname(dirname(fileURLToPath(import.meta.url)))
const DIST = join(WEB, 'dist')
const SERVER_ENTRY = join(WEB, 'dist-ssr', 'entry-server.js')

/** Reads a file as UTF-8, or fails with a message that says which build step is missing. */
async function readBuilt(path, step) {
  try {
    return await readFile(path, 'utf8')
  } catch {
    throw new Error(`${path} is missing: run ${step} first`)
  }
}

/** Writes every file of a name-to-content map into dist and returns the names written. */
async function writeAll(files) {
  for (const [name, content] of Object.entries(files)) await writeFile(join(DIST, name), content, 'utf8')
  return Object.keys(files)
}

/** The prerender: reads the client build, renders the pages through the server build and writes the results. */
async function main() {
  const template = await readBuilt(join(DIST, 'index.html'), 'vite build')
  if (template.includes('data-prerendered=')) {
    throw new Error('dist/index.html is already prerendered: run vite build again before this script')
  }
  const manifestPath = join(DIST, '.vite', 'manifest.json')
  const manifest = JSON.parse(await readBuilt(manifestPath, 'vite build (with build.manifest on)'))
  await readBuilt(SERVER_ENTRY, 'vite build --ssr src/entry-server.tsx --outDir dist-ssr')
  const server = await import(pathToFileURL(SERVER_ENTRY).href)

  const assets = await readdir(join(DIST, 'assets'))
  const font = server.latinFontFile(assets)
  const pages = await writeAll(server.buildPages({ template, manifest, font }))
  const lastmod = process.env.SITE_LASTMOD || null
  if (lastmod && !server.isIsoDate(lastmod)) {
    console.warn(`prerender: SITE_LASTMOD "${lastmod}" is not a real YYYY-MM-DD date that has begun; the sitemap has no lastmod`)
  }
  const crawler = await writeAll(server.buildCrawlerFiles(server.SITE_URL, lastmod))
  await rm(join(DIST, '.vite'), { recursive: true, force: true })

  console.log(`prerender: ${server.SITE_URL}`)
  console.log(`prerender: wrote ${[...pages, ...crawler].join(', ')}`)
  console.log(`prerender: font preload ${font ?? 'none found'}`)
}

main().catch((error) => {
  console.error(`prerender failed: ${error instanceof Error ? error.message : String(error)}`)
  process.exitCode = 1
})
