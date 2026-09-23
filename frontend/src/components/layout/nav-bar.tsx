import * as DropdownMenu from '@radix-ui/react-dropdown-menu'
import {
  BookOpen,
  ChevronsUpDown,
  FlaskConical,
  Inbox,
  KanbanSquare,
  LogOut,
  Moon,
  Plus,
  Settings2,
  Siren,
  Sun,
  Ticket,
  type LucideIcon,
} from 'lucide-react'
import * as React from 'react'
import { NavLink } from 'react-router-dom'
import { Logomark } from '@/components/brand/logomark'
import { useAuth } from '@/features/auth/auth-context'
import { useIncidents } from '@/features/incidents/api'
import { useTicketQueue } from '@/features/tickets/api'
import { getStoredTheme, setTheme, type Theme } from '@/lib/theme'
import type { AuthUser } from '@/lib/types'
import { cn } from '@/lib/utils'

interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  count?: 'queue' | 'incidents'
}

const AGENT_NAV: NavItem[] = [
  { to: '/queue', label: 'Queue', icon: Inbox, count: 'queue' },
  { to: '/incidents', label: 'Incidents', icon: Siren, count: 'incidents' },
  { to: '/knowledge', label: 'Knowledge base', icon: BookOpen },
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

function initials(name: string) {
  return name
    .split(' ')
    .map((p) => p[0])
    .slice(0, 2)
    .join('')
    .toUpperCase()
}

function useThemeToggle() {
  const [theme, setThemeState] = React.useState<Theme>(
    () => getStoredTheme() ?? (document.documentElement.classList.contains('dark') ? 'dark' : 'light'),
  )
  return {
    theme,
    toggle() {
      const next: Theme = theme === 'dark' ? 'light' : 'dark'
      setTheme(next)
      setThemeState(next)
    },
  }
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
  return (
    <span
      className={cn(
        'ml-auto rounded-md px-1.5 py-px text-[11px] font-medium tabular-nums',
        kind === 'incidents' ? 'bg-warning-bg text-warning' : 'bg-surface-2 text-text-muted',
      )}
      title={kind === 'queue' ? 'Unassigned tickets' : 'Incidents awaiting confirmation'}
    >
      {n}
    </span>
  )
}

function NavSection({ label, items }: { label?: string; items: NavItem[] }) {
  return (
    <div>
      {label && (
        <p className="mb-1.5 px-2.5 text-[11px] font-medium uppercase tracking-[0.08em] text-text-subtle">{label}</p>
      )}
      <nav className="space-y-0.5">
        {items.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end
            className={({ isActive }) =>
              cn(
                'group relative flex h-9 items-center gap-2.5 rounded-md px-2.5 text-[13.5px] transition-colors',
                isActive
                  ? 'bg-surface-2 font-medium text-text shadow-card'
                  : 'text-text-muted hover:bg-surface-2/60 hover:text-text',
              )
            }
          >
            {({ isActive }) => (
              <>
                {isActive && (
                  <span className="absolute -left-3 top-1/2 h-4 w-[3px] -translate-y-1/2 rounded-r-full bg-primary shadow-[0_0_10px_var(--color-primary)]" />
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

function UserMenu({ user, compact }: { user: AuthUser; compact?: boolean }) {
  const { logout } = useAuth()
  const { theme, toggle } = useThemeToggle()
  const role = user.role.replace('_', ' ').toLowerCase()

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <button
          className={cn(
            'flex items-center gap-2.5 rounded-lg text-left transition-colors hover:bg-surface-2',
            compact ? 'p-1' : 'w-full p-2',
          )}
          aria-label="Account menu"
        >
          <span className="glass flex size-8 shrink-0 items-center justify-center rounded-full text-[11px] font-semibold text-text">
            {initials(user.fullName)}
          </span>
          {!compact && (
            <>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[13px] font-medium text-text">{user.fullName}</span>
                <span className="block truncate text-[11.5px] capitalize text-text-subtle">{role}</span>
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
            <p className="truncate text-[13px] font-medium text-text">{user.fullName}</p>
            <p className="truncate text-[12px] text-text-subtle">{user.email}</p>
          </div>
          <DropdownMenu.Item
            onSelect={(e) => {
              e.preventDefault()
              toggle()
            }}
            className="flex cursor-pointer items-center gap-2 rounded-md px-2.5 py-2 text-[13px] text-text outline-none data-[highlighted]:bg-surface-2"
          >
            {theme === 'dark' ? <Sun className="size-3.5" aria-hidden /> : <Moon className="size-3.5" aria-hidden />}
            {theme === 'dark' ? 'Light mode' : 'Dark mode'}
          </DropdownMenu.Item>
          <DropdownMenu.Item
            onSelect={() => void logout()}
            className="flex cursor-pointer items-center gap-2 rounded-md px-2.5 py-2 text-[13px] text-danger outline-none data-[highlighted]:bg-danger-bg"
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
        <p className="text-[14px] font-semibold capitalize tracking-tight text-text">
          {isCustomer ? `${user.tenantSlug} Support` : 'ResolveAI'}
        </p>
        <p className="truncate text-[11.5px] capitalize text-text-subtle">
          {isCustomer ? 'Help center' : `${user.tenantSlug} workspace`}
        </p>
      </div>
    </div>
  )
}

export function Sidebar() {
  const { user } = useAuth()
  if (!user) return null
  const isCustomer = user.role === 'CUSTOMER'

  return (
    <aside className="sticky top-0 hidden h-svh w-[248px] shrink-0 flex-col border-r border-border bg-glass px-3 py-4 backdrop-blur-xl md:flex">
      <div className="px-2 pb-6 pt-1">
        <Brand user={user} />
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

/** Below md the sidebar collapses into a slim top bar with the same destinations. */
export function MobileTopBar() {
  const { user } = useAuth()
  if (!user) return null
  const isCustomer = user.role === 'CUSTOMER'
  const items = isCustomer
    ? CUSTOMER_NAV
    : [
        ...AGENT_NAV,
        ...(user.role === 'TEAM_LEAD' || user.role === 'ADMIN' ? LEAD_NAV : []),
        ...(user.role === 'ADMIN' ? ADMIN_NAV : []),
      ]

  return (
    <header className="sticky top-0 z-40 border-b border-border bg-glass backdrop-blur-xl md:hidden">
      <div className="flex h-14 items-center justify-between px-4">
        <Brand user={user} />
        <UserMenu user={user} compact />
      </div>
      <nav className="flex gap-1 overflow-x-auto px-3 pb-2">
        {items.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end
            className={({ isActive }) =>
              cn(
                'flex shrink-0 items-center gap-1.5 rounded-md px-2.5 py-1.5 text-[13px]',
                isActive ? 'bg-surface-2 font-medium text-text' : 'text-text-muted',
              )
            }
          >
            <item.icon className="size-3.5" aria-hidden />
            {item.label}
          </NavLink>
        ))}
      </nav>
    </header>
  )
}
