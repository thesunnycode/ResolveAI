import * as Dialog from '@radix-ui/react-dialog'
import { X } from 'lucide-react'
import * as React from 'react'
import { useAuth } from '@/features/auth/auth-context'
import { useHotkey } from '@/lib/hotkeys'

const MOD = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘' : 'Ctrl'

const GROUPS: { title: string; staffOnly?: boolean; rows: [string[], string][] }[] = [
  {
    title: 'Anywhere',
    rows: [
      [[MOD, 'K'], 'Search tickets, incidents and pages'],
      [['?'], 'Show this sheet'],
    ],
  },
  {
    title: 'Queue',
    staffOnly: true,
    rows: [
      [['j'], 'Next ticket'],
      [['k'], 'Previous ticket'],
      [['Enter'], 'Open the selected ticket'],
      [['a'], 'Assign the selected ticket to me'],
      [['/'], 'Search the queue'],
    ],
  },
  {
    title: 'Ticket',
    rows: [
      [['r'], 'Reply'],
      [[MOD, 'Enter'], 'Send the reply'],
      [['a'], 'Assign to me (staff)'],
      [['e'], 'Resolve (with 5s to undo)'],
      [['c'], 'Copy the ticket reference'],
      [['Esc'], 'Back to the list'],
    ],
  },
]

export const shortcutsEvents = new EventTarget()
export function openShortcuts() {
  shortcutsEvents.dispatchEvent(new Event('open'))
}

/** "?" anywhere (audit F6). Keyboard shortcuts are only worth having if they are findable. */
export function ShortcutsSheet() {
  const { user } = useAuth()
  const [open, setOpen] = React.useState(false)
  useHotkey('?', () => setOpen(true), { enabled: !!user })
  React.useEffect(() => {
    const onOpen = () => setOpen(true)
    shortcutsEvents.addEventListener('open', onOpen)
    return () => shortcutsEvents.removeEventListener('open', onOpen)
  }, [])
  if (!user) return null
  const isStaff = user.role !== 'CUSTOMER'

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-fade-in" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-[calc(100vw-2rem)] max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-xl border border-border bg-surface p-6 shadow-popover data-[state=open]:animate-slide-up">
          <div className="mb-4 flex items-center justify-between">
            <Dialog.Title className="text-lg font-semibold text-text">Keyboard shortcuts</Dialog.Title>
            <Dialog.Close aria-label="Close" className="flex size-8 items-center justify-center rounded-md text-text-subtle hover:bg-surface-2 hover:text-text">
              <X className="size-4" aria-hidden />
            </Dialog.Close>
          </div>
          <Dialog.Description className="sr-only">Keys that work on each screen.</Dialog.Description>
          <div className="grid gap-5 sm:grid-cols-2">
            {GROUPS.filter((g) => isStaff || !g.staffOnly).map((g) => (
              <section key={g.title}>
                <h3 className="mb-2 text-xs font-medium uppercase tracking-[0.08em] text-text-subtle">{g.title}</h3>
                <dl className="space-y-1.5">
                  {g.rows.map(([keys, what]) => (
                    <div key={what} className="flex items-center justify-between gap-3 text-sm">
                      <dt className="text-text-muted">{what}</dt>
                      <dd className="flex shrink-0 gap-1">
                        {keys.map((k) => (
                          <kbd key={k} className="min-w-6 rounded border border-border-control bg-surface-2 px-1.5 py-0.5 text-center font-mono text-xs text-text">
                            {k}
                          </kbd>
                        ))}
                      </dd>
                    </div>
                  ))}
                </dl>
              </section>
            ))}
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
