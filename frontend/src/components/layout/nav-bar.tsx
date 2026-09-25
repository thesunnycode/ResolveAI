import * as DropdownMenu from '@radix-ui/react-dropdown-menu'
import {
  BookOpen,
  Check,
  ChevronsUpDown,
  FlaskConical,
  Inbox,
  KanbanSquare,
  Keyboard,
  LogOut,
  Monitor,
  Moon,
  MoreHorizontal,
  Plus,
  Search,
  Settings2,
  Siren,
  Sun,
  Ticket,
  type LucideIcon,
} from 'lucide-react'
import * as React from 'react'
import { NavLink, useLocation } from 'react-router-dom'
import { Logomark } from '@/components/brand/logomark'
import { useAuth } from '@/features/auth/auth-context'
import { openCommandPalette } from '@/features/command/command-palette'
import { openShortcuts } from '@/features/command/shortcuts-sheet'
import { useIncidents } from '@/features/incidents/api'
import { useTicketQueue } from '@/features/tickets/api'
import { getStoredTheme, setThemePreference, type ThemePreference } from '@/lib/theme'
import type { AuthUser } from '@/lib/types'
import { cn } from '@/lib/utils'

interface NavItem {
  to: string
  label: string
  short?: string
  icon: LucideIcon
  count?: 'queue' | 'incidents'
}

const AGENT_NAV: NavItem[] = [
  { to: '/queue', label: 'Queue', icon: Inbox, count: 'queue' },
  { to: '/incidents', label: 'Incidents', icon: Siren, count: 'incidents' },
  { to: '/knowledge', label: 'Knowledge base', short: 'Knowledge', icon: BookOpen },
]

const LEAD_NAV: NavItem[] = [{ to: '/board', label: 'Board', icon: KanbanSquare }]

const ADMIN_NAV: NavItem[] = [
  { to: '/admin/settings', label: 'Settings', icon: Settings2 },
  { to: '/admin/evaluation', label: 'Evaluation', icon: FlaskConical },
]

const CUSTOMER_NAV: NavItem[] = [
  { to: '/my-tickets', label: 'My tickets', icon: Ticket },
  { to: '/tickets/new', label: 'New ticket', icon: Plus },
]

const MOD = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘' : 'Ctrl'

function itemsFor(user: AuthUser): NavItem[] {
  if (user.role === 'CUSTOMER') return CUSTOMER_NAV
  return [
    ...AGENT_NAV,
    ...(user.role === 'TEAM_LEAD' || user.role === 'ADMIN' ? LEAD_NAV : []),
    ...(user.role === 'ADMIN' ? ADMIN_NAV : []),
  ]
}

function initials(name: string) {
  return name
    .split(' ')
    .map((p) => p[0])
    .slice(0, 2)
    .join('')
    .toUpperCase()
}

function Counts({ kind }: { kind: 'queue' | 'incidents' }) {
  // Both queries are already cached by the pages they belong to, so the
  // sidebar badge costs nothing extra once the user has visited them.
  const queue = useTicketQueue({ assigneeId: 'none' })
  const incidents = useIncidents('PROPOSED')
  const n =
    kind === 'queue'
      ? queue.data
        ? `${queue.data.data.length}${queue.data.pagination.hasNext ? '+' : ''}`
        : null
      : incidents.data
        ? String(incidents.data.data.length)
        : null
  if (!n || n === '0') return null
  const label = kind === 'queue' ? `${n} unassigned` : `${n} awaiting confirmation`
  return (
    <span
      className={cn(
        'ml-auto rounded-md px-1.5 py-px text-xs font-medium tabular-nums',
        kind === 'incidents' ? 'bg-warning-bg text-warning' : 'bg-surface-2 text-text-muted',
      )}
      title={label}
    >
      <span aria-hidden>{n}</span>
      <span className="sr-only">{label}</span>
    </span>
  )
}

function NavSection({ label, items }: { label?: string; items: NavItem[] }) {
  return (
    <div>
      {label && (
        <p className="mb-1.5 px-2.5 text-xs font-medium uppercase tracking-[0.08em] text-text-subtle">{label}</p>
      )}
      <nav aria-label={label ?? 'Main'} className="space-y-0.5">
        {items.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end
            className={({ isActive }) =>
              cn(
                'group relative flex h-9 items-center gap-2.5 rounded-md px-2.5 text-base transition-colors',
                isActive
                  ? 'bg-surface-2 font-medium text-text shadow-card'
                  : 'text-text-muted hover:bg-surface-2/60 hover:text-text',
              )
            }
          >
            {({ isActive }) => (
              <>
                {isActive && (
                  <span className="absolute -left-3 top-1/2 h-4 w-[3px] -translate-y-1/2 rounded-r-full bg-primary" />
                )}
                <item.icon className={cn('size-4', isActive ? 'text-text' : 'text-text-subtle group-hover:text-text-muted')} aria-hidden />
                {item.label}
                {item.count && <Counts kind={item.count} />}
              </>
            )}
          </NavLink>
        ))}
      </nav>
    </div>
  )
}

