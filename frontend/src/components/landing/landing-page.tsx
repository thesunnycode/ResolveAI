import { Link } from 'react-router-dom'
import { ArrowRight, BellRing, Clock, FileCheck2, Inbox, Lock, Send, Sparkles } from 'lucide-react'
import { homeFor, useAuth } from '@/features/auth/auth-context'
import shot from '@/assets/app-tickets.jpg'

const steps = [
  { icon: Inbox, title: "Tickets come in", text: "They're sorted so the ones closest to being late are always on top." },
  { icon: Sparkles, title: "AI suggests a reply", text: "Every fact in the draft shows where it came from. Anything it can't back up is left out." },
  { icon: Send, title: "You check and send", text: 'Nothing goes to a customer until a person presses Send.' },
]

const features = [
  { icon: FileCheck2, title: 'Replies with sources', text: 'See the help article behind each sentence before you use it.' },
  { icon: BellRing, title: 'Outage alerts', text: "When many customers report the same problem, you're told right away." },
  { icon: Clock, title: 'Clear reply times', text: '"Reply in 12m" or "Late by 22m" on every ticket. No jargon.' },
  { icon: Lock, title: 'Private notes', text: 'Talk to your team inside a ticket. Customers never see it.' },
]

export function LandingPage() {
  const { user } = useAuth()
  const appLink = user ? homeFor(user.role) : '/login'

  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="border-b bg-card">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-6">
          <Link to="/" className="font-heading text-xl font-bold text-primary">ResolveAI</Link>
          <nav className="flex items-center gap-2">
            <Link to="/login" className="hidden rounded-full px-4 py-2 text-sm font-medium text-muted-foreground hover:text-foreground sm:inline-flex">Get help</Link>
            {!user && <Link to="/login" className="rounded-full px-4 py-2 text-sm font-medium hover:bg-accent">Sign in</Link>}
            <Link to={appLink} className="rounded-full bg-primary px-5 py-2 text-sm font-semibold text-primary-foreground hover:bg-primary/90">
              {user ? 'Go to app' : 'Try the demo'}
            </Link>
          </nav>
        </div>
      </header>

      <main>
        <section className="mx-auto grid max-w-6xl items-center gap-12 px-6 py-16 lg:grid-cols-[1fr_1.15fr] lg:py-24">
          <div>
            <p className="mb-4 inline-flex rounded-full border bg-card px-3 py-1 text-sm font-medium text-primary">Support desk with careful AI help</p>
            <h1 className="font-heading text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
              Answer every customer on time, with AI replies you can trust.
            </h1>
            <p className="mt-5 max-w-lg text-lg text-muted-foreground">
              ResolveAI puts the most urgent tickets first and drafts replies that show their sources. Your team checks, then sends.
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <Link to={appLink} className="inline-flex items-center gap-2 rounded-full bg-primary px-6 py-3 font-semibold text-primary-foreground hover:bg-primary/90">
                {user ? 'Go to app' : 'Try the demo'} <ArrowRight className="size-4" aria-hidden />
              </Link>
              <Link to="/login" className="inline-flex items-center gap-2 rounded-full border bg-card px-6 py-3 font-semibold hover:bg-accent">
                I need help
              </Link>
            </div>
            <p className="mt-4 text-sm text-muted-foreground">No sign-up needed. Pick a demo account and look around.</p>
          </div>
          <div className="relative">
            <div className="overflow-hidden rounded-2xl border bg-card shadow-lg">
              <img src={shot} alt="The ResolveAI Tickets screen, with the most urgent tickets first" className="w-full" width={1280} height={800} />
            </div>
            <span className="absolute -left-3 top-10 hidden rounded-full border border-danger-border bg-danger-soft px-3 py-1.5 text-sm font-semibold text-danger shadow-sm sm:inline-flex">Late by 22m</span>
            <span className="absolute -bottom-4 right-6 hidden items-center gap-1.5 rounded-full border bg-card px-3 py-1.5 text-sm font-semibold text-primary shadow-sm sm:inline-flex">
              <Sparkles className="size-4" aria-hidden /> AI reply with sources
            </span>
          </div>
        </section>

        <section className="border-y bg-card">
          <div className="mx-auto max-w-6xl px-6 py-16">
            <h2 className="font-heading text-3xl font-bold tracking-tight">How it works</h2>
            <p className="mt-2 text-muted-foreground">Three steps, every time.</p>
            <ol className="mt-10 grid gap-6 md:grid-cols-3">
              {steps.map((s, i) => (
                <li key={s.title} className="rounded-2xl border bg-background p-6">
                  <div className="flex items-center gap-3">
                    <span className="grid size-9 place-items-center rounded-full bg-primary font-semibold text-primary-foreground">{i + 1}</span>
                    <s.icon className="size-5 text-primary" aria-hidden />
                  </div>
                  <h3 className="mt-5 font-heading text-lg font-semibold">{s.title}</h3>
                  <p className="mt-2 text-muted-foreground">{s.text}</p>
                </li>
              ))}
            </ol>
          </div>
        </section>

        <section className="mx-auto max-w-6xl px-6 py-16">
          <h2 className="font-heading text-3xl font-bold tracking-tight">What your team gets</h2>
          <div className="mt-10 grid gap-6 sm:grid-cols-2">
            {features.map((f) => (
              <div key={f.title} className="flex gap-4 rounded-2xl border bg-card p-6">
                <span className="grid size-11 shrink-0 place-items-center rounded-xl bg-accent text-primary">
                  <f.icon className="size-5" aria-hidden />
                </span>
                <div>
                  <h3 className="font-heading text-lg font-semibold">{f.title}</h3>
                  <p className="mt-1 text-muted-foreground">{f.text}</p>
                </div>
              </div>
            ))}
          </div>
        </section>

        <section className="mx-auto max-w-6xl px-6">
          <div className="flex flex-col items-start justify-between gap-4 rounded-2xl border bg-accent p-8 sm:flex-row sm:items-center">
            <div>
              <h2 className="font-heading text-2xl font-semibold">Need help with an order or account?</h2>
              <p className="mt-1 text-muted-foreground">Sign in and open a ticket. We'll keep you updated here.</p>
            </div>
            <Link to="/login" className="rounded-full bg-primary px-6 py-3 font-semibold text-primary-foreground hover:bg-primary/90">Open a ticket</Link>
          </div>
        </section>

        <section className="mx-auto max-w-6xl px-6 py-20 text-center">
          <h2 className="font-heading text-3xl font-bold tracking-tight">See it in one minute</h2>
          <p className="mt-2 text-muted-foreground">Pick a demo account: customer, agent, team lead or admin.</p>
          <Link to={appLink} className="mt-8 inline-flex items-center gap-2 rounded-full bg-primary px-7 py-3 font-semibold text-primary-foreground hover:bg-primary/90">
            {user ? 'Go to app' : 'Try the demo'} <ArrowRight className="size-4" aria-hidden />
          </Link>
        </section>
      </main>

      <footer className="border-t bg-card">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3 px-6 py-6 text-sm text-muted-foreground">
          <span>© 2026 ResolveAI</span>
          <div className="flex gap-5">
            <Link to="/login" className="hover:text-foreground">Sign in</Link>
            <Link to="/register" className="hover:text-foreground">Create account</Link>
          </div>
        </div>
      </footer>
    </div>
  )
}
