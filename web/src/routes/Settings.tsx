// @find: workspace settings, settings, workspace name, time zone, notifications, email alerts, approval rules, who may approve, retention, keep data for, delete old data, /settings, Settings page
// @what: The Workspace settings page: name, time zone, notifications, approval rules and data retention.
// @flow: Routed from App.tsx at /settings; saves through the settings queries in lib/queries
import { useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Card, Eyebrow, Input, Notice, PageHeader, PasswordInput, Select } from '../components/ui'
import { QueryState } from '../components/ui/QueryState'
import { TimeZoneField } from '../components/onboarding/TimeZoneField'
import { ApiError, describeApiError } from '../lib/api'
import { BrowserNotificationsCard } from '../components/settings/BrowserNotificationsCard'
import { SetupBanner } from '../components/setup/SetupBanner'
import { can, profile } from '../lib/session'
import {
  WORKSPACE_NAME_MAX,
  browserTimeZone,
  timeZoneChoices,
  REQUESTER_RULE_LABEL,
  useApprovalSettings,
  useNotificationSettings,
  useRetentionSettings,
  useSaveApprovalSettings,
  useSaveRetentionSettings,
  useSaveNotificationSettings,
  useSendTestNotification,
  useUpdateWorkspaceSettings,
  useWorkspace,
  validateNotifications,
  validateWorkspaceForm,
  type NotificationErrors,
  type NotificationSettings,
  type RetentionSettings,
  type NotificationTest,
  type RequesterRule,
  type Workspace,
  type WorkspaceFormErrors,
} from '../lib/settingsQueries'
import { useToast } from '../lib/toast'

/*
 * Workspace settings: what only an owner or admin changes about the workspace itself.
 *
 * The name and time zone, where the workspace is told that an approval is waiting or a schedule
 * paused itself, and a link to the budget. The one setting that is the person's own rather than
 * the workspace's - whether this browser shows notifications - sits here too, so everything about
 * being told is in one place.
 *
 * Reached from Your profile, by those who hold workspace:update (the route is gated on it).
 */

const WORKSPACE_FIELD_LABELS = { name: 'Workspace name', timezone: 'Time zone' }

// @find: workspace card, rename workspace, change time zone
function WorkspaceCard({ workspace }: { workspace: Workspace }) {
  const toast = useToast()
  const save = useUpdateWorkspaceSettings(workspace.id)
  const [name, setName] = useState(workspace.name)
  const [timezone, setTimezone] = useState(workspace.timezone)
  const [errors, setErrors] = useState<WorkspaceFormErrors>({})
  const [failure, setFailure] = useState<string | null>(null)
  // The workspace's own zone is always among the choices, even one this browser does not list.
  const zones = useMemo(() => timeZoneChoices(workspace.timezone, browserTimeZone()), [workspace.timezone])

  const dirty = name.trim() !== workspace.name || timezone !== workspace.timezone

  function submit(event: FormEvent) {
    event.preventDefault()
    if (save.isPending) return
    setFailure(null)
    const problems = validateWorkspaceForm({ name, timezone }, zones)
    setErrors(problems)
    if (problems.name || problems.timezone) return
    save.mutate(
      { name, timezone },
      {
        onSuccess: (saved) => {
          setName(saved.name)
          setTimezone(saved.timezone)
          toast.success('Workspace settings saved')
        },
        onError: (error) => {
          const fields = error instanceof ApiError ? error.fields : {}
          const shown = {
            ...(fields.name ? { name: fields.name } : {}),
            ...(fields.timezone ? { timezone: fields.timezone } : {}),
          }
          setErrors(shown)
          // A problem beside its field needs no second copy above the form.
          if (Object.keys(shown).length === 0) setFailure(describeApiError(error, WORKSPACE_FIELD_LABELS))
        },
      },
    )
  }

  return (
    <Card as="section">
      <Eyebrow as="h2">Workspace</Eyebrow>
      <form className="stack" style={{ gap: 'var(--space-4)', maxWidth: '520px' }} onSubmit={submit} noValidate>
        {failure && (
          <Notice tone="warning" live>
            {failure}
          </Notice>
        )}
        <Input
          label="Workspace name"
          autoComplete="organization"
          required
          maxLength={WORKSPACE_NAME_MAX}
          value={name}
          onChange={(event) => {
            setName(event.target.value)
            setErrors((current) => ({ ...current, name: undefined }))
          }}
          hint="Shown to everybody in the workspace."
          error={errors.name}
        />
        <TimeZoneField
          id="settings-timezone"
          value={timezone}
          onChange={(zone) => {
            setTimezone(zone)
            setErrors((current) => ({ ...current, timezone: undefined }))
          }}
          choices={zones}
          hint="Start typing a city or region to find it."
          error={errors.timezone}
        />
        <p className="caption">Existing schedules keep their own time zone; new runs use the new zone within 10 minutes.</p>
        <div>
          <Button type="submit" loading={save.isPending} disabled={!dirty}>
            Save workspace settings
          </Button>
        </div>
      </form>
    </Card>
  )
}