const THEMES: { value: ThemePreference; label: string; icon: LucideIcon }[] = [
  { value: 'system', label: 'Match system', icon: Monitor },
  { value: 'light', label: 'Light', icon: Sun },
  { value: 'dark', label: 'Dark', icon: Moon },
]

const menuItem =
  'flex cursor-pointer items-center gap-2 rounded-md px-2.5 py-2 text-sm text-text outline-none data-[highlighted]:bg-surface-2'

function UserMenu({ user, compact }: { user: AuthUser; compact?: boolean }) {
  const { logout } = useAuth()
  const [theme, setTheme] = React.useState<ThemePreference>(getStoredTheme)
  const role = user.role.replace('_', ' ').toLowerCase()

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <button
          className={cn(
            'flex items-center gap-2.5 rounded-lg text-left transition-colors hover:bg-surface-2',
            compact ? 'p-1' : 'w-full p-2',
          )}
          aria-label={`Account menu for ${user.fullName}`}
        >
          <span className="glass flex size-8 shrink-0 items-center justify-center rounded-full text-xs font-semibold text-text">
            {initials(user.fullName)}
          </span>
          {!compact && (
            <>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium text-text">{user.fullName}</span>
                <span className="block truncate text-xs capitalize text-text-subtle">{role}</span>
              </span>
              <ChevronsUpDown className="size-3.5 text-text-subtle" aria-hidden />
            </>
          )}
        </button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          side={compact ? 'bottom' : 'top'}
          align={compact ? 'end' : 'start'}
          sideOffset={8}
          className="z-50 min-w-56 rounded-xl border border-border bg-surface p-1.5 shadow-popover data-[state=open]:animate-slide-up"
        >
          <div className="mb-1 border-b border-border px-2.5 pb-2.5 pt-1.5">
            <p className="truncate text-sm font-medium text-text">{user.fullName}</p>
            <p className="truncate text-xs text-text-subtle">{user.email}</p>
          </div>
          <DropdownMenu.Label className="px-2.5 pb-1 pt-1.5 text-xs font-medium uppercase tracking-[0.08em] text-text-subtle">
            Theme
          </DropdownMenu.Label>
          <DropdownMenu.RadioGroup
            value={theme}
            onValueChange={(v) => {
              const pref = v as ThemePreference
              setThemePreference(pref)
              setTheme(pref)
            }}
          >
            {THEMES.map((t) => (
              <DropdownMenu.RadioItem
                key={t.value}
                value={t.value}
                onSelect={(e) => e.preventDefault()}
                className={menuItem}
              >
                <t.icon className="size-3.5 text-text-subtle" aria-hidden />
                <span className="flex-1">{t.label}</span>
                <DropdownMenu.ItemIndicator>
                  <Check className="size-3.5" aria-hidden />
                </DropdownMenu.ItemIndicator>
              </DropdownMenu.RadioItem>
            ))}
          </DropdownMenu.RadioGroup>
          <DropdownMenu.Separator className="my-1 h-px bg-border" />
          <DropdownMenu.Item onSelect={() => openShortcuts()} className={menuItem}>
            <Keyboard className="size-3.5 text-text-subtle" aria-hidden />
            <span className="flex-1">Keyboard shortcuts</span>
            <kbd className="font-mono text-xs text-text-subtle">?</kbd>
          </DropdownMenu.Item>
          <DropdownMenu.Item
            onSelect={() => void logout()}
            className="flex cursor-pointer items-center gap-2 rounded-md px-2.5 py-2 text-sm text-danger outline-none data-[highlighted]:bg-danger-bg"
          >
            <LogOut className="size-3.5" aria-hidden /> Log out
          </DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  )
}

function Brand({ user }: { user: AuthUser }) {
  const isCustomer = user.role === 'CUSTOMER'
  return (
    <div className="flex items-center gap-2.5">
      <Logomark size={28} />
      <div className="min-w-0 leading-tight">
        <p className="text-base font-semibold capitalize tracking-tight text-text">
          {isCustomer ? `${user.tenantSlug} Support` : 'ResolveAI'}
        </p>
        <p className="truncate text-xs capitalize text-text-subtle">
          {isCustomer ? 'Help center' : `${user.tenantSlug} workspace`}
        </p>
      </div>
    </div>
  )
}

