// @find: api key, paste key, clean key, recognise key, provider key prefix, wrong provider, Bearer, OPENAI_API_KEY, where to get a key, free tier, key pages
// @what: Cleans a pasted API key and names the provider it looks like, so the wrong key is caught before it is sent.
// @flow: Used by the Settings model provider key form and the setup wizard
/*
 * Making a pasted key easy to get right.
 *
 * People paste keys from password managers, .env files and chat messages, so a paste often carries
 * more than the key: spaces and line breaks, quotes, "Bearer ", or the whole OPENAI_API_KEY=... line.
 * cleanKey takes those off. Most providers also put a recognisable prefix on their keys, so a key
 * pasted for the wrong provider can be named before it is sent: "This looks like an OpenRouter key."
 *
 * Nothing here is a check of the key itself. The prefixes only ever produce a warning; the
 * provider's own answer to one small call decides whether a key works.
 */

type Format = { pattern: RegExp; name: string; providers: readonly string[] }

/** Known prefixes, most specific first: sk-or- and sk-ant- before OpenAI's plain sk-. */
const FORMATS: readonly Format[] = [
  { pattern: /^sk-or-/, name: 'OpenRouter', providers: ['openrouter'] },
  { pattern: /^sk-ant-/, name: 'Anthropic', providers: ['anthropic'] },
  { pattern: /^nvapi-/, name: 'NVIDIA NIM', providers: ['nvidia'] },
  { pattern: /^gsk_/, name: 'Groq', providers: ['groq'] },
  { pattern: /^AIza[0-9A-Za-z_-]{20,}$/, name: 'Google Gemini', providers: ['gemini', 'google'] },
  { pattern: /^xai-/, name: 'xAI', providers: ['xai'] },
  { pattern: /^(ABSK|bedrock-api-key-)/, name: 'Amazon Bedrock API key', providers: ['bedrock'] },
  { pattern: /^(AKIA|ASIA)[A-Z0-9]{12,}$/, name: 'AWS access key ID', providers: ['bedrock'] },
  { pattern: /^(ghp_|gho_|ghs_|github_pat_)/, name: 'GitHub token', providers: ['github'] },
  { pattern: /^xox[abpr]-/, name: 'Slack token', providers: ['slack'] },
  { pattern: /^(sk|rk)_(live|test)_/, name: 'Stripe key', providers: ['stripe'] },
  { pattern: /^lin_api_/, name: 'Linear key', providers: ['linear'] },
  { pattern: /^(secret_|ntn_)/, name: 'Notion token', providers: ['notion'] },
  { pattern: /^(sk-proj-|sk-svcacct-|sk-admin-|sk-[A-Za-z0-9]{20,})/, name: 'OpenAI', providers: ['openai'] },
]

// @find: clean key, strip quotes spaces Bearer, env line
/**
 * The key without what a paste brings along: surrounding whitespace and quotes, a "Bearer " or
 * "Authorization: " in front, a NAME= from an .env line, and line breaks inside a long key.
 */
export function cleanKey(raw: string): string {
  let value = raw.trim()
  // OPENAI_API_KEY=sk-... or export GROQ_API_KEY="gsk_..."
  const envLine = /^(?:export\s+)?[A-Z][A-Z0-9_]*_[A-Z0-9_]*\s*=\s*(.+)$/.exec(value)
  if (envLine?.[1]) value = envLine[1].trim()
  value = value.replace(/^authorization\s*:\s*/i, '').replace(/^bearer\s+/i, '')
  value = value.replace(/^(['"`])(.*)\1$/s, '$2').trim()
  // A key is one unbroken piece of text; a line break inside one is the paste wrapping it.
  return value.replace(/\s+/g, '')
}

// @find: recognise key, which provider is this key
/** Who a key looks like it came from, by its prefix; null when it has none anyone uses. */
export function recogniseKey(value: string): { name: string; providers: readonly string[] } | null {
  const key = cleanKey(value)
  if (!key) return null
  const match = FORMATS.find((format) => format.pattern.test(key))
  return match ? { name: match.name, providers: match.providers } : null
}

// @find: key mismatch, wrong provider key warning
/**
 * A sentence when the key looks like another provider's, said beside the field before anything is
 * sent. Null when it looks right, or when nothing can be told from it (many providers use no prefix).
 *
 * @param providerId the provider the key is being entered for, such as "groq"
 * @param providerName how that provider is named on screen
 */
export function keyMismatch(providerId: string, providerName: string, value: string): string | null {
  const seen = recogniseKey(value)
  if (!seen) return null
  const id = providerId.toLowerCase()
  if (seen.providers.some((provider) => id === provider || id.startsWith(`${provider}-`) || id.includes(provider))) return null
  // An OpenAI-style sk- key is used by too many compatible providers to warn on, except where
  // the provider has a prefix of its own that this is not.
  if (seen.name === 'OpenAI' && !OWN_PREFIX.has(id)) return null
  return `This looks like ${article(seen.name)} ${seen.name}${seen.name.endsWith('key') || seen.name.endsWith('token') || seen.name.endsWith('ID') ? '' : ' key'}, not a key for ${providerName}. Check that you copied the right one.`
}

/** Providers whose keys always carry their own prefix, so an sk- key for them is certainly wrong. */
const OWN_PREFIX = new Set(['openrouter', 'anthropic', 'nvidia', 'groq', 'gemini', 'xai'])

function article(name: string): string {
  return /^[AEIOU]/.test(name) ? 'an' : 'a'
}

/**
 * Whether a paste changed when it was cleaned, so the field can say what was taken off rather than
 * silently sending something other than what is shown.
 */
export function cleanedNote(raw: string): string | null {
  const cleaned = cleanKey(raw)
  if (!raw.trim() || cleaned === raw) return null
  if (cleaned === raw.trim()) return null
  return 'Extra text around the key, such as spaces, quotes or a name=, will be left out.'
}

/** Where each provider's keys are made, for a "Get a key" link beside the field. */
export const KEY_PAGES: Readonly<Record<string, { url: string; free: boolean; note: string }>> = {
  nvidia: { url: 'https://build.nvidia.com/settings/api-keys', free: true, note: 'Free to start, no card needed.' },
  groq: { url: 'https://console.groq.com/keys', free: true, note: 'Free tier with daily limits.' },
  openrouter: { url: 'https://openrouter.ai/settings/keys', free: true, note: 'Has free models; paid ones need credit.' },
  gemini: { url: 'https://aistudio.google.com/apikey', free: true, note: 'Free tier with rate limits.' },
  anthropic: { url: 'https://console.anthropic.com/settings/keys', free: false, note: 'Paid, billed by Anthropic.' },
  openai: { url: 'https://platform.openai.com/api-keys', free: false, note: 'Paid, billed by OpenAI.' },
  bedrock: { url: 'https://console.aws.amazon.com/bedrock/home#/api-keys', free: false, note: 'Billed to your AWS account.' },
}
