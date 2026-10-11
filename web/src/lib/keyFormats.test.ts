// @find: tests for key formats, cleanKey, recogniseKey, keyMismatch
// @what: Unit tests for pasted key cleaning and recognition.
import { describe, expect, it } from 'vitest'
import { accountSidProblem, cleanKey, cleanedNote, keyMismatch, recogniseKey } from './keyFormats'

// Built from parts so the repository never holds a literal that secret scanners take for a real Twilio ID.
const FAKE_HEX = '0123456789abcdef'.repeat(2)
const FAKE_ACCOUNT_SID = 'A' + 'C' + FAKE_HEX
const FAKE_API_KEY_SID = 'S' + 'K' + FAKE_HEX

describe('cleanKey', () => {
  it('takes off what a paste brings along', () => {
    expect(cleanKey('  gsk_abc123  ')).toBe('gsk_abc123')
    expect(cleanKey('"sk-or-v1-abc"')).toBe('sk-or-v1-abc')
    expect(cleanKey('Bearer nvapi-xyz')).toBe('nvapi-xyz')
    expect(cleanKey('Authorization: Bearer nvapi-xyz')).toBe('nvapi-xyz')
    expect(cleanKey('OPENAI_API_KEY=sk-proj-abc')).toBe('sk-proj-abc')
    expect(cleanKey('export GROQ_API_KEY="gsk_abc"')).toBe('gsk_abc')
    expect(cleanKey('sk-ant-abc\ndef')).toBe('sk-ant-abcdef')
  })

  it('leaves a base64 key with = at the end alone', () => {
    expect(cleanKey('ABSKQmVkcm9ja0FQSUtleS1hYmM=')).toBe('ABSKQmVkcm9ja0FQSUtleS1hYmM=')
  })

  it('says when something was taken off', () => {
    expect(cleanedNote('gsk_abc')).toBeNull()
    expect(cleanedNote('  gsk_abc ')).toBeNull()
    expect(cleanedNote('"gsk_abc"')).not.toBeNull()
  })
})

describe('recognising a key', () => {
  it('names providers by their prefix', () => {
    expect(recogniseKey('sk-or-v1-123')?.name).toBe('OpenRouter')
    expect(recogniseKey('sk-ant-api03-123')?.name).toBe('Anthropic')
    expect(recogniseKey('nvapi-123')?.name).toBe('NVIDIA NIM')
    expect(recogniseKey('AKIAABCDEFGHIJKLMNOP')?.name).toBe('AWS access key ID')
    expect(recogniseKey('just-some-text')).toBeNull()
  })

  it('warns when a key looks like another provider’s', () => {
    expect(keyMismatch('groq', 'Groq', 'sk-or-v1-123')).toMatch(/OpenRouter key, not a key for Groq/)
    expect(keyMismatch('openrouter', 'OpenRouter', 'sk-or-v1-123')).toBeNull()
    expect(keyMismatch('nvidia', 'NVIDIA NIM', 'AKIAABCDEFGHIJKLMNOP')).toMatch(/AWS access key ID/)
    // Plain sk- keys are used by many OpenAI-compatible providers: no warning for those.
    expect(keyMismatch('my-proxy', 'My proxy', 'sk-abcdefghijklmnopqrstuvwxyz')).toBeNull()
    expect(keyMismatch('anthropic', 'Anthropic', 'sk-proj-abc')).toMatch(/OpenAI key/)
    expect(keyMismatch('groq', 'Groq', 'unknown-format')).toBeNull()
  })
})

describe('Twilio Account SID check', () => {
  it('accepts an AC SID and explains an SK SID or anything else', () => {
    expect(accountSidProblem('twilio', 'accountSid', FAKE_ACCOUNT_SID)).toBeNull()
    expect(accountSidProblem('twilio', 'accountSid', FAKE_API_KEY_SID)).toMatch(/API key SID/)
    expect(accountSidProblem('twilio', 'accountSid', 'abc')).toMatch(/starts with AC/)
    expect(accountSidProblem('twilio', 'accountSid', '')).toBeNull()
    expect(accountSidProblem('zendesk', 'accountSid', 'abc')).toBeNull()
    expect(keyMismatch('slack', 'Slack', FAKE_ACCOUNT_SID)).toMatch(/Twilio Account SID/)
  })
})