function SearchButton({ compact }: { compact?: boolean }) {
  if (compact) {
    return (
      <button
        type="button"
        onClick={openCommandPalette}
        aria-label="Search"
        className="flex size-10 items-center justify-center rounded-lg text-text-muted hover:bg-surface-2 hover:text-text"
      >
        <Search className="size-4" aria-hidden />
      </button>
    )
  }
  return (
    <button
      type="button"
      onClick={openCommandPalette}
      className="flex h-9 w-full items-center gap-2 rounded-md border border-border px-2.5 text-sm text-text-subtle transition-colors hover:border-border-strong hover:text-text-muted"
    >
      <Search className="size-3.5" aria-hidden />
      <span className="flex-1 text-left">Search…</span>
      <kbd className="font-mono text-xs">{MOD} K</kbd>
    </button>
  )
}

export function Sidebar() {
  const { user } = useAuth()
  if (!user) return null
  const isCustomer = user.role === 'CUSTOMER'

  return (
    <aside className="sticky top-0 hidden h-svh w-[248px] shrink-0 flex-col border-r border-border bg-glass px-3 py-4 backdrop-blur-xl md:flex">
      <div className="px-2 pb-4 pt-1">
        <Brand user={user} />
      </div>
      <div className="px-1 pb-5">
        <SearchButton />
      </div>
      <div className="flex-1 space-y-6 overflow-y-auto">
        {isCustomer ? (
          <NavSection items={CUSTOMER_NAV} />
        ) : (
          <>
            <NavSection
              label="Support"
              items={user.role === 'TEAM_LEAD' || user.role === 'ADMIN' ? [...AGENT_NAV, ...LEAD_NAV] : AGENT_NAV}
            />
            {user.role === 'ADMIN' && <NavSection label="Admin" items={ADMIN_NAV} />}
          </>
        )}
      </div>
      <div className="border-t border-border pt-3">
        <UserMenu user={user} />
      </div>
    </aside>
  )
}

/** Below md: a slim top bar (brand, search, account); navigation lives in the bottom bar. */
export function MobileTopBar() {
  const { user } = useAuth()
  if (!user) return null
  return (
    <header className="sticky top-0 z-40 border-b border-border bg-glass backdrop-blur-xl md:hidden">
      <div className="flex h-14 items-center justify-between gap-2 px-4">
        <Brand user={user} />
        <div className="flex items-center gap-1">
          <SearchButton compact />
          <UserMenu user={user} compact />
        </div>
      </div>
    </header>
  )
}

/**
 * Mobile navigation (audit R4). It used to be a horizontally scrolling text strip in the
 * top bar with Board, Settings and Evaluation off-screen and nothing saying so. Now: up to
 * four destinations as thumb-reachable tabs, the rest under "More".
 */
export function MobileBottomNav() {
  const { user } = useAuth()
  const location = useLocation()
  if (!user) return null
  const items = itemsFor(user)
  const primary = items.length <= 4 ? items : items.slice(0, 3)
  const overflow = items.length <= 4 ? [] : items.slice(3)
  const overflowActive = overflow.some((i) => location.pathname.startsWith(i.to))

  const tab = (active: boolean) =>
    cn(
      'flex min-h-14 flex-1 flex-col items-center justify-center gap-1 text-xs',
      active ? 'font-medium text-text' : 'text-text-muted',
    )

  return (
    <nav
      aria-label="Main"
      className="fixed inset-x-0 bottom-0 z-40 flex border-t border-border bg-glass pb-[env(safe-area-inset-bottom)] backdrop-blur-xl md:hidden"
    >
      {primary.map((item) => (
        <NavLink key={item.to} to={item.to} end className={({ isActive }) => tab(isActive)}>
          <item.icon className="size-5" aria-hidden />
          {item.short ?? item.label}
        </NavLink>
      ))}
      {overflow.length > 0 && (
        <DropdownMenu.Root>
          <DropdownMenu.Trigger className={tab(overflowActive)} aria-label="More destinations">
            <MoreHorizontal className="size-5" aria-hidden />
            More
          </DropdownMenu.Trigger>
          <DropdownMenu.Portal>
            <DropdownMenu.Content
              side="top"
              align="end"
              sideOffset={8}
              className="z-50 min-w-48 rounded-xl border border-border bg-surface p-1.5 shadow-popover"
            >
              {overflow.map((item) => (
                <DropdownMenu.Item key={item.to} asChild className={menuItem}>
                  <NavLink to={item.to}>
                    <item.icon className="size-4 text-text-subtle" aria-hidden />
                    {item.label}
                  </NavLink>
                </DropdownMenu.Item>
              ))}
            </DropdownMenu.Content>
          </DropdownMenu.Portal>
        </DropdownMenu.Root>
      )}
    </nav>
  )
}
