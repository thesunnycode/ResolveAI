import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, Coins, FlaskConical, Play, XCircle } from 'lucide-react'
import { Page, PageHeader } from '@/components/layout/page-header'
import { useDocumentTitle } from '@/components/layout/route-a11y'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/app/states'
import { Skeleton } from '@/components/ui/skeleton'
import { useToast } from '@/components/ui/toast'
import { ApiError, api } from '@/lib/api-client'
import { track } from '@/lib/analytics'
import { cn } from '@/lib/utils'

interface EvalRun {
  id: number
  suite: string
  modelId: string
  metrics: Record<string, number>
  passed: boolean
  startedAt: string
}

/** GET /admin/ai/usage — month to date. Costs are integer micro-dollars. */
interface AiUsage {
  period: { from: string; to: string }
  budget: { monthlyMicros: number; spentMicros: number; remainingPct: number; status: 'OK' | 'LOW' | 'EXHAUSTED' } | null
  totals: { calls: number; tokensIn: number; tokensOut: number; costMicros: number }
  breakdown: {
    feature: string
    promptVersion: string
    model: string
    calls: number
    costMicros: number
    avgLatencyMs: number
    suppressionRate: number | null
  }[]
}

function humanize(key: string) {
  return key.replace(/([A-Z])/g, ' $1').replace(/_/g, ' ').toLowerCase()
}

function dollars(micros: number) {
  const usd = micros / 1_000_000
  return usd < 0.01 && usd > 0 ? `$${usd.toFixed(4)}` : `$${usd.toFixed(2)}`
}

function useEvalRunner() {
  const qc = useQueryClient()
  const status = useQuery({
    queryKey: ['eval-run-status'],
    queryFn: async () => (await api.get<{ running: boolean }>('/admin/eval/runs/status')).data,
    refetchInterval: (q) => (q.state.data?.running ? 5000 : false),
  })
  const start = useMutation({
    mutationFn: async () => (await api.post<{ detail: string }>('/admin/eval/runs')).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['eval-run-status'] }),
  })
  return { running: !!status.data?.running, start }
}

