// @find: tests for router, redirectFor, match, route parameters, old address redirects
// @what: Unit tests for route matching and old-address redirects.
// @find: tests for router, page title, setPageTitle, attention count
// @what: Unit tests for the router page title.
import { describe, expect, it } from 'vitest'
import { match, redirectFor } from './router'

describe('redirectFor', () => {
  it('sends the old Integrations address to Connectors', () => {
    expect(redirectFor('/integrations')).toBe('/connectors')
    expect(redirectFor('/integrations/')).toBe('/connectors')
  })

  it('leaves every address that has not moved alone', () => {
    expect(redirectFor('/connectors')).toBeNull()
    expect(redirectFor('/')).toBeNull()
    expect(redirectFor('/agents/integrations')).toBeNull()
  })
})

describe('match', () => {
  it('reads route parameters, and refuses a path of another shape', () => {
    expect(match('/agents/:id', '/agents/a1')).toEqual({ id: 'a1' })
    expect(match('/connectors', '/connectors')).toEqual({})
    expect(match('/agents/:id', '/agents')).toBeNull()
    expect(match('/agents/:id', '/agents/%E0%A4%A')).toBeNull()
  })
})
