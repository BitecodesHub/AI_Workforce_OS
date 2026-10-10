// @find: bedrock credentials, AWS Bedrock, region picker, find my region, access key, secret key, bedrock api key, connect model, BedrockCredentialFields, POST /api/providers/bedrock/bedrock-regions
// @what: Credential fields for connecting AWS Bedrock, including region choice and Find my region.
// @flow: Used by ConnectModelDialog; uses RegionCombobox and useFindBedrockRegion
import { useState } from 'react'
import { Button, Input, Notice, PasswordInput, Select } from '../ui'
import {
  BEDROCK_IAM_ACTIONS,
  BEDROCK_MODEL_ACCESS_DOCS,
  BEDROCK_SETUP_DOCS,
  bedrockCredentialValue,
  bedrockFormComplete,
  regionLabel,
  type BedrockAuth,
  type BedrockForm,
} from '../../lib/bedrock'
import { ApiError, describeApiError } from '../../lib/api'
import { cleanKey, recogniseKey } from '../../lib/keyFormats'
import { useFindBedrockRegion, type RegionFinding, type RegionStatus } from '../../lib/settingsQueries'
import { RegionCombobox, type RegionNote } from './RegionCombobox'

/*
 * The Bedrock part of "Connect your AI": how the workspace signs in to AWS, the region, and the
 * steps to get there. Values are held by the dialog and sent once, as one JSON credential; every
 * secret field is a password field and nothing here keeps a copy.
 */

type Props = {
  /** The Bedrock provider's id, for "Find my region". */
  providerId: string
  form: BedrockForm
  onChange: (form: BedrockForm) => void
  /** A field the service refused, with its sentence. */
  fieldErrors?: Partial<Record<keyof BedrockForm, string>>
  disabled?: boolean
}

/** The tag a region gets in the list once "Find my region" has looked at it. */
const NOTE: Record<RegionStatus, RegionNote | null> = {
  ready: { text: 'Works', tone: 'good' },
  accepted: { text: 'Key accepted', tone: 'good' },
  no_model_access: { text: 'No model access yet', tone: 'warn' },
  refused: { text: 'Not accepted', tone: 'bad' },
  unreachable: null,
}

// @find: regionNotes, region availability notes
export function regionNotes(finding: RegionFinding | null): Record<string, RegionNote> {
  const notes: Record<string, RegionNote> = {}
  for (const result of finding?.regions ?? []) {
    const note = NOTE[result.status]
    if (note) notes[result.region] = note
  }
  return notes
}

/**
 * Where a pasted value really belongs: an access key ID pasted as an API key (or the other way
 * round) switches the sign-in, rather than failing later with "credentials refused".
 */
function placePaste(form: BedrockForm, field: 'apiKey' | 'accessKeyId', raw: string): { form: BedrockForm; moved: string | null } {
  const value = cleanKey(raw)
  const seen = recogniseKey(value)
  if (field === 'apiKey' && seen?.name === 'AWS access key ID') {
    return {
      form: { ...form, auth: 'access_key', apiKey: '', accessKeyId: value },
      moved: 'That is an AWS access key ID, so the sign-in was switched to AWS access key. Add its secret below.',
    }
  }
  if (field === 'accessKeyId' && seen?.name === 'Amazon Bedrock API key') {
    return {
      form: { ...form, auth: 'api_key', accessKeyId: '', apiKey: value },
      moved: 'That is a Bedrock API key, so the sign-in was switched to Bedrock API key.',
    }
  }
  return { form: { ...form, [field]: value }, moved: null }
}

/** A sentence when a pasted key belongs to another provider altogether. */
function otherProvider(value: string): string | null {
  const seen = recogniseKey(value)
  if (!seen || seen.providers.includes('bedrock')) return null
  return `This looks like ${/^[AEIOU]/.test(seen.name) ? 'an' : 'a'} ${seen.name}${/key|token|ID$/.test(seen.name) ? '' : ' key'}, not an AWS one. Check that you copied the right key.`
}

