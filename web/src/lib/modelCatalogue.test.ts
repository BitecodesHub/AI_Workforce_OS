import { describe, expect, it } from 'vitest'
import {
  capGroups,
  filterModels,
  formatContext,
  formatPrice,
  groupModels,
  mergeSaved,
  shortPrice,
  type CatalogueModel,
} from './modelCatalogue'

const option = (id: string, displayName: string, extra: Partial<CatalogueModel> = {}): CatalogueModel => ({
  id,
  displayName,
  free: false,
  toolCalling: true,
  vision: false,
  contextLength: 128_000,
  pricePerMTokIn: 1,
  pricePerMTokOut: 2,
  ...extra,
})

const OPTIONS = [
  option('anthropic/claude-sonnet-4', 'Anthropic: Claude Sonnet 4', { pricePerMTokIn: 3, pricePerMTokOut: 15, vision: true }),
  option('google/gemma-4-31b-it:free', 'Google: Gemma 4 31B', { free: true, pricePerMTokIn: 0, pricePerMTokOut: 0 }),
  option('meta-llama/llama-3.3-70b-instruct', 'Meta: Llama 3.3 70B Instruct'),
  option('nvidia/nemotron-3-super-120b-a12b:free', 'NVIDIA: Nemotron 3 Super', { free: true }),
]

describe('searching models', () => {
  it('matches every word against the name or the id, in any order', () => {
    expect(filterModels(OPTIONS, 'llama 70').map((model) => model.id)).toEqual(['meta-llama/llama-3.3-70b-instruct'])
    expect(filterModels(OPTIONS, '70B LLAMA').map((model) => model.id)).toEqual(['meta-llama/llama-3.3-70b-instruct'])
    expect(filterModels(OPTIONS, 'a12b').map((model) => model.id)).toEqual(['nvidia/nemotron-3-super-120b-a12b:free'])
  })

  it('finds free and vision models by those words', () => {
    expect(filterModels(OPTIONS, 'free').map((model) => model.id)).toHaveLength(2)
    expect(filterModels(OPTIONS, 'free gemma').map((model) => model.id)).toEqual(['google/gemma-4-31b-it:free'])
    expect(filterModels(OPTIONS, 'vision').map((model) => model.id)).toEqual(['anthropic/claude-sonnet-4'])
  })

  it('returns everything for an empty search and nothing for a miss', () => {
    expect(filterModels(OPTIONS, '  ')).toHaveLength(4)
    expect(filterModels(OPTIONS, 'mixtral')).toEqual([])
  })
})

describe('grouping models', () => {
  it('puts the free group first, each group by name', () => {
    const groups = groupModels(OPTIONS)
    expect(groups.map((group) => group.label)).toEqual(['Free', 'Paid'])
    expect(groups[0]!.options.map((model) => model.displayName)).toEqual(['Google: Gemma 4 31B', 'NVIDIA: Nemotron 3 Super'])
    expect(groups[1]!.options.map((model) => model.displayName)).toEqual([
      'Anthropic: Claude Sonnet 4',
      'Meta: Llama 3.3 70B Instruct',
    ])
  })

  it('leaves out an empty group', () => {
    expect(groupModels(OPTIONS.filter((model) => !model.free)).map((group) => group.key)).toEqual(['paid'])
  })

  it('caps how many are drawn across groups and counts the rest', () => {
    const { groups, hidden } = capGroups(groupModels(OPTIONS), 3)
    expect(groups.flatMap((group) => group.options)).toHaveLength(3)
    expect(groups.map((group) => group.options.length)).toEqual([2, 1])
    expect(hidden).toBe(1)
  })
})

describe('folding in saved models', () => {
  it('adds a saved tool model the list leaves out, never an embedding model or another provider', () => {
    const saved = [
      { providerId: 'nvidia', modelId: 'meta/llama-3.3-70b-instruct', displayName: 'Llama 3.3 70B', contextWindow: 128000, maxOutputTokens: 4096, supportsTools: true, inputCostPerMillion: 0, outputCostPerMillion: 0 },
      { providerId: 'nvidia', modelId: 'embed', displayName: 'Embed', contextWindow: 512, maxOutputTokens: 1, supportsTools: false, inputCostPerMillion: 0, outputCostPerMillion: 0 },
      { providerId: 'groq', modelId: 'llama-fast', displayName: 'Llama Fast', contextWindow: 128000, maxOutputTokens: 4096, supportsTools: true, inputCostPerMillion: 1, outputCostPerMillion: 1 },
    ]
    const merged = mergeSaved('nvidia', [option('qwen/qwen3', 'Qwen3', { free: true })], saved)
    expect(merged.map((model) => model.id)).toEqual(['qwen/qwen3', 'meta/llama-3.3-70b-instruct'])
    expect(merged[1]!.free).toBe(true)
  })
})

describe('wording', () => {
  it('shortens context windows', () => {
    expect(formatContext(128_000)).toBe('128K')
    expect(formatContext(1_048_576)).toBe('1.05M')
    expect(formatContext(2_000_000)).toBe('2M')
    expect(formatContext(0)).toBe('')
  })

  it('prices paid models per million tokens and says nothing for free or unpriced ones', () => {
    expect(shortPrice(OPTIONS[0]!)).toBe('$3 / $15 per 1M')
    expect(formatPrice(OPTIONS[0]!)).toBe('$3 in / $15 out per 1M tokens')
    expect(shortPrice(option('x', 'X', { pricePerMTokIn: 0.075, pricePerMTokOut: 0.3 }))).toBe('$0.075 / $0.3 per 1M')
    expect(shortPrice(OPTIONS[1]!)).toBeNull()
    expect(shortPrice(option('y', 'Y', { pricePerMTokIn: null, pricePerMTokOut: null }))).toBeNull()
  })
})
