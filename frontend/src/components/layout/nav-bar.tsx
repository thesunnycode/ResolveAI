import * as React from 'react'
import { NavLink } from 'react-router-dom'
import {
  BookOpen,
  FlaskConical,
  Inbox,
  KanbanSquare,
  Keyboard,
  LogOut,
  Menu,
  Monitor,
  Moon,
  Plus,
  Search,
  Settings2,
  Siren,
  Sun,
  Ticket,
  type LucideIcon,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Sheet, SheetContent, SheetTitle, SheetTrigger } from '@/components/ui/sheet'
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
  icon: LucideIcon
  count?: 'queue' | 'incidents'
}

const AGENT_NAV: NavItem[] = [
  { to: '/queue', label: 'Tickets', icon: Inbox, count: 'queue' },
  { to: '/incidents', label: 'Outages', icon: Siren, count: 'incidents' },
  { to: '/knowledge', label: 'Help articles', icon: BookOpen },
]

const LEAD_NAV: NavItem[] = [{ to: '/board', label: 'Board', icon: KanbanSquare }]

const ADMIN_NAV: NavItem[] = [
  { to: '/settings', label: 'Settings', icon: Settings2 },
  { to: '/evaluation', label: 'Evaluation', icon: FlaskConical },
]

const CUSTOMER_NAV: NavItem[] = [
  { to: '/tickets', label: 'My tickets', icon: Ticket },
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
  // nav badge costs nothing extra once the user has visited them.
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
        'rounded-full px-2 text-xs font-semibold leading-5',
        kind === 'incidents' ? 'bg-danger-soft text-danger' : 'bg-primary text-primary-foreground',
      )}
      title={label}
    >
      <span aria-hidden>{n}</span>
      <span className="sr-only">{label}</span>
    </span>
  )
}

function NavList({ items, vertical, onNavigate }: { items: NavItem[]; vertical?: boolean; onNavigate?: () => void }) {
  return (
    <nav aria-label="Main" className={cn('flex gap-1', vertical && 'flex-col')}>
      {items.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          end
          onClick={onNavigate}
          className={({ isActive }) =>
            cn(
              'flex h-10 items-center gap-2 rounded-xl px-4 font-medium text-muted-foreground transition-colors hover:bg-secondary hover:text-primary',
              isActive && '!text-primary bg-secondary font-semibold',
            )
          }
        >
          {vertical && <item.icon className="size-4" aria-hidden />}
          {item.label}
          {item.count && <Counts kind={item.count} />}
        </NavLink>
      ))}
    </nav>
  )
}

const THEMES: { value: ThemePreference; label: string; icon: LucideIcon }[] = [
  { value: 'system', label: 'Match system', icon: Monitor },
  { value: 'light', label: 'Light', icon: Sun },
  { value: 'dark', label: 'Dark', icon: Moon },
]

function UserMenu({ user }: { user: AuthUser }) {
  const { logout } = useAuth()
  const [theme, setTheme] = React.useState<ThemePreference>(getStoredTheme)
  const role = user.role.replace('_', ' ').toLowerCase()

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          aria-label={`Account menu for ${user.fullName}`}
          className="flex items-center gap-3 rounded-full py-1 pl-3 pr-1 text-left hover:bg-secondary focus-visible:outline-2 focus-visible:outline-ring"
        >
          <span className="hidden text-right leading-tight sm:block">
            <span className="block truncate text-sm font-semibold">{user.fullName}</span>
            <span className="block truncate text-xs capitalize text-muted-foreground">{role}</span>
          </span>
          <span className="grid size-10 shrink-0 place-items-center rounded-full bg-primary font-heading text-sm font-semibold text-primary-foreground">
            {initials(user.fullName)}
          </span>
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-60">
        <DropdownMenuLabel className="font-normal">
          <div className="font-medium">{user.fullName}</div>
          <div className="text-xs text-muted-foreground">{user.email}</div>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuLabel className="text-xs font-medium uppercase tracking-[0.08em] text-muted-foreground">
          Theme
        </DropdownMenuLabel>
        <DropdownMenuRadioGroup
          value={theme}
          onValueChange={(v) => {
            const pref = v as ThemePreference
            setThemePreference(pref)
            setTheme(pref)
          }}
        >
          {THEMES.map((t) => (
            <DropdownMenuRadioItem key={t.value} value={t.value} onSelect={(e) => e.preventDefault()}>
              <t.icon className="size-3.5 text-muted-foreground" aria-hidden />
              {t.label}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => openShortcuts()}>
          <Keyboard className="size-3.5 text-muted-foreground" aria-hidden />
          <span className="flex-1">Keyboard shortcuts</span>
          <kbd className="font-mono text-xs text-muted-foreground">?</kbd>
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={() => void logout()} className="text-danger focus:bg-danger-soft focus:text-danger">
          <LogOut className="size-3.5" aria-hidden /> Log out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function Brand({ user }: { user: AuthUser }) {
  const isCustomer = user.role === 'CUSTOMER'
  return (
    <div className="flex items-center gap-2.5">
      <Logomark size={28} />
      <div className="hidden min-w-0 leading-tight sm:block">
        <p className="font-heading truncate text-base font-semibold capitalize tracking-tight text-foreground">
          {isCustomer ? `${user.tenantSlug} Support` : 'ResolveAI'}
        </p>
        <p className="truncate text-xs capitalize text-muted-foreground">
          {isCustomer ? 'Help center' : `${user.tenantSlug} workspace`}
        </p>
      </div>
    </div>
  )
}

function SearchButton() {
  return (
    <button
      type="button"
      onClick={openCommandPalette}
      aria-label="Search"
      className="flex h-9 items-center gap-2 rounded-xl border border-border px-2.5 text-sm text-muted-foreground transition-colors hover:border-input hover:text-foreground"
    >
      <Search className="size-3.5" aria-hidden />
      <span className="hidden md:inline">Search…</span>
      <kbd className="hidden font-mono text-xs md:inline">{MOD} K</kbd>
    </button>
  )
}

export function AppNav() {
  const { user } = useAuth()
  const [open, setOpen] = React.useState(false)
  if (!user) return null
  const items = itemsFor(user)

  return (
    <header className="sticky top-0 z-30 border-b bg-card">
      <div className="mx-auto flex h-[72px] max-w-[1280px] items-center gap-4 px-4 md:px-8">
        <Sheet open={open} onOpenChange={setOpen}>
          <SheetTrigger asChild>
            <Button variant="ghost" size="icon" aria-label="Open menu" className="md:hidden">
              <Menu />
            </Button>
          </SheetTrigger>
          <SheetContent side="left" className="w-[280px] p-5">
            <SheetTitle className="mb-4">
              <Brand user={user} />
            </SheetTitle>
            <NavList items={items} vertical onNavigate={() => setOpen(false)} />
          </SheetContent>
        </Sheet>
        <Brand user={user} />
        <div className="hidden md:block">
          <NavList items={items} />
        </div>
        <div className="ml-auto flex items-center gap-2">
          <SearchButton />
          <UserMenu user={user} />
        </div>
      </div>
    </header>
  )
}