// @find: BedrockCredentialFields, bedrock keys, region, find my region
export function BedrockCredentialFields({ providerId, form, onChange, fieldErrors = {}, disabled }: Props) {
  const set = <K extends keyof BedrockForm>(key: K, value: BedrockForm[K]) => onChange({ ...form, [key]: value })
  const secretProps = { autoComplete: 'off', spellCheck: false, disabled } as const
  const [moved, setMoved] = useState<string | null>(null)
  const findRegion = useFindBedrockRegion()
  const [finding, setFinding] = useState<RegionFinding | null>(null)
  const [findError, setFindError] = useState<string | null>(null)

  const paste = (field: 'apiKey' | 'accessKeyId', raw: string) => {
    const placed = placePaste(form, field, raw)
    setMoved(placed.moved)
    setFinding(null)
    onChange(placed.form)
  }

  const find = async () => {
    setFindError(null)
    setFinding(null)
    try {
      const result = await findRegion.mutateAsync({ providerId, value: bedrockCredentialValue(form) })
      setFinding(result)
      if (result.best) onChange({ ...form, region: result.best })
    } catch (failure) {
      setFindError(
        failure instanceof ApiError && Object.keys(failure.fields).length > 0
          ? describeApiError(failure, { apiKey: 'Bedrock API key', accessKeyId: 'Access key ID', secretAccessKey: 'Secret access key', sessionToken: 'Session token' })
          : describeApiError(failure),
      )
    }
  }

  const working = finding?.regions.filter((result) => result.status === 'ready' || result.status === 'accepted' || result.status === 'no_model_access') ?? []
  const wrongProvider = otherProvider(form.auth === 'api_key' ? form.apiKey : form.accessKeyId)

  return (
    <div className="stack" style={{ gap: 'var(--space-4)' }}>
      <Select
        label="Sign in with"
        value={form.auth}
        onChange={(event) => {
          setMoved(null)
          set('auth', event.target.value as BedrockAuth)
        }}
        disabled={disabled}
        hint={
          form.auth === 'api_key'
            ? 'A Bedrock API key, made in the Amazon Bedrock console under API keys. It starts ABSK or bedrock-api-key-.'
            : 'An access key for an IAM user or role that may call Bedrock.'
        }
      >
        <option value="api_key">Bedrock API key</option>
        <option value="access_key">AWS access key</option>
      </Select>

      {moved && (
        <Notice tone="info" live>
          {moved}
        </Notice>
      )}

      {form.auth === 'api_key' ? (
        <PasswordInput
          label="Bedrock API key"
          value={form.apiKey}
          onChange={(event) => paste('apiKey', event.target.value)}
          error={fieldErrors.apiKey ?? wrongProvider ?? undefined}
          required
          data-autofocus
          {...secretProps}
        />
      ) : (
        <>
          <Input
            label="Access key ID"
            value={form.accessKeyId}
            onChange={(event) => paste('accessKeyId', event.target.value)}
            error={fieldErrors.accessKeyId ?? wrongProvider ?? undefined}
            hint="Starts with AKIA, or ASIA for a temporary key."
            required
            data-autofocus
            {...secretProps}
          />
          <PasswordInput
            label="Secret access key"
            value={form.secretAccessKey}
            onChange={(event) => set('secretAccessKey', cleanKey(event.target.value))}
            error={fieldErrors.secretAccessKey}
            required
            {...secretProps}
          />
          <PasswordInput
            label="Session token"
            optional={!form.accessKeyId.startsWith('ASIA')}
            value={form.sessionToken}
            onChange={(event) => set('sessionToken', cleanKey(event.target.value))}
            error={fieldErrors.sessionToken}
            hint={form.accessKeyId.startsWith('ASIA') ? 'Needed: an ASIA key is temporary and comes with a session token.' : 'Only for a temporary key (ASIA…).'}
            {...secretProps}
          />
        </>
      )}

      <div className="stack" style={{ gap: 'var(--space-2)' }}>
        <RegionCombobox
          value={form.region}
          onChange={(region) => set('region', region)}
          error={fieldErrors.region}
          disabled={disabled}
          notes={regionNotes(finding)}
          hint="Where your models run. Type a city, a country or an id to search. Not sure? Use Find my region."
        />
        <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', alignItems: 'center' }}>
          <Button
            variant="outline"
            className="button-sm"
            onClick={() => void find()}
            loading={findRegion.isPending}
            disabled={disabled || !bedrockFormComplete(form) || findRegion.isPending}
            title={bedrockFormComplete(form) ? undefined : 'Enter the key first'}
          >
            Find my region
          </Button>
          <span className="caption">
            {findRegion.isPending
              ? 'Asking every Bedrock region. This takes up to 15 seconds.'
              : bedrockFormComplete(form)
                ? 'Tries your key in every region at once. Nothing is saved.'
                : 'Enter the key first, then find the regions it works in.'}
          </span>
        </div>
        {findError && (
          <Notice tone="warning" live>
            {findError}
          </Notice>
        )}
        {finding && (
          <Notice tone={finding.best && working.some((result) => result.status === 'ready') ? 'success' : finding.best ? 'info' : 'warning'} live>
            <span>
              {finding.message}
              {working.length > 1 && (
                <>
                  {' '}
                  It also works in{' '}
                  {working
                    .filter((result) => result.region !== finding.best)
                    .slice(0, 5)
                    .map((result) => regionLabel(result.region))
                    .join(', ')}
                  {working.length > 6 ? ' and more' : ''}. Pick any of them above.
                </>
              )}
            </span>
          </Notice>
        )}
      </div>

      <details>
        <summary className="link" style={{ cursor: 'pointer' }}>
          How to set up Bedrock
        </summary>
        <ol className="caption" style={{ margin: 'var(--space-3) 0 0', paddingLeft: 'var(--space-5)' }}>
          <li>
            In the Amazon Bedrock console, open <strong>Model access</strong> for your region and turn on the models
            you want, such as Claude or Nova.{' '}
            <a href={BEDROCK_MODEL_ACCESS_DOCS} target="_blank" rel="noreferrer">
              How model access works
            </a>
          </li>
          <li>
            Either make a <strong>Bedrock API key</strong> in the console, or create an IAM user with an access key
            and a policy that allows {BEDROCK_IAM_ACTIONS.map((action, index) => (
              <span key={action}>
                <code>{action}</code>
                {index < BEDROCK_IAM_ACTIONS.length - 1 ? ', ' : ''}
              </span>
            ))}
            .
          </li>
          <li>Paste it here and choose the same region, or use Find my region. It is checked with one small request before anything is saved.</li>
        </ol>
        <p className="caption" style={{ margin: 'var(--space-2) 0 0' }}>
          <a href={BEDROCK_SETUP_DOCS} target="_blank" rel="noreferrer">
            AWS guide to getting started with Bedrock
          </a>
        </p>
      </details>
    </div>
  )
}
