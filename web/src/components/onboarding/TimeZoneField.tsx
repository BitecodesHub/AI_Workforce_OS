// @find: time zone field, timezone picker, choose time zone, schedule timezone, onboarding timezone, TimeZoneField
// @what: Input field for choosing an IANA time zone with a datalist of options.
// @flow: Used by the Settings and Create workspace pages
import { useId } from 'react'
import { Input } from '../ui'
import { timeInZone } from '../../lib/settingsQueries'

/*
 * A time zone to pick from every zone the browser knows, found by typing.
 *
 * There are more than four hundred, so a plain list is a long scroll. A text field with a native
 * suggestion list lets somebody type "Melbourne" or "Kolkata" and pick the match, with the screen
 * reader and keyboard behaviour the browser already provides. Only a zone from the list is accepted
 * (see isKnownTimeZone), because the platform refuses a name it does not recognise. The hint shows
 * the time it is in the chosen zone, which is how a person checks they picked the right one.
 */

// @find: TimeZoneField, time zone picker
/**
 * Text input with a native datalist of the supplied zones (the platform refuses names it does not recognise).
 * The hint appends the current time in the chosen zone (timeInZone) once the value is a known choice, so a
 * person can confirm the pick. Props: id, label, value, onChange, choices, error and hint. Used by the
 * Settings and Create workspace pages.
 */
export function TimeZoneField({
  id,
  label = 'Time zone',
  value,
  onChange,
  choices,
  error,
  hint,
}: {
  id: string
  label?: string
  value: string
  onChange: (zone: string) => void
  choices: readonly string[]
  error?: string | undefined
  /** What to say about the choice itself, before the local time. */
  hint?: string
}) {
  const listId = useId()
  const local = choices.includes(value) ? timeInZone(value) : null
  const text = [hint, local ? `It is ${local} there now.` : null].filter(Boolean).join(' ')
  return (
    <>
      <Input
        id={id}
        label={label}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        list={listId}
        autoComplete="off"
        autoCapitalize="none"
        spellCheck={false}
        required
        error={error}
        hint={text || undefined}
      />
      <datalist id={listId}>
        {choices.map((zone) => (
          <option key={zone} value={zone} />
        ))}
      </datalist>
    </>
  )
}
