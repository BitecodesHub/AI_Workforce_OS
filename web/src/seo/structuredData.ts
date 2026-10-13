// @find: structured data, JSON-LD, schema.org, Organization, WebSite, SoftwareApplication, FAQPage, BreadcrumbList, WebPage, rich results, answer engines, structuredDataFor
// @what: Builds the schema.org JSON-LD graph each prerendered public page carries, from the same facts and FAQ the page shows.
// @flow: Called by src/entry-server.tsx; scripts/prerender.mjs writes the result into each page's head as a script of type application/ld+json

import { FAQ_ITEMS } from '../components/landing/sections/faqData'
import type { FaqItem } from '../components/landing/sections/faqData'
import {
  BUILT_IN_ROLE_COUNT,
  CONNECTOR_COUNT,
  CONNECTOR_LABEL,
  PERMISSION_CODE_COUNT,
  PROVIDERS,
  listOf,
} from '../components/landing/shared/landingFacts'
import { OG_IMAGE, PRODUCT_DEFINITION, PUBLIC_PAGES, SITE_NAME, absoluteUrl, fullTitle } from '../lib/seo'
import type { PublicPage } from '../lib/seo'

/*
 * What search engines and AI answer engines can read about the product without guessing from
 * the layout. Every value is something the page itself states, or a fact from landingFacts, which
 * the page states too.
 *
 * Deliberately absent: offers, price, aggregateRating, review and award. The site shows none of
 * them, so stating them would be invented. Without a price and a rating Google shows no software
 * rich result, which is the honest outcome; the entity data is still read.
 *
 * Google shows FAQ rich results only for well-known government and health sites, so the FAQPage
 * is for answer engines and Bing. It is built from FAQ_ITEMS, the list the page renders, so it
 * cannot say something the page does not.
 *
 * The Organization is named after the product until the publisher's legal name is confirmed; it
 * has no sameAs links for the same reason.
 */

/** A JSON-LD node: plain JSON values only. */
export type JsonLdNode = { [key: string]: JsonValue }
type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue }

/** The full JSON-LD document for one page. */
export type JsonLdGraph = { '@context': 'https://schema.org'; '@graph': JsonLdNode[] }

// @find: feature list, what the product does, structured data features
/** The product's features as the public pages state them, for SoftwareApplication.featureList. */
export function featureList(): string[] {
  return [
    'AI assistants that draft emails, sort customer questions and prepare reports',
    'Requests in plain words, typed or spoken',
    'Sending an email, posting a message or deleting something waits for a person who is allowed to approve it',
    'Answers from your own documents, showing the page each point came from',
    `Works with ${PROVIDERS.length} AI providers (${listOf([...PROVIDERS])}) and moves on to the next when one is busy`,
    `${CONNECTOR_COUNT} connectors, including ${CONNECTOR_LABEL.gmail}, ${CONNECTOR_LABEL.calendar}, ${CONNECTOR_LABEL.outlook}, ${CONNECTOR_LABEL.slack} and ${CONNECTOR_LABEL.salesforce}, that connect once an owner or admin sets them up and use practice data until then`,
    `${BUILT_IN_ROLE_COUNT} built-in roles composed from ${PERMISSION_CODE_COUNT} permissions`,
    'A tamper-evident record of every approval decision and finished job',
    'A built-in offline sandbox model, so it can be tried without an AI provider key',
  ]
}

/** The @id of a node, so nodes can point at each other across the graph. */
function idFor(site: string, name: 'organization' | 'website' | 'software'): string {
  return `${site}/#${name}`
}

/** The link-preview image as a schema.org ImageObject. */
function imageObject(site: string): JsonLdNode {
  return {
    '@type': 'ImageObject',
    url: absoluteUrl(OG_IMAGE.path, site),
    width: OG_IMAGE.width,
    height: OG_IMAGE.height,
    caption: OG_IMAGE.alt,
  }
}

/** The nodes every public page shares: who publishes it, the site and the product. */
function sharedNodes(site: string): JsonLdNode[] {
  const home = absoluteUrl('/', site)
  return [
    {
      '@type': 'Organization',
      '@id': idFor(site, 'organization'),
      name: SITE_NAME,
      url: home,
      logo: { '@type': 'ImageObject', url: absoluteUrl('/icons/icon-512.png', site), width: 512, height: 512 },
    },
    {
      '@type': 'WebSite',
      '@id': idFor(site, 'website'),
      name: SITE_NAME,
      url: home,
      description: PUBLIC_PAGES.home.description,
      inLanguage: 'en',
      publisher: { '@id': idFor(site, 'organization') },
    },
    {
      '@type': 'SoftwareApplication',
      '@id': idFor(site, 'software'),
      name: SITE_NAME,
      url: home,
      description: PRODUCT_DEFINITION,
      applicationCategory: 'BusinessApplication',
      operatingSystem: 'Web browser',
      image: imageObject(site),
      featureList: featureList(),
      publisher: { '@id': idFor(site, 'organization') },
    },
  ]
}

// @find: faq structured data, FAQPage mainEntity, Question, Answer
/** schema.org Questions for FAQ items, word for word as the page shows them. */
export function faqEntities(items: ReadonlyArray<FaqItem> = FAQ_ITEMS): JsonLdNode[] {
  return items.map((item) => ({
    '@type': 'Question',
    name: item.q,
    acceptedAnswer: { '@type': 'Answer', text: item.a },
  }))
}

/** The node for the page itself: an FAQPage for the home page, a WebPage otherwise. */
function pageNode(page: PublicPage, site: string): JsonLdNode {
  const meta = PUBLIC_PAGES[page]
  const url = absoluteUrl(meta.path, site)
  const node: JsonLdNode = {
    '@type': page === 'home' ? 'FAQPage' : 'WebPage',
    '@id': `${url}#webpage`,
    url,
    name: fullTitle(meta.screen),
    description: meta.description,
    inLanguage: 'en',
    isPartOf: { '@id': idFor(site, 'website') },
    about: { '@id': idFor(site, 'software') },
    primaryImageOfPage: imageObject(site),
  }
  if (page === 'home') node.mainEntity = faqEntities()
  if (page === 'trust') node.breadcrumb = { '@id': `${url}#breadcrumb` }
  return node
}

/** Home, then the technical page: the trail a results page can show for /trust. */
function trustBreadcrumb(site: string): JsonLdNode {
  const url = absoluteUrl(PUBLIC_PAGES.trust.path, site)
  return {
    '@type': 'BreadcrumbList',
    '@id': `${url}#breadcrumb`,
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Home', item: absoluteUrl('/', site) },
      { '@type': 'ListItem', position: 2, name: PUBLIC_PAGES.trust.screen, item: url },
    ],
  }
}

// @find: structured data for page, JSON-LD graph per page
/** The JSON-LD graph for one public page. The sign-in page carries only the shared nodes and its WebPage. */
export function structuredDataFor(page: PublicPage, site: string): JsonLdGraph {
  const nodes = [...sharedNodes(site), pageNode(page, site)]
  if (page === 'trust') nodes.push(trustBreadcrumb(site))
  return { '@context': 'https://schema.org', '@graph': nodes }
}
