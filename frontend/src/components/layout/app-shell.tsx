import { Outlet } from 'react-router-dom'
import { CommandPalette } from '@/features/command/command-palette'
import { ShortcutsSheet } from '@/features/command/shortcuts-sheet'
import { MobileBottomNav, MobileTopBar, Sidebar } from './nav-bar'
import { RouteAnnouncer } from './route-a11y'

export function AppShell() {
  return (
    <div className="relative min-h-svh bg-bg">
      {/* First tab stop (audit A7): skips the sidebar's navigation. */}
      <a href="#main-content" className="skip-link">
        Skip to content
      </a>
      <div className="relative flex">
        <Sidebar />
        <div className="flex min-w-0 flex-1 flex-col">
          <MobileTopBar />
          {/* Bottom padding on mobile clears the fixed bottom navigation. */}
          <main id="main-content" tabIndex={-1} className="flex-1 pb-16 outline-none md:pb-0">
            <Outlet />
          </main>
        </div>
      </div>
      <MobileBottomNav />
      <CommandPalette />
      <ShortcutsSheet />
      <RouteAnnouncer />
    </div>
  )
}
