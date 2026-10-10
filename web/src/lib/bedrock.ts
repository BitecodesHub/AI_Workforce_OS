// @find: Amazon Bedrock, AWS Bedrock, AWS region, access key, Bedrock API key, session token, credential form, BEDROCK_REGIONS, groupRegions, bedrockCredentialValue, Connect your AI dialog
// @what: Bedrock sign-in form shape, region list and the stored credential value.
// @flow: Used by components/onboarding/ConnectModelDialog.tsx, BedrockCredentialFields.tsx and RegionCombobox.tsx.
/*
 * Amazon Bedrock's sign-in, for the "Connect your AI" dialog.
 *
 * Bedrock is not one pasted key. A workspace signs in with either an AWS access key (an access key
 * ID and secret, plus a session token for temporary keys) or a Bedrock API key, and chooses the AWS
 * region to call. The fields are sent as one JSON value, which the service checks field by field
 * (BedrockCredentials on the server) and stores encrypted like any other credential. This file is
 * the pure part: the regions offered, the form's shape, and turning the form into that value.
 */

export const BEDROCK_DEFAULT_REGION = 'us-east-1'

/** Where a region is, for grouping the region list the way AWS's console does. */
export type BedrockGeography = 'United States' | 'Americas' | 'Europe' | 'Middle East' | 'Asia Pacific' | 'AWS GovCloud'

export type BedrockRegion = { id: string; name: string; geography: BedrockGeography }

// @find: Bedrock regions list, AWS region names; used by: region picker in Connect your AI dialog
/** The regions Bedrock runs in, by the names AWS's console uses, in the console's order. */
export const BEDROCK_REGIONS: ReadonlyArray<BedrockRegion> = [
  { id: 'us-east-1', name: 'US East (N. Virginia)', geography: 'United States' },
  { id: 'us-east-2', name: 'US East (Ohio)', geography: 'United States' },
  { id: 'us-west-1', name: 'US West (N. California)', geography: 'United States' },
  { id: 'us-west-2', name: 'US West (Oregon)', geography: 'United States' },
  { id: 'ca-central-1', name: 'Canada (Central)', geography: 'Americas' },
  { id: 'ca-west-1', name: 'Canada West (Calgary)', geography: 'Americas' },
  { id: 'mx-central-1', name: 'Mexico (Central)', geography: 'Americas' },
  { id: 'sa-east-1', name: 'South America (São Paulo)', geography: 'Americas' },
  { id: 'eu-central-1', name: 'Europe (Frankfurt)', geography: 'Europe' },
  { id: 'eu-central-2', name: 'Europe (Zurich)', geography: 'Europe' },
  { id: 'eu-west-1', name: 'Europe (Ireland)', geography: 'Europe' },
  { id: 'eu-west-2', name: 'Europe (London)', geography: 'Europe' },
  { id: 'eu-west-3', name: 'Europe (Paris)', geography: 'Europe' },
  { id: 'eu-north-1', name: 'Europe (Stockholm)', geography: 'Europe' },
  { id: 'eu-south-1', name: 'Europe (Milan)', geography: 'Europe' },
  { id: 'eu-south-2', name: 'Europe (Spain)', geography: 'Europe' },
  { id: 'il-central-1', name: 'Israel (Tel Aviv)', geography: 'Middle East' },
  { id: 'me-central-1', name: 'Middle East (UAE)', geography: 'Middle East' },
  { id: 'me-south-1', name: 'Middle East (Bahrain)', geography: 'Middle East' },
  { id: 'ap-south-1', name: 'Asia Pacific (Mumbai)', geography: 'Asia Pacific' },
  { id: 'ap-south-2', name: 'Asia Pacific (Hyderabad)', geography: 'Asia Pacific' },
  { id: 'ap-southeast-1', name: 'Asia Pacific (Singapore)', geography: 'Asia Pacific' },
  { id: 'ap-southeast-2', name: 'Asia Pacific (Sydney)', geography: 'Asia Pacific' },
  { id: 'ap-southeast-3', name: 'Asia Pacific (Jakarta)', geography: 'Asia Pacific' },
  { id: 'ap-southeast-4', name: 'Asia Pacific (Melbourne)', geography: 'Asia Pacific' },
  { id: 'ap-southeast-5', name: 'Asia Pacific (Malaysia)', geography: 'Asia Pacific' },
  { id: 'ap-southeast-7', name: 'Asia Pacific (Thailand)', geography: 'Asia Pacific' },
  { id: 'ap-northeast-1', name: 'Asia Pacific (Tokyo)', geography: 'Asia Pacific' },
  { id: 'ap-northeast-2', name: 'Asia Pacific (Seoul)', geography: 'Asia Pacific' },
  { id: 'ap-northeast-3', name: 'Asia Pacific (Osaka)', geography: 'Asia Pacific' },
  { id: 'ap-east-2', name: 'Asia Pacific (Taipei)', geography: 'Asia Pacific' },
  { id: 'us-gov-west-1', name: 'AWS GovCloud (US-West)', geography: 'AWS GovCloud' },
  { id: 'us-gov-east-1', name: 'AWS GovCloud (US-East)', geography: 'AWS GovCloud' },
]

