// @find: tests for TestNow, test model, test now, try model, test routing, model latency, test candidate, Test button
// @what: Automated tests for TestNow.
// @flow: Run with the web test runner; covers TestNow.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TestNow } from './TestNow'

function answer(result: string, message: string, latencyMs: number) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      new Response(
        JSON.stringify({ providerId: 'groq', modelId: 'm', modelName: 'M', result, message, latencyMs, checkedAt: '2026-10-08T00:00:00Z' }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    ),
  )
}

async function test() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { mutations: { retry: false } } })}>
      <TestNow providerId="groq" modelId="m" name="M" />
    </QueryClientProvider>,
  )
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Test M now' }))
  })
}

afterEach(() => vi.unstubAllGlobals())

describe('TestNow', () => {
  it('says how long a model that answered took once, in the service’s own sentence', async () => {
    answer('ok', 'Groq · M answered in 317 ms.', 317)
    await test()
    expect(await screen.findByText('Groq · M answered in 317 ms.')).toBeInTheDocument()
  })

  it('adds the time to a failure, whose sentence does not carry it', async () => {
    answer('not_found', 'Groq does not offer M to this account.', 79)
    await test()
    expect(await screen.findByText('Groq does not offer M to this account. (79 ms)')).toBeInTheDocument()
  })
})
