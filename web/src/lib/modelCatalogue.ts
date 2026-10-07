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
}

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

export type ModelGroup = { key: 'free' | 'paid'; label: string; options: CatalogueModel[] }

/**
 * The catalogue's models, plus any saved model of the provider it does not list (a seeded model
 * the provider's list leaves out still works and may be in a policy already). Embedding models
 * and models that cannot call tools are never added from the saved list.
 */
export function mergeSaved(
  providerId: string,
  catalogue: readonly CatalogueModel[] | undefined,
  saved: readonly SavedModel[],
): CatalogueModel[] {
  const out = [...(catalogue ?? [])]
  const seen = new Set(out.map((model) => model.id))
  for (const model of saved) {
    if (model.providerId !== providerId || seen.has(model.modelId)) continue
    if (model.maxOutputTokens <= 1 || !model.supportsTools) continue
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
    })
    seen.add(model.modelId)
  }
  return out
}

const fold = (value: string) =>
  value
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')

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

/** Free models first, then paid, each by name. Empty groups are left out. */
export function groupModels(options: readonly CatalogueModel[]): ModelGroup[] {
  const free = options.filter((option) => option.free).sort(byName)
  const paid = options.filter((option) => !option.free).sort(byName)
  const groups: ModelGroup[] = []
  if (free.length > 0) groups.push({ key: 'free', label: 'Free', options: free })
  if (paid.length > 0) groups.push({ key: 'paid', label: 'Paid', options: paid })
  return groups
}

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

/** "$3 in / $15 out per 1M tokens"; null when the provider lists no price. */
export function formatPrice(option: CatalogueModel): string | null {
  const input = option.pricePerMTokIn
  const output = option.pricePerMTokOut
  if (option.free || input == null || output == null) return null
  return `${money(input)} in / ${money(output)} out per 1M tokens`
}

/** The short price tag beside a paid option: "$3 / $15 per 1M". */
export function shortPrice(option: CatalogueModel): string | null {
  const input = option.pricePerMTokIn
  const output = option.pricePerMTokOut
  if (option.free || input == null || output == null) return null
  return `${money(input)} / ${money(output)} per 1M`
}

/** What a person hears and sees summarised for one option. */
export function optionSummary(option: CatalogueModel): string {
  const parts = [option.displayName]
  if (option.free) parts.push('free')
  const context = formatContext(option.contextLength)
  if (context) parts.push(`${context} context`)
  if (option.vision) parts.push('reads images')
  const price = formatPrice(option)
  if (price) parts.push(price)
  return parts.join(', ')
}
