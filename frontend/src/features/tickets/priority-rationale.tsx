import * as Popover from '@radix-ui/react-popover'
import { Info } from 'lucide-react'
import * as React from 'react'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'
import { usePriorityRationale } from './api'

function InputRow({ label, value }: { label: string; value: unknown }) {
  return (
    <div className="flex items-center justify-between gap-3 py-1 text-xs">
      <span className="text-muted-foreground">{label}</span>
      <span className="font-medium text-foreground">{String(value)}</span>
    </div>
  )
}

/**
 * Doc 06 component #12 — why this ticket has this priority. Splits {@code fromModel} vs
 * {@code fromSystem} inputs (the model's reading of the ticket vs. plan tier / reopen
 * count / linked incident) and shows every rule the policy evaluated, matched or not —
 * see {@code RuleTrace}'s own doc on why the near-misses matter as much as the hits.
 */
export function PriorityRationale({ ticketId }: { ticketId: number }) {
  const [open, setOpen] = React.useState(false)
  const { data, isLoading, isError } = usePriorityRationale(ticketId, { enabled: open })

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <button
          type="button"
          className="text-muted-foreground hover:text-foreground"
          aria-label="Why this priority"
        >
          <Info className="size-3.5" aria-hidden />
        </button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          side="bottom"
          align="start"
          sideOffset={6}
          className="z-50 w-80 rounded-lg border border-border bg-card p-3.5 shadow-popover animate-fade-in"
        >
          {isLoading ? (
            <div className="space-y-2">
              <Skeleton className="h-3.5 w-2/3" />
              <Skeleton className="h-16 w-full" />
              <Skeleton className="h-16 w-full" />
            </div>
          ) : isError || !data ? (
            <p className="text-xs text-muted-foreground">Couldn&apos;t load the rationale.</p>
          ) : (
            <>
              <p className="text-xs leading-relaxed text-foreground">{data.humanReadable}</p>

              {data.inputs && (
                <div className="mt-3 grid grid-cols-2 gap-3">
                  <div>
                    <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                      From the model
                    </p>
                    <div className="divide-y divide-border">
                      {Object.entries(data.inputs.fromModel).map(([k, v]) => (
                        <InputRow key={k} label={k} value={v} />
                      ))}
                    </div>
                  </div>
                  <div>
                    <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                      From the system
                    </p>
                    <div className="divide-y divide-border">
                      {Object.entries(data.inputs.fromSystem).map(([k, v]) => (
                        <InputRow key={k} label={k} value={v ?? '—'} />
                      ))}
                    </div>
                  </div>
                </div>
              )}

              <p className="mb-1 mt-3 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                Rules evaluated
              </p>
              <ul className="space-y-1">
                {data.rules.map((r) => (
                  <li
                    key={r.rule}
                    className={cn(
                      'rounded-md px-2 py-1.5 text-xs',
                      r.matched ? 'bg-secondary text-foreground' : 'bg-muted text-muted-foreground',
                    )}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-medium">{r.rule}</span>
                      <span className={cn('font-mono text-xs', r.matched ? 'text-primary' : 'text-muted-foreground')}>
                        {r.matched ? r.effect : 'not matched'}
                      </span>
                    </div>
                    <p className="mt-0.5 text-muted-foreground">{r.note}</p>
                  </li>
                ))}
              </ul>

              <p className="mt-3 text-xs text-muted-foreground">
                Policy {data.policyVersion} · {data.overridable ? 'Can still be overridden' : 'Locked — ticket is closed'}
              </p>
            </>
          )}
          <Popover.Arrow className="fill-card" />
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
