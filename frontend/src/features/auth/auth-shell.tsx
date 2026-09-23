import { Check, Sparkles } from 'lucide-react'
import type * as React from 'react'
import { Logomark } from '@/components/brand/logomark'
import { Eyebrow } from '@/components/layout/page-header'

const VALUE_PROPS = [
  'SLA clocks that pause and resume on their own — never guessed at',
  'AI drafts grounded in your knowledge base, every claim cited',
  'Related tickets grouped into one incident before the pile-up',
]

/**
 * The one graphic on this screen is the thing a user will look at all day
 * once inside: an SLA clock at ~70%, with two queued tickets behind it.
 */
function SlaPreview() {
  const radius = 58
  const circumference = 2 * Math.PI * radius
  const progress = 0.7

  return (
    <div className="relative isolate mx-auto w-full max-w-[420px]">
      <div className="glass relative rounded-2xl bg-surface/40 p-6">
        <div className="mb-5 flex items-center justify-between">
          <div>
            <p className="text-[11.5px] text-text-subtle">TKT-1042 · P2</p>
            <p className="mt-0.5 text-[14px] font-medium text-text">Checkout returns 502 for EU cards</p>
          </div>
          <span className="rounded-md bg-warning-bg px-2 py-0.5 text-[11px] font-medium text-warning">At risk</span>
        </div>
        <div className="flex items-center gap-6">
          <div className="relative size-[140px] shrink-0">
            <svg viewBox="0 0 140 140" className="size-full -rotate-90">
              <circle cx="70" cy="70" r={radius} stroke="var(--color-border-strong)" strokeWidth="8" fill="none" />
              <circle
                cx="70"
                cy="70"
                r={radius}
                stroke="var(--color-primary)"
                strokeWidth="8"
                strokeLinecap="round"
                fill="none"
                strokeDasharray={circumference}
                strokeDashoffset={circumference * (1 - progress)}
              />
            </svg>
            <div className="absolute inset-0 flex flex-col items-center justify-center">
              <span className="text-[22px] font-semibold tracking-tight text-text">34m</span>
              <span className="text-[11px] text-text-subtle">to first reply</span>
            </div>
          </div>
          <div className="min-w-0 flex-1 space-y-2.5">
            <div className="rounded-lg border border-border bg-surface-2/60 p-2.5">
              <p className="flex items-center gap-1.5 text-[11.5px] font-medium text-primary">
                <Sparkles className="size-3" aria-hidden /> Draft ready
              </p>
              <p className="mt-1 text-[12px] leading-snug text-text-muted">2 of 2 claims cited from “EU payments runbook”.</p>
            </div>
            <div className="rounded-lg border border-border bg-surface-2/60 p-2.5">
              <p className="text-[11.5px] font-medium text-text">INC-07 · 14 linked</p>
              <p className="mt-1 text-[12px] leading-snug text-text-muted">Arrival rate 4.2× baseline.</p>
            </div>
          </div>
        </div>
      </div>
      <div className="glass absolute -bottom-5 -right-5 -z-10 h-full w-full rounded-2xl opacity-50" />
    </div>
  )
}

export function AuthShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="relative flex min-h-svh overflow-hidden bg-bg">
      <div className="relative flex w-full flex-col px-6 py-8 sm:px-12 lg:w-[480px] lg:shrink-0 lg:px-14">
        <div className="flex items-center gap-2.5">
          <Logomark size={30} />
          <span className="text-[15px] font-semibold tracking-tight text-text">ResolveAI</span>
        </div>
        <div className="flex flex-1 items-center">
          <div className="w-full max-w-[360px] py-12">{children}</div>
        </div>
        <p className="text-[12px] text-text-subtle">© {new Date().getFullYear()} ResolveAI · Support that keeps its promises</p>
      </div>

      <div className="relative hidden flex-1 items-center border-l border-border lg:flex">
        <div className="mx-auto w-full max-w-[560px] px-12">
          <Eyebrow>AI-assisted support desk</Eyebrow>
          <h2 className="mt-5 text-[44px] font-semibold leading-[1.05] tracking-[-0.035em] text-text">
            Every ticket has a clock.
            <span className="block text-text-subtle">Nothing slips through.</span>
          </h2>
          <ul className="mt-6 space-y-2.5">
            {VALUE_PROPS.map((v) => (
              <li key={v} className="flex items-start gap-2.5 text-[13.5px] leading-relaxed text-text-muted">
                <Check className="mt-1 size-3.5 shrink-0 text-primary" aria-hidden />
                {v}
              </li>
            ))}
          </ul>
          <div className="mt-12">
            <SlaPreview />
          </div>
        </div>
      </div>
    </div>
  )
}
