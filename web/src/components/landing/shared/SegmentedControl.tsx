import { useId } from 'react'
import type { ReactElement } from 'react'

/*
 * A segmented control built from a native radio group.
 *
 * The fieldset and legend give the group its accessible name, the shared name attribute makes the
 * radios one group, and the browser supplies arrow-key movement and selection for free. Nothing
 * here reimplements what the platform already does correctly.
 */

export type SegmentedOption<V extends string> = { value: V; label: string }

export type SegmentedControlProps<V extends string> = {
  legend: string
  value: V
  options: ReadonlyArray<SegmentedOption<V>>
  onChange: (value: V) => void
  disabled?: boolean
  hideLegend?: boolean
  fullWidth?: boolean
}

export function SegmentedControl<V extends string>({
  legend,
  value,
  options,
  onChange,
  disabled,
  hideLegend,
  fullWidth,
}: SegmentedControlProps<V>): ReactElement {
  const name = useId()
  return (
    <fieldset className="lp-seg" disabled={disabled === true}>
      <legend className={hideLegend ? 'lp-seg-legend-hidden' : 'lp-seg-legend'}>{legend}</legend>
      <div className={fullWidth ? 'lp-seg-options lp-seg-full' : 'lp-seg-options'}>
        {options.map((option) => (
          <label key={option.value}>
            <input
              type="radio"
              className="lp-seg-input"
              name={name}
              value={option.value}
              checked={option.value === value}
              onChange={() => onChange(option.value)}
            />
            <span className="lp-seg-pill">{option.label}</span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}
