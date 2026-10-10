// @find: model picker, model catalogue, free and paid models, search models, group models, GET /api/providers/{id}/models, GET /api/providers/models, saved models, price, context size, mergeSaved, groupModels, filterModels, Model Routing page
// @what: Search, grouping and wording for the model picker options.
// @flow: Used by ModelCombobox.tsx and CandidateModelField.tsx.
/*
 * The model picker's options: a provider's full model list (GET /api/providers/{id}/models), with
 * the workspace's saved models for that provider folded in, searched and grouped "Free" first.
 *
 * Pure, so the search, the grouping and the wording can be tested without a component.
 */

/** One model as GET /api/providers/{id}/models returns it. */
export type CatalogueModel = {
  id: string
  displayName: string
  free: boolean
  toolCalling: boolean
  vision: boolean
  /** Tokens; 0 when the provider does not say. */
  contextLength: number
  /** US dollars per million tokens; null when the provider does not list a price. */
  pricePerMTokIn?: number | null
  pricePerMTokOut?: number | null
  pricingNote?: string | null
  /**
   * True for a saved model the provider's own list leaves out: the provider does not offer it to
   * this account's key, so a run would be refused on it. Only ever set when the list came from
   * the provider (live or cached), never from the saved list alone.
   */
  unavailable?: boolean
}

/** The words for a model the provider does not offer to this account. */
export const NOT_AVAILABLE = 'Not available to this account'

export type ProviderCatalogue = {
  providerId: string
  providerName: string
  /** live: fetched now; cached: fetched within the last few hours; saved: the provider was not asked or did not answer. */
  source: 'live' | 'cached' | 'saved'
  fetchedAt?: string | null
  /** A plain sentence when the list is not fresh from the provider. */
  message?: string | null
  total: number
  freeCount: number
  models: CatalogueModel[]
}

/** A saved catalogue model (GET /api/providers/models), the shape the picker borrows from. */
export type SavedModel = {
  providerId: string
  modelId: string
  displayName: string
  contextWindow: number
  maxOutputTokens: number
  supportsTools: boolean
  inputCostPerMillion: number
  outputCostPerMillion: number
}

export type ModelGroup = { key: 'free' | 'paid' | 'unavailable'; label: string; options: CatalogueModel[] }

// @find: merge saved models into the provider list; used by: CandidateModelField
/**
 * The catalogue's models, plus saved models of the provider it does not list. Embedding models
 * and models that cannot call tools are never added from the saved list.
 *
 * With no catalogue (live off, or the list not loaded) every saved model is offered. With one
 * the provider itself answered (`fromProvider`), a saved model it leaves out is not offered to
 * this account: it is added only when it is `keep` (already chosen, so the field can say so), and
 * then marked unavailable rather than looking like a working choice.
 */
export function mergeSaved(
  providerId: string,
  catalogue: readonly CatalogueModel[] | undefined,
  saved: readonly SavedModel[],
  options: { fromProvider?: boolean; keep?: string } = {},
): CatalogueModel[] {
  const out = [...(catalogue ?? [])]
  const seen = new Set(out.map((model) => model.id))
  const authoritative = Boolean(catalogue && options.fromProvider)
  for (const model of saved) {
    if (model.providerId !== providerId || seen.has(model.modelId)) continue
    if (model.maxOutputTokens <= 1 || !model.supportsTools) continue
    if (authoritative && model.modelId !== options.keep) continue
    const zero = model.inputCostPerMillion === 0 && model.outputCostPerMillion === 0
    out.push({
      id: model.modelId,
      displayName: model.displayName,
      free: zero,
      toolCalling: true,
      vision: false,
      contextLength: model.contextWindow,
      pricePerMTokIn: model.inputCostPerMillion,
      pricePerMTokOut: model.outputCostPerMillion,
      ...(authoritative ? { unavailable: true } : {}),
    })
    seen.add(model.modelId)
  }
  return out
}

// @find: first available model, default pick
/**
 * The model to fill in when a provider is picked for a row: the first the provider offers to this
 * account, free first, by name. Undefined while there is nothing available to choose.
 */
export function firstAvailable(options: readonly CatalogueModel[]): CatalogueModel | undefined {
  return groupModels(options.filter((option) => !option.unavailable))[0]?.options[0]
}

