import { Outlet } from 'react-router-dom'
import { CommandPalette } from '@/features/command/command-palette'
import { ShortcutsSheet } from '@/features/command/shortcuts-sheet'
import { AppNav } from './nav-bar'
import { RouteAnnouncer } from './route-a11y'

export function AppShell() {
  return (
    <div className="min-h-screen bg-background">
      {/* First tab stop (audit A7): skips the header's navigation. */}
      <a href="#main-content" className="skip-link">
        Skip to content
      </a>
      <AppNav />
      <main id="main-content" tabIndex={-1} className="min-w-0 outline-none">
        <Outlet />
      </main>
      <CommandPalette />
      <ShortcutsSheet />
      <RouteAnnouncer />
    </div>
  )
}