// @find: notifications card, email notifications, approval alerts
export function NotificationsCard({ settings }: { settings: NotificationSettings }) {
  const toast = useToast()
  const save = useSaveNotificationSettings()
  const test = useSendTestNotification()
  const [webhookUrl, setWebhookUrl] = useState(settings.webhookUrl)
  const [secret, setSecret] = useState('')
  const [removeSecret, setRemoveSecret] = useState(false)
  const [events, setEvents] = useState<readonly string[]>(settings.events)
  const [errors, setErrors] = useState<NotificationErrors>({})
  const [failure, setFailure] = useState<string | null>(null)
  const [result, setResult] = useState<NotificationTest | null>(null)

  const sameEvents = events.length === settings.events.length && events.every((event) => settings.events.includes(event))
  const dirty = webhookUrl.trim() !== settings.webhookUrl || secret !== '' || removeSecret || !sameEvents
  // The test goes to the address that is saved, so it needs one, and nothing unsaved beside it.
  const canTest = settings.webhookUrl !== '' && !dirty

  function toggleEvent(id: string) {
    setEvents((current) => (current.includes(id) ? current.filter((event) => event !== id) : [...current, id]))
    setErrors((current) => ({ ...current, events: undefined }))
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (save.isPending) return
    setFailure(null)
    setResult(null)
    const problems = validateNotifications({ webhookUrl, secret, removeSecret, events })
    setErrors(problems)
    if (problems.webhookUrl || problems.secret || problems.events) return
    save.mutate(
      {
        webhookUrl: webhookUrl.trim(),
        // Left out keeps the stored secret; empty removes it.
        ...(removeSecret ? { secret: '' } : secret ? { secret } : {}),
        // An address that is cleared turns notifications off, whatever was ticked.
        events: [...events],
      },
      {
        onSuccess: (saved) => {
          setWebhookUrl(saved.webhookUrl)
          setEvents(saved.events)
          setSecret('')
          setRemoveSecret(false)
          toast.success(saved.webhookUrl ? 'Notification settings saved' : 'Notifications are off')
        },
        onError: (error) => {
          const fields = error instanceof ApiError ? error.fields : {}
          const shown = {
            ...(fields.webhookUrl ? { webhookUrl: `The address ${fields.webhookUrl}.` } : {}),
            ...(fields.secret ? { secret: `The secret ${fields.secret}.` } : {}),
            ...(fields.events ? { events: `The events ${fields.events}.` } : {}),
          }
          setErrors(shown)
          if (Object.keys(shown).length === 0) setFailure(describeApiError(error))
        },
      },
    )
  }

  function sendTest() {
    setResult(null)
    setFailure(null)
    test.mutate(undefined, {
      onSuccess: setResult,
      onError: (error) => setFailure(describeApiError(error)),
    })
  }

  return (
    <Card as="section">
      <Eyebrow as="h2">Notifications</Eyebrow>
      <p className="muted" style={{ maxWidth: '62ch', marginBottom: 'var(--space-5)' }}>
        Tell a chat channel or another system when an approval is waiting, when one expires, or when a schedule pauses
        itself. Messages carry a short summary and a link, never the details of the request.
      </p>
      <form className="stack" style={{ gap: 'var(--space-4)', maxWidth: '520px' }} onSubmit={submit} noValidate>
        {failure && (
          <Notice tone="warning" live>
            {failure}
          </Notice>
        )}
        <Input
          label="Webhook address"
          type="url"
          inputMode="url"
          autoComplete="off"
          spellCheck={false}
          value={webhookUrl}
          onChange={(event) => {
            setWebhookUrl(event.target.value)
            setErrors((current) => ({ ...current, webhookUrl: undefined }))
          }}
          placeholder="https://example.com/hooks/aiworkforce"
          hint="Leave empty to turn notifications off."
          error={errors.webhookUrl}
        />
        <PasswordInput
          label={settings.hasSecret ? 'Replace the signing secret' : 'Signing secret'}
          optional
          autoComplete="off"
          value={secret}
          disabled={removeSecret}
          onChange={(event) => {
            setSecret(event.target.value)
            setErrors((current) => ({ ...current, secret: undefined }))
          }}
          hint={
            settings.hasSecret
              ? 'A secret is set and cannot be shown. Leave this empty to keep it.'
              : 'Each message is signed with it, so the receiver can tell it came from this workspace.'
          }
          error={errors.secret}
        />
        {settings.hasSecret && (
          <label className="question-option">
            <input
              type="checkbox"
              checked={removeSecret}
              onChange={(event) => {
                setRemoveSecret(event.target.checked)
                if (event.target.checked) setSecret('')
              }}
            />
            <span className="question-option-label">Remove the saved secret</span>
          </label>
        )}

        <fieldset className="question-block" aria-describedby={errors.events ? 'notification-events-error' : undefined}>
          <legend>Tell me when</legend>
          {settings.availableEvents.map((option) => (
            <label key={option.id} className="question-option">
              <input type="checkbox" checked={events.includes(option.id)} onChange={() => toggleEvent(option.id)} />
              <span className="question-option-label">{option.label}</span>
            </label>
          ))}
          {errors.events && (
            <p className="field-error" id="notification-events-error">
              {errors.events}
            </p>
          )}
        </fieldset>

        <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap' }}>
          <Button type="submit" loading={save.isPending} disabled={!dirty}>
            Save notification settings
          </Button>
          <Button variant="outline" onClick={sendTest} loading={test.isPending} disabled={!canTest}>
            Send test message
          </Button>
        </div>
        {!canTest && (
          <p className="caption">
            {settings.webhookUrl !== ''
              ? 'Save your changes first. The test message goes to the address that is saved.'
              : 'Save a webhook address first. The test message goes to the address that is saved.'}
          </p>
        )}
        {result && (
          <Notice tone={result.delivered ? 'success' : 'warning'} live>
            {result.message}
          </Notice>
        )}
      </form>
    </Card>
  )
}

