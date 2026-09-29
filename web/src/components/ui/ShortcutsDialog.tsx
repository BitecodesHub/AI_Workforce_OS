import { Dialog, Kbd } from './index'

/*
 * The keyboard shortcuts a screen offers, grouped the way it explains them, each chord shown as
 * the separate keys `formatHotkey` (lib/hotkeys.ts) already split out.
 */

export function ShortcutsDialog({
  open,
  onClose,
  groups,
}: {
  open: boolean
  onClose: () => void
  groups: Array<{ title: string; items: Array<{ keys: string[]; description: string }> }>
}) {
  return (
    <Dialog open={open} onClose={onClose} eyebrow="Keyboard" title="Keyboard shortcuts">
      <div className="stack" style={{ gap: 'var(--space-6)' }}>
        {groups.map((group) => (
          <div key={group.title}>
            <p className="section-note" style={{ marginBottom: 'var(--space-3)' }}>
              {group.title}
            </p>
            <dl className="facts" style={{ margin: 0 }}>
              {group.items.map((item, itemIndex) => (
                <div key={`${item.description}-${itemIndex}`}>
                  <dt>{item.description}</dt>
                  <dd className="row" style={{ gap: 'var(--space-1)', justifyContent: 'flex-end' }}>
                    {item.keys.map((key, index) => (
                      <Kbd key={index}>{key}</Kbd>
                    ))}
                  </dd>
                </div>
              ))}
            </dl>
          </div>
        ))}
      </div>
    </Dialog>
  )
}
