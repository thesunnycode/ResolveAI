import * as Dialog from '@radix-ui/react-dialog'
import { useQuery } from '@tanstack/react-query'
import {
  BookOpen,
  CornerDownLeft,
  FlaskConical,
  Inbox,
  KanbanSquare,
  Plus,
  Search,
  Settings2,
  Siren,
  Ticket,
  type LucideIcon,
} from 'lucide-react'
import * as React from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/features/auth/auth-context'
import { api } from '@/lib/api-client'
import { useHotkey } from '@/lib/hotkeys'
import type { CursorPage, IncidentSummary, Role, TicketSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

interface Item {
  id: string
  group: 'Tickets' | 'Incidents' | 'Go to'
  label: string
  hint?: string
  icon: LucideIcon
  run: () => void
}

const PAGES: { label: string; to: string; icon: LucideIcon; roles: Role[] }[] = [
  { label: 'Queue', to: '/queue', icon: Inbox, roles: ['AGENT', 'TEAM_LEAD', 'ADMIN'] },
  { label: 'Incidents', to: '/incidents', icon: Siren, roles: ['AGENT', 'TEAM_LEAD', 'ADMIN'] },
  { label: 'Knowledge base', to: '/knowledge', icon: BookOpen, roles: ['AGENT', 'TEAM_LEAD', 'ADMIN'] },
  { label: 'Board', to: '/board', icon: KanbanSquare, roles: ['TEAM_LEAD', 'ADMIN'] },
  { label: 'Settings', to: '/admin/settings', icon: Settings2, roles: ['ADMIN'] },
  { label: 'Evaluation', to: '/admin/evaluation', icon: FlaskConical, roles: ['ADMIN'] },
  { label: 'My tickets', to: '/tickets', icon: Ticket, roles: ['CUSTOMER'] },
  { label: 'New ticket', to: '/tickets/new', icon: Plus, roles: ['CUSTOMER'] },
]

/** Open from anywhere with ⌘K / Ctrl+K, or the search button in the sidebar. */
export const commandPaletteEvents = new EventTarget()
export function openCommandPalette() {
  commandPaletteEvents.dispatchEvent(new Event('open'))
}

function useDebounced<T>(value: T, ms: number) {
  const [v, setV] = React.useState(value)
  React.useEffect(() => {
    const t = setTimeout(() => setV(value), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return v
}

/**
 * ⌘K (audit H3): search tickets by text or exact reference, jump to an incident, or go to
 * any page the role can see - without the mouse. Search was previously only inside the
 * queue page, so finding "the ticket that customer emailed about" meant navigating first.
 */
export function CommandPalette() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [open, setOpen] = React.useState(false)
  const [query, setQuery] = React.useState('')
  const [active, setActive] = React.useState(0)
  const q = useDebounced(query.trim(), 200)
  const isStaff = !!user && user.role !== 'CUSTOMER'

  useHotkey('mod+k', () => setOpen((o) => !o), { enabled: !!user, allowInInputs: true })
  React.useEffect(() => {
    const onOpen = () => setOpen(true)
    commandPaletteEvents.addEventListener('open', onOpen)
    return () => commandPaletteEvents.removeEventListener('open', onOpen)
  }, [])
  React.useEffect(() => {
    if (!open) {
      setQuery('')
      setActive(0)
    }
  }, [open])

  const tickets = useQuery({
    queryKey: ['palette-tickets', q],
    queryFn: async () =>
      (await api.get<CursorPage<TicketSummary>>(`/tickets?${new URLSearchParams({ q, size: '6' })}`)).data.data,
    enabled: open && q.length >= 2,
    staleTime: 10_000,
  })
  const incidents = useQuery({
    queryKey: ['palette-incidents'],
    queryFn: async () =>
      (await api.get<CursorPage<IncidentSummary>>('/incidents?status=PROPOSED,CONFIRMED,MITIGATED')).data.data,
    enabled: open && isStaff,
    staleTime: 30_000,
  })

  const go = React.useCallback(
    (to: string) => {
      setOpen(false)
      navigate(to)
    },
    [navigate],
  )

  const items: Item[] = React.useMemo(() => {
    if (!user) return []
    const needle = query.trim().toLowerCase()
    const out: Item[] = []
    for (const t of tickets.data ?? []) {
      out.push({
        id: `t${t.id}`,
        group: 'Tickets',
        label: t.subject,
        hint: `${t.reference} · ${t.status.replace(/_/g, ' ').toLowerCase()}`,
        icon: Ticket,
        run: () => go(`/tickets/${t.id}`),
      })
    }
    for (const i of incidents.data ?? []) {
      if (needle && !`${i.reference} ${i.title}`.toLowerCase().includes(needle)) continue
      out.push({
        id: `i${i.id}`,
        group: 'Incidents',
        label: i.title,
        hint: i.reference,
        icon: Siren,
        run: () => go(`/incidents/${i.id}`),
      })
    }
    for (const p of PAGES) {
      if (!p.roles.includes(user.role)) continue
      if (needle && !p.label.toLowerCase().includes(needle)) continue
      out.push({ id: `p${p.to}`, group: 'Go to', label: p.label, icon: p.icon, run: () => go(p.to) })
    }
    return out
  }, [user, query, tickets.data, incidents.data, go])

  React.useEffect(() => setActive(0), [query])

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive((a) => Math.min(a + 1, items.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((a) => Math.max(a - 1, 0))
    } else if (e.key === 'Enter' && items[active]) {
      e.preventDefault()
      items[active].run()
    }
  }

  if (!user) return null
  let lastGroup = ''

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-fade-in" />
        <Dialog.Content
          aria-describedby={undefined}
          className="fixed left-1/2 top-[12vh] z-50 w-[calc(100vw-2rem)] max-w-xl -translate-x-1/2 overflow-hidden rounded-xl border border-border bg-surface shadow-popover data-[state=open]:animate-slide-up"
        >
          <Dialog.Title className="sr-only">Search and jump</Dialog.Title>
          <div className="flex items-center gap-2.5 border-b border-border px-4">
            <Search className="size-4 shrink-0 text-text-subtle" aria-hidden />
            <input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={onKeyDown}
              placeholder={isStaff ? 'Search tickets (text or TKT-1234), incidents, pages…' : 'Search your tickets and pages…'}
              aria-label="Search"
              role="combobox"
              aria-expanded
              aria-controls="palette-results"
              aria-activedescendant={items[active] ? `palette-${items[active].id}` : undefined}
              className="h-12 flex-1 bg-transparent text-base text-text outline-none placeholder:text-text-subtle"
            />
            <kbd className="rounded border border-border px-1.5 py-0.5 font-mono text-xs text-text-subtle">esc</kbd>
          </div>
          <ul id="palette-results" role="listbox" aria-label="Results" className="max-h-[50vh] overflow-y-auto p-1.5">
            {items.length === 0 && (
              <li className="px-3 py-6 text-center text-sm text-text-muted">
                {query.trim().length >= 2 && tickets.isFetching ? 'Searching…' : 'No matches.'}
              </li>
            )}
            {items.map((item, idx) => {
              const header = item.group !== lastGroup ? item.group : null
              lastGroup = item.group
              return (
                <React.Fragment key={item.id}>
                  {header && (
                    <li role="presentation" className="px-2.5 pb-1 pt-2 text-xs font-medium uppercase tracking-[0.08em] text-text-subtle">
                      {header}
                    </li>
                  )}
                  <li
                    id={`palette-${item.id}`}
                    role="option"
                    aria-selected={idx === active}
                    onMouseMove={() => setActive(idx)}
                    onClick={item.run}
                    className={cn(
                      'flex cursor-pointer items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm',
                      idx === active ? 'bg-surface-2 text-text' : 'text-text-muted',
                    )}
                  >
                    <item.icon className="size-4 shrink-0 text-text-subtle" aria-hidden />
                    <span className="min-w-0 flex-1 truncate text-text">{item.label}</span>
                    {item.hint && <span className="shrink-0 text-xs text-text-subtle">{item.hint}</span>}
                    {idx === active && <CornerDownLeft className="size-3.5 shrink-0 text-text-subtle" aria-hidden />}
                  </li>
                </React.Fragment>
              )
            })}
          </ul>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
