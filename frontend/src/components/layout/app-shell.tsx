import { Outlet } from 'react-router-dom'
import { MobileTopBar, Sidebar } from './nav-bar'

/**
 * Two soft light sources behind everything, fixed to the viewport - the
 * blurred, matte depth from the references, kept deliberately faint so it
 * reads as atmosphere rather than decoration and never competes with a
 * priority or SLA color sitting on top of it.
 */
function AmbientBackdrop() {
  return (
    <div aria-hidden className="noise pointer-events-none fixed inset-0 overflow-hidden">
      <div className="absolute -right-40 -top-56 size-[640px] rounded-full bg-primary opacity-[0.07] blur-[140px] [.dark_&]:opacity-[0.11]" />
      <div className="absolute -bottom-72 left-1/4 size-[560px] rounded-full bg-[#6b7cff] opacity-[0.04] blur-[150px] [.dark_&]:opacity-[0.07]" />
    </div>
  )
}

export function AppShell() {
  return (
    <div className="relative min-h-svh bg-bg">
      <AmbientBackdrop />
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