export function EvalDashboardPage() {
  useDocumentTitle('Evaluation')
  const { push } = useToast()
  const { running, start } = useEvalRunner()
  const runs = useQuery({
    queryKey: ['eval-runs'],
    queryFn: async () => (await api.get<EvalRun[]>('/admin/eval/runs')).data,
    // While a run is in flight, pick its result up as soon as it is written.
    refetchInterval: running ? 5000 : false,
  })
  const usage = useQuery({
    queryKey: ['ai-usage'],
    queryFn: async () => (await api.get<AiUsage>('/admin/ai/usage')).data,
    retry: false,
  })

  async function runSuite() {
    try {
      const res = await start.mutateAsync()
      track('eval_run_started')
      push('success', res.detail)
    } catch (err) {
      push('info', err instanceof ApiError ? err.problem.detail : 'Could not start the evaluation run.')
    }
  }

  const bySuite = new Map<string, EvalRun[]>()
  for (const run of runs.data ?? []) {
    if (!bySuite.has(run.suite)) bySuite.set(run.suite, [])
    bySuite.get(run.suite)!.push(run)
  }

  const runButton = (
    <Button size="sm" variant="secondary" loading={start.isPending || running} onClick={runSuite}>
      <Play className="size-3.5" aria-hidden /> {running ? 'Running…' : 'Run classification suite'}
    </Button>
  )

  const u = usage.data

  return (
    <Page>
      <PageHeader
        title="Evaluation"
        description="How accurate each prompt version is against the labelled test suites, and what it costs to run."
        actions={bySuite.size > 0 ? runButton : undefined}
      />

      <section className="mb-8">
        <h2 className="mb-3 text-sm font-medium text-muted-foreground">Suites</h2>
        {runs.isLoading ? (
          <div className="grid gap-4 sm:grid-cols-2">
            <Skeleton className="h-40 rounded-xl" />
            <Skeleton className="h-40 rounded-xl" />
          </div>
        ) : bySuite.size === 0 ? (
          <div className="glass rounded-xl">
            <EmptyState
              icon={FlaskConical}
              title={running ? 'Evaluation running…' : 'No evaluation runs yet'}
              body={
                running
                  ? 'Classifying the 60 labelled test tickets. The result appears here in a few minutes.'
                  : 'Run the 60-ticket classification suite against the active triage prompt. It takes a few minutes and costs about ten cents of this workspace’s AI budget.'
              }
            />
            {!running && <div className="-mt-10 flex justify-center pb-10">{runButton}</div>}
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2">
            {[...bySuite.entries()].map(([suite, suiteRuns]) => {
              const latest = suiteRuns[0]
              const [metricName, metricValue] = Object.entries(latest.metrics)[0] ?? ['score', 0]
              return (
                <div key={suite} className="glass rounded-xl p-5">
                  <div className="flex items-start justify-between">
                    <div>
                      <p className="text-sm font-medium text-foreground">{suite}</p>
                      <p className="mt-0.5 text-xs text-muted-foreground">{latest.modelId}</p>
                    </div>
                    {latest.passed ? (
                      <span className="inline-flex items-center gap-1 rounded-md bg-success-bg px-2 py-0.5 text-xs font-medium text-success">
                        <CheckCircle2 className="size-3" aria-hidden /> Passing
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 rounded-md bg-danger-bg px-2 py-0.5 text-xs font-medium text-danger">
                        <XCircle className="size-3" aria-hidden /> Failing
                      </span>
                    )}
                  </div>
                  <p className="mt-4 text-2xl font-semibold tabular-nums tracking-[-0.03em] text-foreground">
                    {(metricValue * 100).toFixed(1)}%
                  </p>
                  <p className="text-xs capitalize text-muted-foreground">{humanize(metricName)}</p>
                  {/* Audit N6: a y-axis, gridlines and dated ends, so the trend can be read. */}
                  {(() => {
                    const shown = suiteRuns.slice(0, 12).reverse()
                    const day = (iso: string) => new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
                    return (
                      <figure className="mt-4">
                        <div className="flex gap-2">
                          <div className="flex h-16 flex-col justify-between text-right text-xs tabular-nums text-muted-foreground" aria-hidden>
                            <span>100%</span>
                            <span>50%</span>
                            <span>0%</span>
                          </div>
                          <div
                            className="relative flex h-16 flex-1 items-end gap-1 border-b border-l border-border-strong"
                            role="img"
                            aria-label={`Last ${shown.length} runs: ${shown
                              .map((r) => `${((Object.values(r.metrics)[0] ?? 0) * 100).toFixed(0)}% ${r.passed ? 'pass' : 'fail'}`)
                              .join(', ')}`}
                          >
                            <div className="pointer-events-none absolute inset-x-0 top-0 border-t border-dashed border-border" aria-hidden />
                            <div className="pointer-events-none absolute inset-x-0 top-1/2 border-t border-dashed border-border" aria-hidden />
                            {shown.map((r) => {
                              const value = Object.values(r.metrics)[0] ?? 0
                              return (
                                <div
                                  key={r.id}
                                  title={`${day(r.startedAt)} · ${(value * 100).toFixed(1)}% · ${r.passed ? 'passed' : 'failed'}`}
                                  className={cn('relative flex-1 rounded-t-sm', r.passed ? 'bg-primary/70' : 'bg-danger/70')}
                                  style={{ height: `${Math.max(4, value * 100)}%` }}
                                />
                              )
                            })}
                          </div>
                        </div>
                        <figcaption className="mt-1 flex justify-between pl-10 text-xs text-muted-foreground">
                          <span>{day(shown[0].startedAt)}</span>
                          <span className="flex items-center gap-2">
                            <span className="inline-flex items-center gap-1">
                              <span className="size-2 rounded-sm bg-primary/70" aria-hidden /> passed
                            </span>
                            <span className="inline-flex items-center gap-1">
                              <span className="size-2 rounded-sm bg-danger/70" aria-hidden /> failed
                            </span>
                          </span>
                          <span>{day(shown[shown.length - 1].startedAt)}</span>
                        </figcaption>
                      </figure>
                    )
                  })()}
                </div>
              )
            })}
          </div>
        )}
      </section>

      <section>
        <h2 className="mb-3 text-sm font-medium text-muted-foreground">
          Token spend {u && <span className="font-normal text-muted-foreground">· {u.period.from} to {u.period.to}</span>}
        </h2>
        {usage.isLoading ? (
          <Skeleton className="h-28 rounded-xl" />
        ) : !u || u.totals.calls === 0 ? (
          <div className="glass rounded-xl">
            <EmptyState
              icon={Coins}
              title="No AI calls this month yet"
              body="Every triage and every draft records its tokens and cost. They add up here, against the workspace’s monthly budget."
            />
          </div>
        ) : (
          <>
            <div className="mb-3 grid gap-3 sm:grid-cols-3">
              <div className="glass rounded-xl px-4 py-3.5">
                <p className="text-xs text-muted-foreground">Spent this month</p>
                <p className="mt-1 text-xl font-semibold tabular-nums text-foreground">{dollars(u.totals.costMicros)}</p>
                {u.budget && (
                  <p className={cn('text-xs', u.budget.status === 'OK' ? 'text-muted-foreground' : 'text-warning')}>
                    of {dollars(u.budget.monthlyMicros)} budget · {u.budget.remainingPct}% left
                  </p>
                )}
              </div>
              <div className="glass rounded-xl px-4 py-3.5">
                <p className="text-xs text-muted-foreground">Model calls</p>
                <p className="mt-1 text-xl font-semibold tabular-nums text-foreground">{u.totals.calls.toLocaleString()}</p>
              </div>
              <div className="glass rounded-xl px-4 py-3.5">
                <p className="text-xs text-muted-foreground">Tokens in / out</p>
                <p className="mt-1 text-xl font-semibold tabular-nums text-foreground">
                  {u.totals.tokensIn.toLocaleString()} / {u.totals.tokensOut.toLocaleString()}
                </p>
              </div>
            </div>
            <div className="glass overflow-x-auto rounded-xl">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-border text-xs uppercase tracking-[0.08em] text-muted-foreground">
                    <th className="px-4 py-2.5 font-medium">Feature</th>
                    <th className="px-4 py-2.5 font-medium">Prompt · model</th>
                    <th className="px-4 py-2.5 text-right font-medium">Calls</th>
                    <th className="px-4 py-2.5 text-right font-medium">Cost</th>
                    <th className="px-4 py-2.5 text-right font-medium">Avg latency</th>
                    <th className="px-4 py-2.5 text-right font-medium">Suppressed</th>
                  </tr>
                </thead>
                <tbody>
                  {u.breakdown.map((r) => (
                    <tr key={`${r.feature}-${r.promptVersion}-${r.model}`} className="border-b border-border last:border-0">
                      <td className="px-4 py-2.5 text-foreground">{r.feature}</td>
                      <td className="px-4 py-2.5 text-muted-foreground">
                        {r.promptVersion} · {r.model}
                      </td>
                      <td className="px-4 py-2.5 text-right tabular-nums">{r.calls}</td>
                      <td className="px-4 py-2.5 text-right tabular-nums">{dollars(r.costMicros)}</td>
                      <td className="px-4 py-2.5 text-right tabular-nums">{(r.avgLatencyMs / 1000).toFixed(1)}s</td>
                      <td className="px-4 py-2.5 text-right tabular-nums text-muted-foreground">
                        {r.suppressionRate == null ? '—' : `${Math.round(r.suppressionRate * 100)}%`}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </section>
    </Page>
  )
}
