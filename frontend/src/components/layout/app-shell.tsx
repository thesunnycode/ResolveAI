import { Outlet } from 'react-router-dom'
import { MobileTopBar, Sidebar } from './nav-bar'

export function AppShell() {
  return (
    <div className="relative min-h-svh bg-bg">
      <div className="relative flex">
        <Sidebar />
        <div className="flex min-w-0 flex-1 flex-col">
          <MobileTopBar />
          <main className="flex-1">
            <Outlet />
          </main>
        </div>
      </div>
    </div>
  )
}