/**
 * A second pair of eyes: whether the person who asked for work may approve what its agent then
 * does. Applies from the next decision, to requests already waiting too.
 */
// @find: approval rule card, who can approve requests
function ApprovalRuleCard({ rule }: { rule: RequesterRule }) {
  const toast = useToast()
  const save = useSaveApprovalSettings()
  const [chosen, setChosen] = useState<RequesterRule>(rule)
  const [failure, setFailure] = useState<string | null>(null)

  function submit(event: FormEvent) {
    event.preventDefault()
    setFailure(null)
    save.mutate(chosen, {
      onSuccess: (saved) =>
        toast.success(
          saved.requesterCannotApprove === 'off'
            ? 'Saved. The person who asked may approve their own requests.'
            : 'Saved. Someone other than the person who asked now decides these requests.',
        ),
      onError: (error) => setFailure(describeApiError(error)),
    })
  }

  return (
    <Card as="section">
      <Eyebrow as="h2">A second pair of eyes</Eyebrow>
      <p className="muted" style={{ maxWidth: '62ch', marginBottom: 'var(--space-5)' }}>
        Choose when someone other than the person who asked for the work must approve what its agent wants to do.
        It applies to the next decision, including requests already waiting.
      </p>
      <form className="stack" style={{ gap: 'var(--space-4)', maxWidth: '420px' }} onSubmit={submit} noValidate>
        {failure && (
          <Notice tone="warning" live>
            {failure}
          </Notice>
        )}
        <Select
          label="Someone else must approve"
          value={chosen}
          onChange={(event) => setChosen(event.target.value as RequesterRule)}
        >
          {(Object.keys(REQUESTER_RULE_LABEL) as RequesterRule[]).map((value) => (
            <option key={value} value={value}>
              {REQUESTER_RULE_LABEL[value]}
            </option>
          ))}
        </Select>
        <div>
          <Button type="submit" loading={save.isPending} disabled={chosen === rule}>
            Save
          </Button>
        </div>
      </form>
    </Card>
  )
}

