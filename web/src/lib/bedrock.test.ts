// @find: tests for Amazon Bedrock, bedrockCredentialValue, bedrockFormComplete, groupRegions, region list, AWS access key, Connect your AI dialog
// @what: Unit tests for the Bedrock credential and region list helpers.
import { describe, expect, it } from 'vitest'
import {
  BEDROCK_REGIONS,
  EMPTY_BEDROCK_FORM,
  bedrockCredentialValue,
  bedrockFormComplete,
  isBedrock,
} from './bedrock'

describe('the Bedrock credential', () => {
  it('sends only the chosen sign-in’s fields, trimmed, with the region', () => {
    const form = {
      ...EMPTY_BEDROCK_FORM,
      apiKey: ' ABSKkey ',
      accessKeyId: 'AKIAEXAMPLE',
      secretAccessKey: 'secret',
      region: 'ap-southeast-2',
    }
    expect(JSON.parse(bedrockCredentialValue(form))).toEqual({ type: 'api_key', apiKey: 'ABSKkey', region: 'ap-southeast-2' })
    expect(JSON.parse(bedrockCredentialValue({ ...form, auth: 'access_key' }))).toEqual({
      type: 'access_key',
      accessKeyId: 'AKIAEXAMPLE',
      secretAccessKey: 'secret',
      region: 'ap-southeast-2',
    })
  })

  it('is complete only with what the sign-in needs', () => {
    expect(bedrockFormComplete(EMPTY_BEDROCK_FORM)).toBe(false)
    expect(bedrockFormComplete({ ...EMPTY_BEDROCK_FORM, apiKey: 'k' })).toBe(true)
    expect(bedrockFormComplete({ ...EMPTY_BEDROCK_FORM, auth: 'access_key', accessKeyId: 'a' })).toBe(false)
    expect(bedrockFormComplete({ ...EMPTY_BEDROCK_FORM, auth: 'access_key', accessKeyId: 'a', secretAccessKey: 's' })).toBe(true)
  })

  it('defaults to us-east-1 and offers each region once', () => {
    expect(EMPTY_BEDROCK_FORM.region).toBe('us-east-1')
    const ids = BEDROCK_REGIONS.map((region) => region.id)
    expect(new Set(ids).size).toBe(ids.length)
    expect(ids).toContain('eu-west-1')
    expect(isBedrock({ kind: 'bedrock' })).toBe(true)
    expect(isBedrock({ kind: 'OPENAI_COMPATIBLE' })).toBe(false)
  })
})

describe('the region list', () => {
  it('labels a region by its console name and id', async () => {
    const { regionLabel } = await import('./bedrock')
    expect(regionLabel('us-east-1')).toBe('US East (N. Virginia) — us-east-1')
    expect(regionLabel('xx-nowhere-9')).toBe('xx-nowhere-9')
  })

  it('groups by geography in the console’s order, and narrows by every word typed', async () => {
    const { groupRegions } = await import('./bedrock')
    expect(groupRegions('').map((group) => group.geography)).toEqual([
      'United States',
      'Americas',
      'Europe',
      'Middle East',
      'Asia Pacific',
      'AWS GovCloud',
    ])
    expect(groupRegions('asia sydney').flatMap((group) => group.regions.map((region) => region.id))).toEqual(['ap-southeast-2'])
    expect(groupRegions('eu-west').flatMap((group) => group.regions.map((region) => region.id))).toEqual([
      'eu-west-1',
      'eu-west-2',
      'eu-west-3',
    ])
    expect(groupRegions('atlantis')).toEqual([])
  })
})