const fold = (value: string) =>
  value
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')

// @find: search models by text; used by: ModelCombobox
/**
 * The options that match what was typed. Every word must appear in the name or the id, in any
 * order, so "llama 70" finds "Llama 3.3 70B" and "free gemma" finds the free Gemma models.
 */
export function filterModels(options: readonly CatalogueModel[], query: string): CatalogueModel[] {
  const words = fold(query).split(/\s+/).filter(Boolean)
  if (words.length === 0) return [...options]
  return options.filter((option) => {
    const haystack = `${fold(option.displayName)} ${fold(option.id)}${option.free ? ' free' : ''}${option.vision ? ' vision' : ''}`
    return words.every((word) => haystack.includes(word))
  })
}

const byName = (a: CatalogueModel, b: CatalogueModel) =>
  a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base', numeric: true }) || a.id.localeCompare(b.id)

// @find: group models free paid not available; used by: ModelCombobox
/** Free models first, then paid, each by name, then any not available to this account. Empty groups are left out. */
export function groupModels(options: readonly CatalogueModel[]): ModelGroup[] {
  const offered = options.filter((option) => !option.unavailable)
  const free = offered.filter((option) => option.free).sort(byName)
  const paid = offered.filter((option) => !option.free).sort(byName)
  const unavailable = options.filter((option) => option.unavailable).sort(byName)
  const groups: ModelGroup[] = []
  if (free.length > 0) groups.push({ key: 'free', label: 'Free', options: free })
  if (paid.length > 0) groups.push({ key: 'paid', label: 'Paid', options: paid })
  if (unavailable.length > 0) groups.push({ key: 'unavailable', label: NOT_AVAILABLE, options: unavailable })
  return groups
}

// @find: limit models shown per group, show more
/**
 * Caps how many options are drawn, across groups in order, so a list of several hundred stays
 * quick to open and to type into. `hidden` is how many matched but were not drawn.
 */
export function capGroups(groups: readonly ModelGroup[], limit: number): { groups: ModelGroup[]; hidden: number } {
  let left = limit
  let hidden = 0
  const out: ModelGroup[] = []
  for (const group of groups) {
    const shown = group.options.slice(0, Math.max(0, left))
    hidden += group.options.length - shown.length
    left -= shown.length
    if (shown.length > 0) out.push({ ...group, options: shown })
  }
  return { groups: out, hidden }
}

// @find: context size wording, 128K, 1M
/** "128K", "1M", "1.05M"; empty when not known. */
export function formatContext(tokens: number): string {
  if (!tokens || tokens <= 0) return ''
  if (tokens >= 1_000_000) {
    const millions = tokens / 1_000_000
    return `${Number(millions.toFixed(millions >= 10 ? 0 : 2))}M`
  }
  if (tokens >= 1000) return `${Math.round(tokens / 1000)}K`
  return String(tokens)
}

const money = (value: number) => {
  if (value === 0) return '$0'
  if (value < 1) return `$${Number(value.toPrecision(2))}`
  return `$${Number(value.toFixed(2))}`
}

// @find: model price wording per 1M tokens
/** "$3 in / $15 out per 1M tokens"; null when the provider lists no price. */
export function formatPrice(option: CatalogueModel): string | null {
  const input = option.pricePerMTokIn
  const output = option.pricePerMTokOut
  if (option.free || input == null || output == null) return null
  return `${money(input)} in / ${money(output)} out per 1M tokens`
}

// @find: short price tag beside a paid model
/** The short price tag beside a paid option: "$3 / $15 per 1M". */
export function shortPrice(option: CatalogueModel): string | null {
  const input = option.pricePerMTokIn
  const output = option.pricePerMTokOut
  if (option.free || input == null || output == null) return null
  return `${money(input)} / ${money(output)} per 1M`
}

// @find: model option summary text
/** What a person hears and sees summarised for one option. */
export function optionSummary(option: CatalogueModel): string {
  const parts = [option.displayName]
  if (option.unavailable) parts.push(NOT_AVAILABLE.toLowerCase())
  if (option.free) parts.push('free')
  const context = formatContext(option.contextLength)
  if (context) parts.push(`${context} context`)
  if (option.vision) parts.push('reads images')
  const price = formatPrice(option)
  if (price) parts.push(price)
  return parts.join(', ')
}