/** Whether this browser shows a notification for what is waiting on this person. Theirs alone, kept in this browser. */
// @find: retention card, how long to keep data
function RetentionCard({ settings }: { settings: RetentionSettings }) {
  const toast = useToast()
  const save = useSaveRetentionSettings()
  const [days, setDays] = useState(String(settings.runDetailDays))
  const [failure, setFailure] = useState<string | null>(null)
  const parsed = Number(days)
  const valid =
    Number.isInteger(parsed) && parsed >= settings.minRunDetailDays && parsed <= settings.maxRunDetailDays

  function submit(event: FormEvent) {
    event.preventDefault()
    setFailure(null)
    if (!valid) return
    save.mutate(parsed, {
      onSuccess: () => toast.success('Saved. The new period applies from tonight.'),
      onError: (error) => setFailure(describeApiError(error)),
    })
  }

  return (
    <Card as="section">
      <Eyebrow as="h2">Keeping run detail</Eyebrow>
      <p className="muted" style={{ maxWidth: '62ch', marginBottom: 'var(--space-5)' }}>
        The full detail of what an agent was sent and answered is cleared this many days after a run finishes. The
        run itself, its steps, its result and its cost are kept. Spending figures are kept for{' '}
        {settings.usageDays} days whatever is chosen here.
      </p>
      <form className="stack" style={{ gap: 'var(--space-4)', maxWidth: '320px' }} onSubmit={submit} noValidate>
        {failure && (
          <Notice tone="warning" live>
            {failure}
          </Notice>
        )}
        <Input
          label="Days to keep run detail"
          type="number"
          inputMode="numeric"
          value={days}
          onChange={(event) => setDays(event.target.value)}
          hint={`Between ${settings.minRunDetailDays} and ${settings.maxRunDetailDays}. The usual choice is ${settings.defaultRunDetailDays}.`}
          error={valid ? undefined : `Enter a whole number from ${settings.minRunDetailDays} to ${settings.maxRunDetailDays}.`}
        />
        <div>
          <Button type="submit" disabled={!valid || save.isPending || parsed === settings.runDetailDays}>
            {save.isPending ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </form>
    </Card>
  )
}

// @find: Settings component, workspace settings page, /settings
export function Settings() {
  const workspaceId = profile()?.workspaceId ?? null
  const workspace = useWorkspace(workspaceId)
  const notifications = useNotificationSettings()
  const retention = useRetentionSettings()
  const approvalRule = useApprovalSettings()

  return (
    <div className="page">
      <PageHeader
        eyebrow="Your workspace"
        title="Workspace settings"
        description="The workspace’s name and time zone, and where it is told that something needs a person."
      />

      <div className="page-sections">
        <SetupBanner always />
        <QueryState query={workspace} permission="workspace:update" what="the workspace settings" rows={3}>
          {(loaded) => <WorkspaceCard key={`${loaded.id}:${loaded.name}:${loaded.timezone}`} workspace={loaded} />}
        </QueryState>

        <QueryState query={notifications} permission="workspace:update" what="the notification settings" rows={3}>
          {(loaded) => (
            <NotificationsCard
              key={`${loaded.webhookUrl}:${loaded.hasSecret}:${loaded.events.join(',')}`}
              settings={loaded}
            />
          )}
        </QueryState>

        <BrowserNotificationsCard />

        <QueryState query={approvalRule} permission="workspace:update" what="the approval setting" rows={2}>
          {(loaded) => <ApprovalRuleCard key={loaded.requesterCannotApprove} rule={loaded.requesterCannotApprove} />}
        </QueryState>

        <QueryState query={retention} permission="workspace:update" what="the retention settings" rows={2}>
          {(loaded) => <RetentionCard key={String(loaded.runDetailDays)} settings={loaded} />}
        </QueryState>

        <Card as="section">
          <Eyebrow as="h2">Budget</Eyebrow>
          <p className="muted" style={{ maxWidth: '62ch' }}>
            Spending limits for the workspace and for each agent are set where the spending is shown.{' '}
            {can('analytics:read') ? (
              <a className="link" href="/analytics#budget">
                Open the budget in Analytics
              </a>
            ) : (
              'An owner or admin can open the budget in Analytics.'
            )}
          </p>
        </Card>
      </div>
    </div>
  )
}
