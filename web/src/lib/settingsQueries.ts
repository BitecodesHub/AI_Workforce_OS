import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'

/*
 * What the workspace's settings page and the "Connect your AI" dialog read and write: the
 * workspace's name and time zone, where it is told that something is waiting, and the one-token
 * check of a model key. Pure helpers (time zones, validation) are kept apart from the hooks so
 * both screens and their tests use the same rules.
 */

/* ---- Time zones --------------------------------------------------------------------------------- */

/** Used where the browser cannot list its own zones. A person can still type any other zone's name. */
const FALLBACK_ZONES = [
  'UTC',
  'Australia/Melbourne',
  'Australia/Sydney',
  'Australia/Brisbane',
  'Australia/Adelaide',
  'Australia/Perth',
  'Pacific/Auckland',
  'Asia/Kolkata',
  'Asia/Singapore',
  'Asia/Tokyo',
  'Asia/Dubai',
  'Europe/London',
  'Europe/Dublin',
  'Europe/Paris',
  'Europe/Berlin',
  'America/New_York',
  'America/Chicago',
  'America/Denver',
  'America/Los_Angeles',
  'America/Toronto',
]

/** The time zone this browser is set to, or UTC when it will not say. */
export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

/**
 * Every IANA time zone the browser knows, sorted, plus UTC (which `Intl.supportedValuesOf` leaves
 * out) and any extra zones asked for, such as the zone the workspace already has or the browser's
 * own, so the current value is always one of the choices.
 */
export function timeZoneChoices(...extra: Array<string | null | undefined>): string[] {
  let zones: string[]
  try {
    zones = Intl.supportedValuesOf('timeZone')
  } catch {
    zones = []
  }
  const all = new Set<string>(zones.length > 0 ? zones : FALLBACK_ZONES)
  all.add('UTC')
  for (const zone of extra) if (zone) all.add(zone)
  return [...all].sort((a, b) => a.localeCompare(b))
}

/** Whether the text is exactly one of the choices. Zones are case-sensitive on the server. */
export function isKnownTimeZone(zone: string, choices: readonly string[]): boolean {
  return choices.includes(zone)
}

/** The local time in a zone, as a short phrase, or null for a zone the browser cannot format. */
export function timeInZone(zone: string, now: Date = new Date()): string | null {
  try {
    return new Intl.DateTimeFormat(undefined, { timeZone: zone, hour: 'numeric', minute: '2-digit' }).format(now)
  } catch {
    return null
  }
}

/* ---- The workspace ------------------------------------------------------------------------------ */

/** GET /api/workspaces/{id} (WorkspaceController.WorkspaceView). */
export type Workspace = { id: string; name: string; slug: string; timezone: string; status: string }

/** The same limit as WorkspaceController.UpdateSettingsRequest. */
export const WORKSPACE_NAME_MAX = 120

export type WorkspaceForm = { name: string; timezone: string }

export type WorkspaceFormErrors = { name?: string | undefined; timezone?: string | undefined }

/**
 * What is wrong with the settings form, field by field, before anything is sent. The service
 * refuses the same things; checking first means the message sits beside the field.
 */
export function validateWorkspaceForm(form: WorkspaceForm, choices: readonly string[]): WorkspaceFormErrors {
  const errors: WorkspaceFormErrors = {}
  const name = form.name.trim()
  if (!name) errors.name = 'Enter a name for the workspace.'
  else if (name.length > WORKSPACE_NAME_MAX) errors.name = `Use at most ${WORKSPACE_NAME_MAX} characters.`
  if (!isKnownTimeZone(form.timezone, choices)) {
    errors.timezone = 'Choose a time zone from the list, such as Australia/Melbourne.'
  }
  return errors
}

export function useWorkspace(id: string | null | undefined) {
  return useQuery({
    queryKey: ['workspace', id],
    queryFn: ({ signal }) => api<Workspace>(`/api/workspaces/${id}`, { signal }),
    enabled: Boolean(id),
  })
}

/** Renames the workspace or moves its time zone (workspace:update). */
export function useUpdateWorkspaceSettings(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (form: WorkspaceForm) =>
      api<Workspace>(`/api/workspaces/${id}/settings`, {
        method: 'PATCH',
        body: { name: form.name.trim(), timezone: form.timezone },
      }),
    onSuccess: (saved) => client.setQueryData(['workspace', id], saved),
  })
}

