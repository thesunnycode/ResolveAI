import { useQuery } from '@tanstack/react-query'
import { CheckCircle2, Coins, FlaskConical, XCircle } from 'lucide-react'
import { Page, PageHeader } from '@/components/layout/page-header'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { api } from '@/lib/api-client'
import { cn } from '@/lib/utils'

interface EvalRun {
  id: number
  suite: string
  modelId: string
  metrics: Record<string, number>
  passed: boolean
  startedAt: string
}

function humanize(key: string) {
  return key.replace(/([A-Z])/g, ' $1').replace(/_/g, ' ').toLowerCase()
}

export function EvalDashboardPage() {
  const runs = useQuery({
    queryKey: ['eval-runs'],
    queryFn: async () => (await api.get<EvalRun[]>('/admin/eval/runs')).data,
  })
  const usage = useQuery({
    queryKey: ['ai-usage'],
    queryFn: async () => (await api.get<Record<string, unknown>>('/admin/ai/usage')).data,
    retry: false,
  })

  const bySuite = new Map<string, EvalRun[]>()
  for (const run of runs.data ?? []) {
    if (!bySuite.has(run.suite)) bySuite.set(run.suite, [])
    bySuite.get(run.suite)!.push(run)
  }

  const usageEntries = usage.data
    ? Object.entries(usage.data).filter(([, v]) => typeof v === 'number' || typeof v === 'string')
    : []

  return (
    <Page>
      <PageHeader
        title="Evaluation"
        description="How accurate each prompt version is against the labelled test suites, and what it costs to run."
      />

      <section className="mb-8">
        <h2 className="mb-3 text-[13px] font-medium text-text-muted">Suites</h2>
        {runs.isLoading ? (
          <div className="grid gap-4 sm:grid-cols-2">
            <Skeleton className="h-40 rounded-xl" />
            <Skeleton className="h-40 rounded-xl" />
          </div>
        ) : bySuite.size === 0 ? (
          <div className="glass rounded-xl">
            <EmptyState
              icon={FlaskConical}
              title="No evaluation runs yet"
              description="Run the classification suite from the backend and each result will appear here with its trend across prompt versions."
            />
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
                      <p className="text-[13px] font-medium text-text">{suite}</p>
                      <p className="mt-0.5 text-[12px] text-text-subtle">{latest.modelId}</p>
                    </div>
                    {latest.passed ? (
                      <span className="inline-flex items-center gap-1 rounded-md bg-success-bg px-2 py-0.5 text-[11.5px] font-medium text-success">
                        <CheckCircle2 className="size-3" aria-hidden /> Passing
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 rounded-md bg-danger-bg px-2 py-0.5 text-[11.5px] font-medium text-danger">
                        <XCircle className="size-3" aria-hidden /> Failing
                      </span>
                    )}
                  </div>
                  <p className="mt-4 text-[30px] font-semibold tabular-nums tracking-[-0.03em] text-text">
                    {(metricValue * 100).toFixed(1)}%
                  </p>
                  <p className="text-[12px] capitalize text-text-subtle">{humanize(metricName)}</p>
                  <div className="mt-4 flex h-12 items-end gap-1">
                    {suiteRuns
                      .slice(0, 12)
                      .reverse()
                      .map((r) => {
                        const value = Object.values(r.metrics)[0] ?? 0
                        return (
                          <div
                            key={r.id}
                            title={`${(value * 100).toFixed(0)}%`}
                            className={cn('flex-1 rounded-sm', r.passed ? 'bg-primary/70' : 'bg-danger/70')}
                            style={{ height: `${Math.max(8, value * 100)}%` }}
                          />
                        )
                      })}
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </section>

      <section>
        <h2 className="mb-3 text-[13px] font-medium text-text-muted">Token spend</h2>
        {usage.isLoading ? (
          <Skeleton className="h-28 rounded-xl" />
        ) : usageEntries.length === 0 ? (
          <div className="glass rounded-xl">
            <EmptyState
              icon={Coins}
              title="No usage reported yet"
              description="Model calls, tokens and cost per feature show up here once the usage endpoint is reporting."
            />
          </div>
        ) : (
          <div className="grid gap-3 sm:grid-cols-3">
            {usageEntries.map(([k, v]) => (
              <div key={k} className="glass rounded-xl px-4 py-3.5">
                <p className="text-[12px] capitalize text-text-muted">{humanize(k)}</p>
                <p className="mt-1 text-[20px] font-semibold tabular-nums text-text">{String(v)}</p>
              </div>
            ))}
          </div>
        )}
      </section>
    </Page>
  )
}
