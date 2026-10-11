// @find: theme switch, dark mode toggle, light mode, system theme, appearance setting, ThemeSwitch, ThemeCard, Profile page, account menu
// @what: The three-way theme control (System, Light, Dark) for the account menu and the Profile page.
// @flow: Reads and changes the setting through lib/theme's useTheme
import { useId } from 'react'
import { Card, Eyebrow } from '../ui'
import { THEME_LABELS, THEME_SETTINGS, useTheme } from '../../lib/theme'

/*
 * Three native radio buttons in a fieldset, drawn as a segmented control. Native radios bring the
 * keyboard behaviour with them (Tab reaches the group, the arrow keys move between choices), and a
 * screen reader announces "Theme, System, radio button, 1 of 3" without any ARIA of our own.
 */
// @find: theme switch component, segmented theme control
/**
 * Three-way theme control (System, Light, Dark) built from native radio buttons in a fieldset.
 * Reads and writes the setting through useTheme (lib/theme), which persists it in this browser. The legend
 * text defaults to "Theme" and can be overridden. Rendered by ThemeCard and by the account menu in Navbar.
 */
export function ThemeSwitch({ legend = 'Theme' }: { legend?: string }) {
  const [setting, setSetting] = useTheme()
  const name = useId()
  return (
    <fieldset className="theme-switch">
      <legend className="theme-switch-legend">{legend}</legend>
      <div className="theme-switch-options">
        {THEME_SETTINGS.map((option) => (
          <label key={option} className="theme-switch-option">
            <input
              type="radio"
              name={name}
              value={option}
              checked={setting === option}
              onChange={() => setSetting(option)}
            />
            <span>{THEME_LABELS[option]}</span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}

// @find: theme card, appearance card on profile
/** Card for the Profile page with the Appearance heading, the ThemeSwitch and a caption explaining that System follows the device and that the choice is kept in this browser only. */
export function ThemeCard() {
  return (
    <Card as="section">
      <Eyebrow as="h2">Appearance</Eyebrow>
      <ThemeSwitch />
      <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
        System follows your device’s light or dark setting. Your choice is kept in this browser only.
      </p>
    </Card>
  )
}