/* ---- Where the workspace is told ---------------------------------------------------------------- */

/** An event a workspace can choose to hear, and what it means in words. */
export type NotificationEventOption = { id: string; label: string }

/** GET /api/orchestrator/notification-settings (NotificationSettingsController.SettingsView). */
export type NotificationSettings = {
  webhookUrl: string
  hasSecret: boolean
  events: string[]
  availableEvents: NotificationEventOption[]
}

/** PUT body. `secret` left out keeps the stored one; an empty string removes it. */
export type NotificationInput = { webhookUrl: string; secret?: string; events: string[] }

/** What Send test message answers (NotificationSettingsController.TestView). */
export type NotificationTest = { delivered: boolean; statusCode?: number | null; message: string }

/** The service's own limits (NotificationService.SECRET_MIN and SECRET_MAX). */
export const SECRET_MIN = 8
export const SECRET_MAX = 256

export type NotificationErrors = {
  webhookUrl?: string | undefined
  secret?: string | undefined
  events?: string | undefined
}

/**
 * What is wrong with the notification form. An empty address is allowed: it turns notifications
 * off. The service decides which addresses it will really call (https only, nothing private).
 */
export function validateNotifications(
  form: { webhookUrl: string; secret: string; removeSecret: boolean; events: readonly string[] },
): NotificationErrors {
  const errors: NotificationErrors = {}
  const url = form.webhookUrl.trim()
  if (url && !/^https?:\/\/\S+$/i.test(url)) {
    errors.webhookUrl = 'Enter a full web address, starting with https://.'
  }
  if (url && form.secret && !form.removeSecret && (form.secret.length < SECRET_MIN || form.secret.length > SECRET_MAX)) {
    errors.secret = `Use between ${SECRET_MIN} and ${SECRET_MAX} characters.`
  }
  if (url && form.events.length === 0) {
    errors.events = 'Choose at least one event, or clear the address to turn notifications off.'
  }
  return errors
}

export function useNotificationSettings(options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: ['notification-settings'],
    queryFn: ({ signal }) => api<NotificationSettings>('/api/orchestrator/notification-settings', { signal }),
    enabled: options.enabled ?? true,
  })
}

export function useSaveNotificationSettings() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: NotificationInput) =>
      api<NotificationSettings>('/api/orchestrator/notification-settings', { method: 'PUT', body: input }),
    onSuccess: (saved) => client.setQueryData(['notification-settings'], saved),
  })
}

/** Sends a test message to the address that is saved, not the one being typed. */
export function useSendTestNotification() {
  return useMutation({
    mutationFn: () => api<NotificationTest>('/api/orchestrator/notification-settings/test', { method: 'POST' }),
  })
}

/* ---- How long run detail is kept ----------------------------------------------------------------- */

/** GET /api/orchestrator/retention-settings */
export type RetentionSettings = {
  runDetailDays: number
  defaultRunDetailDays: number
  minRunDetailDays: number
  maxRunDetailDays: number
  usageDays: number
}

export function useRetentionSettings(options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: ['retention-settings'],
    queryFn: ({ signal }) => api<RetentionSettings>('/api/orchestrator/retention-settings', { signal }),
    enabled: options.enabled ?? true,
  })
}

export function useSaveRetentionSettings() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (runDetailDays: number) =>
      api<RetentionSettings>('/api/orchestrator/retention-settings', { method: 'PUT', body: { runDetailDays } }),
    onSuccess: (saved) => client.setQueryData(['retention-settings'], saved),
  })
}

/* ---- Checking a model key ----------------------------------------------------------------------- */

/** What the check of a pasted key came to (ProviderController.KeyCheck). */
export type KeyCheck = 'valid' | 'rejected' | 'no_credit' | 'network_error'

/** POST /api/providers/{id}/test: `message` is plain words, written by the service. */
export type KeyTest = { result: KeyCheck; message: string }

/**
 * Checks a key with one small call before it is stored (provider:manage). Nothing is saved by the
 * call itself, and the service allows five a minute in a workspace.
 */
export function useTestProviderKey() {
  return useMutation({
    mutationFn: (input: { providerId: string; value: string }) =>
      api<KeyTest>(`/api/providers/${encodeURIComponent(input.providerId)}/test`, {
        method: 'POST',
        body: { value: input.value },
      }),
  })
}