// @find: region label, US East N. Virginia
/** "US East (N. Virginia) — us-east-1"; a region not in the list reads as its id. */
export function regionLabel(id: string): string {
  const region = BEDROCK_REGIONS.find((candidate) => candidate.id === id)
  return region ? `${region.name} — ${region.id}` : id
}

// @find: group and search regions; used by: RegionCombobox in Connect your AI dialog
/**
 * The regions matching what is typed, by name, id or geography, grouped in the list's order.
 * Every word typed must match somewhere, so "europe paris" and "eu-west" both narrow it.
 */
export function groupRegions(query: string): Array<{ geography: BedrockGeography; regions: BedrockRegion[] }> {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean)
  const groups: Array<{ geography: BedrockGeography; regions: BedrockRegion[] }> = []
  for (const region of BEDROCK_REGIONS) {
    const haystack = `${region.name} ${region.id} ${region.geography}`.toLowerCase()
    if (!words.every((word) => haystack.includes(word))) continue
    const group = groups.find((candidate) => candidate.geography === region.geography)
    if (group) group.regions.push(region)
    else groups.push({ geography: region.geography, regions: [region] })
  }
  return groups
}

/** AWS's guide to Bedrock's IAM permissions and API keys, linked from the setup steps. */
export const BEDROCK_SETUP_DOCS = 'https://docs.aws.amazon.com/bedrock/latest/userguide/getting-started.html'
export const BEDROCK_MODEL_ACCESS_DOCS = 'https://docs.aws.amazon.com/bedrock/latest/userguide/model-access.html'

/** The IAM permissions the setup steps ask for: chat, listing models and inference profiles. */
export const BEDROCK_IAM_ACTIONS = [
  'bedrock:InvokeModel',
  'bedrock:InvokeModelWithResponseStream',
  'bedrock:ListFoundationModels',
  'bedrock:ListInferenceProfiles',
] as const

export type BedrockAuth = 'api_key' | 'access_key'

export type BedrockForm = {
  auth: BedrockAuth
  apiKey: string
  accessKeyId: string
  secretAccessKey: string
  sessionToken: string
  region: string
}

export const EMPTY_BEDROCK_FORM: BedrockForm = {
  auth: 'api_key',
  apiKey: '',
  accessKeyId: '',
  secretAccessKey: '',
  sessionToken: '',
  region: BEDROCK_DEFAULT_REGION,
}

/** Field labels, so the service's field problems read in the form's own words. */
export const BEDROCK_FIELD_LABELS: Record<string, string> = {
  apiKey: 'Bedrock API key',
  accessKeyId: 'Access key ID',
  secretAccessKey: 'Secret access key',
  sessionToken: 'Session token',
  region: 'Region',
  value: 'Credentials',
}

// @find: is Bedrock provider check
export function isBedrock(provider: { kind: string }): boolean {
  return provider.kind.toUpperCase() === 'BEDROCK'
}

// @find: Bedrock form complete, enable connect button
/**
 * Whether the form has what the chosen sign-in needs. The service checks each field's shape; this
 * only decides whether the button can be pressed.
 */
export function bedrockFormComplete(form: BedrockForm): boolean {
  if (!form.region.trim()) return false
  if (form.auth === 'api_key') return form.apiKey.trim().length > 0
  return form.accessKeyId.trim().length > 0 && form.secretAccessKey.trim().length > 0
}

// @find: build Bedrock credential JSON, access key or API key; stored encrypted via the providers credential route; used by: Connect your AI dialog
/**
 * The one value stored for Bedrock: JSON with only the chosen sign-in's fields, trimmed. A field
 * from the other sign-in, typed and then switched away from, is never sent.
 */
export function bedrockCredentialValue(form: BedrockForm): string {
  const region = form.region.trim() || BEDROCK_DEFAULT_REGION
  if (form.auth === 'api_key') {
    return JSON.stringify({ type: 'api_key', apiKey: form.apiKey.trim(), region })
  }
  const token = form.sessionToken.trim()
  return JSON.stringify({
    type: 'access_key',
    accessKeyId: form.accessKeyId.trim(),
    secretAccessKey: form.secretAccessKey.trim(),
    ...(token ? { sessionToken: token } : {}),
    region,
  })
}
