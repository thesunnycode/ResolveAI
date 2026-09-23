import * as Tabs from '@radix-ui/react-tabs'
import * as Switch from '@radix-ui/react-switch'
import * as React from 'react'
import { Page, PageHeader } from '@/components/layout/page-header'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ErrorBanner } from '@/components/ui/error-banner'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/field-error'
import { Skeleton, SkeletonRow } from '@/components/ui/skeleton'
import { useToast } from '@/components/ui/toast'
import { ApiError } from '@/lib/api-client'
import type { AdminAiPolicy, AdminCalendar, AdminSlaPolicyRow } from '@/lib/types'
import { cn } from '@/lib/utils'
import { useAiPolicy, useCalendar, useSlaPolicies, useUpdateAiPolicy, useUpdateCalendar, useUpdateSlaPolicy } from './api'

const DAY_LABELS = [
  { iso: 1, label: 'Mon' },
  { iso: 2, label: 'Tue' },
  { iso: 3, label: 'Wed' },
  { iso: 4, label: 'Thu' },
  { iso: 5, label: 'Fri' },
  { iso: 6, label: 'Sat' },
  { iso: 7, label: 'Sun' },
]

function SlaPolicyTab() {
  const { data, isLoading, isError, refetch } = useSlaPolicies()
  return (
    <div className="p-5">
      <p className="mb-5 max-w-2xl text-[13px] leading-relaxed text-text-muted">
        First-response and resolution targets, in business minutes, for your plan&apos;s tickets. Saving
        supersedes the current target — tickets already promised the old one keep it.
      </p>
      {isLoading ? (
        <SkeletonRow count={4} />
      ) : isError ? (
        <ErrorBanner onRetry={() => refetch()} />
      ) : (
        <div className="overflow-hidden rounded-lg border border-border bg-surface-2/30">
          <table className="w-full text-left text-[13px]">
            <thead>
              <tr className="border-b border-border text-[11.5px] uppercase tracking-[0.08em] text-text-subtle">
                <th className="px-4 py-3 font-medium">Priority</th>
                <th className="px-4 py-3 font-medium">First response (min)</th>
                <th className="px-4 py-3 font-medium">Resolution (min)</th>
                <th className="px-4 py-3 font-medium">Status</th>
                <th className="px-4 py-3" />
              </tr>
            </thead>
            <tbody>
              {(data ?? []).map((row) => (
                <SlaPolicyTableRow key={`${row.priority}-${row.versionLabel}`} row={row} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function SlaPolicyTableRow({ row }: { row: AdminSlaPolicyRow }) {
  const { push } = useToast()
  const mutate = useUpdateSlaPolicy()
  const initialFr = row.firstResponseMinutes ?? 60
  const initialRes = row.resolutionMinutes ?? 240
  const [fr, setFr] = React.useState(initialFr)
  const [res, setRes] = React.useState(initialRes)
  const dirty = fr !== initialFr || res !== initialRes

  async function handleSave() {
    try {
      await mutate.mutateAsync({ priority: row.priority, firstResponseMinutes: fr, resolutionMinutes: res })
      push('success', `${row.priority} target saved.`)
    } catch (err) {
      push('error', err instanceof ApiError ? err.problem.detail : `Could not save ${row.priority}.`)
    }
  }

  return (
    <tr className="border-b border-border last:border-0">
      <td className="px-4 py-3 font-medium text-text">
        <span className="flex items-center gap-2">
          <span className="size-2 rounded-full" style={{ background: `var(--color-${row.priority.toLowerCase()})` }} />
          {row.priority}
        </span>
      </td>
      <td className="px-4 py-3">
        <Input
          type="number"
          min={1}
          className="h-8 w-24 text-[13px] tabular-nums"
          value={fr}
          onChange={(e) => setFr(Number(e.target.value))}
          aria-label={`${row.priority} first response minutes`}
        />
      </td>
      <td className="px-4 py-3">
        <Input
          type="number"
          min={1}
          className="h-8 w-24 text-[13px] tabular-nums"
          value={res}
          onChange={(e) => setRes(Number(e.target.value))}
          aria-label={`${row.priority} resolution minutes`}
        />
      </td>
      <td className="px-4 py-3">
        {row.configured ? (
          <span className="text-text-subtle">{row.versionLabel}</span>
        ) : (
          <Badge variant="warning">Not configured</Badge>
        )}
      </td>
      <td className="px-4 py-3 text-right">
        <Button size="sm" variant="secondary" loading={mutate.isPending} disabled={!dirty} onClick={handleSave}>
          Save
        </Button>
      </td>
    </tr>
  )
}

function CalendarTab() {
  const { data, isLoading, isError, refetch } = useCalendar()
  if (isLoading) {
    return (
      <div className="max-w-md space-y-4 p-5">
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-16 w-full" />
      </div>
    )
  }
  if (isError || !data) {
    return (
      <div className="p-5">
        <ErrorBanner onRetry={() => refetch()} />
      </div>
    )
  }
  return <CalendarForm calendar={data} />
}

function CalendarForm({ calendar }: { calendar: AdminCalendar }) {
  const { push } = useToast()
  const mutate = useUpdateCalendar()
  const [timezone, setTimezone] = React.useState(calendar.timezone)
  const [days, setDays] = React.useState<Set<number>>(new Set(calendar.workingDays))
  const [dayStart, setDayStart] = React.useState(calendar.dayStart.slice(0, 5))
  const [dayEnd, setDayEnd] = React.useState(calendar.dayEnd.slice(0, 5))

  async function handleSave() {
    if (days.size === 0) {
      push('error', 'At least one working day is required.')
      return
    }
    try {
      await mutate.mutateAsync({
        timezone,
        workingDays: [...days].sort((a, b) => a - b),
        dayStart,
        dayEnd,
      })
      push('success', 'Calendar saved.')
    } catch (err) {
      push('error', err instanceof ApiError ? err.problem.detail : 'Could not save the calendar.')
    }
  }

  return (
    <div className="max-w-md space-y-4 p-5">
      <div>
        <Label>Timezone</Label>
        <Input value={timezone} onChange={(e) => setTimezone(e.target.value)} placeholder="Asia/Kolkata" />
      </div>
      <div>
        <Label>Working days</Label>
        <div className="mt-1.5 flex gap-1.5">
          {DAY_LABELS.map((d) => (
            <button
              key={d.iso}
              type="button"
              onClick={() =>
                setDays((prev) => {
                  const next = new Set(prev)
                  next.has(d.iso) ? next.delete(d.iso) : next.add(d.iso)
                  return next
                })
              }
              className={cn(
                'flex h-9 w-11 items-center justify-center rounded-md border text-[12px] font-medium transition-colors',
                days.has(d.iso)
                  ? 'border-text/40 bg-surface-2 text-text'
                  : 'border-border text-text-muted',
              )}
            >
              {d.label}
            </button>
          ))}
        </div>
      </div>
      <div className="flex gap-3">
        <div>
          <Label>Day start</Label>
          <Input type="time" value={dayStart} onChange={(e) => setDayStart(e.target.value)} />
        </div>
        <div>
          <Label>Day end</Label>
          <Input type="time" value={dayEnd} onChange={(e) => setDayEnd(e.target.value)} />
        </div>
      </div>
      <Button size="sm" loading={mutate.isPending} onClick={handleSave}>
        Save calendar
      </Button>
    </div>
  )
}

function AiPolicyTab() {
  const { data, isLoading, isError, refetch } = useAiPolicy()
  if (isLoading) {
    return (
      <div className="max-w-md space-y-4 p-5">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-20 w-full" />
      </div>
    )
  }
  if (isError || !data) {
    return (
      <div className="p-5">
        <ErrorBanner onRetry={() => refetch()} />
      </div>
    )
  }
  return <AiPolicyForm policy={data} />
}

function AiPolicyForm({ policy }: { policy: AdminAiPolicy }) {
  const { push } = useToast()
  const mutate = useUpdateAiPolicy()
  const [externalAllowed, setExternalAllowed] = React.useState(policy.externalModelAllowed)
  const [piiRedaction, setPiiRedaction] = React.useState(policy.piiRedactionRequired)
  const [budget, setBudget] = React.useState(Math.round(policy.monthlyBudgetMicros / 1_000_000))
  const [retentionDays, setRetentionDays] = React.useState(policy.retentionDays)

  const spent = policy.currentMonthSpendMicros / 1_000_000
  const budgetTotal = policy.monthlyBudgetMicros / 1_000_000
  const pct = budgetTotal > 0 ? Math.min(100, Math.round((spent / budgetTotal) * 100)) : 0

  async function handleSave() {
    try {
      await mutate.mutateAsync({
        externalModelAllowed: externalAllowed,
        allowedProviders: policy.allowedProviders,
        piiRedactionRequired: piiRedaction,
        monthlyBudgetMicros: budget * 1_000_000,
        retentionDays,
      })
      push('success', 'AI policy saved.')
    } catch (err) {
      push('error', err instanceof ApiError ? err.problem.detail : 'Could not save the AI policy.')
    }
  }

  return (
    <div className="max-w-md space-y-5 p-5">
      <div className="flex items-center justify-between gap-4">
        <div>
          <p className="text-[14px] font-medium text-text">Allow external models</p>
          {!externalAllowed && (
            <p className="mt-0.5 text-[12px] text-warning">
              Tickets will be processed by the local model only. Triage accuracy may be lower.
            </p>
          )}
        </div>
        <Switch.Root
          checked={externalAllowed}
          onCheckedChange={setExternalAllowed}
          className={cn(
            'relative h-5 w-9 shrink-0 rounded-full',
            externalAllowed ? 'bg-primary' : 'bg-surface-2 border border-border',
          )}
        >
          <Switch.Thumb className="block size-4 translate-x-0.5 rounded-full bg-surface shadow transition-transform data-[state=checked]:translate-x-[18px]" />
        </Switch.Root>
      </div>

      <div className="flex items-center justify-between gap-4">
        <div>
          <p className="text-[14px] font-medium text-text">Redact PII before sending to external models</p>
          <p className="mt-0.5 text-[12px] text-text-subtle">Turning this off does not undo redaction already applied.</p>
        </div>
        <Switch.Root
          checked={piiRedaction}
          onCheckedChange={setPiiRedaction}
          className={cn(
            'relative h-5 w-9 shrink-0 rounded-full',
            piiRedaction ? 'bg-primary' : 'bg-surface-2 border border-border',
          )}
        >
          <Switch.Thumb className="block size-4 translate-x-0.5 rounded-full bg-surface shadow transition-transform data-[state=checked]:translate-x-[18px]" />
        </Switch.Root>
      </div>

      <div>
        <Label>Monthly budget (₹)</Label>
        <Input type="number" min={0} value={budget} onChange={(e) => setBudget(Number(e.target.value))} />
        <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-surface-2">
          <div className="h-full rounded-full bg-primary" style={{ width: `${pct}%` }} />
        </div>
        <p className="mt-1 text-[12px] text-text-subtle">
          ₹{spent.toLocaleString()} spent of ₹{budgetTotal.toLocaleString()} this month
        </p>
      </div>

      <div>
        <Label>PII retention (days)</Label>
        <Input
          type="number"
          min={1}
          value={retentionDays}
          onChange={(e) => setRetentionDays(Number(e.target.value))}
          className="w-32"
        />
      </div>

      <Button size="sm" loading={mutate.isPending} onClick={handleSave}>
        Save AI policy
      </Button>
    </div>
  )
}

export function SettingsPage() {
  return (
    <Page>
      <PageHeader title="Settings" description="Service targets, business hours and AI guardrails for this workspace." />
      <Tabs.Root defaultValue="sla" className="glass overflow-hidden rounded-xl">
        <Tabs.List className="flex gap-1 border-b border-border px-3">
          {[
            { v: 'sla', label: 'SLA Policy' },
            { v: 'calendar', label: 'Calendar' },
            { v: 'ai', label: 'AI Policy' },
          ].map((t) => (
            <Tabs.Trigger
              key={t.v}
              value={t.v}
              className="-mb-px border-b-2 border-transparent px-3 py-3 text-[13px] font-medium text-text-muted transition-colors hover:text-text data-[state=active]:border-text data-[state=active]:text-text"
            >
              {t.label}
            </Tabs.Trigger>
          ))}
        </Tabs.List>
        <Tabs.Content value="sla">
          <SlaPolicyTab />
        </Tabs.Content>
        <Tabs.Content value="calendar">
          <CalendarTab />
        </Tabs.Content>
        <Tabs.Content value="ai">
          <AiPolicyTab />
        </Tabs.Content>
      </Tabs.Root>
    </Page>
  )
}
