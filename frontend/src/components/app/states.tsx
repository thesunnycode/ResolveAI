import { Link } from "react-router-dom";
import { AlertCircle, Lock, RotateCw, type LucideIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

export function EmptyState({
  icon: Icon,
  title,
  body,
  action,
  tone = "neutral",
}: {
  icon: LucideIcon;
  title: string;
  body: string;
  action?: React.ReactNode;
  tone?: "neutral" | "good";
}) {
  return (
    <div className="flex flex-col items-center px-6 py-14 text-center">
      <span className={cn("mb-4 grid size-10 place-items-center rounded-lg", tone === "good" ? "bg-success-soft text-success" : "bg-muted text-muted-foreground")}>
        <Icon className="size-5" aria-hidden />
      </span>
      <h3 className="text-sm font-semibold">{title}</h3>
      <p className="mt-1 max-w-sm text-muted-foreground">{body}</p>
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

/** Full error when nothing is loaded yet. */
export function ErrorState({ error, onRetry, retrying }: { error: unknown; onRetry: () => void; retrying?: boolean }) {
  return (
    <div className="flex flex-col items-center px-6 py-14 text-center">
      <span className="mb-4 grid size-10 place-items-center rounded-lg bg-danger-soft text-danger">
        <AlertCircle className="size-5" aria-hidden />
      </span>
      <h3 className="text-sm font-semibold">This didn't load</h3>
      <p className="mt-1 max-w-sm text-muted-foreground">{error instanceof Error ? error.message : "Something went wrong."}</p>
      <Button variant="secondary" size="sm" className="mt-5" onClick={onRetry} disabled={retrying}>
        <RotateCw className={cn("size-3.5", retrying && "animate-spin")} /> Try again
      </Button>
    </div>
  );
}

/** Inline banner when a refresh failed but older data is still shown. */
export function StaleBanner({ onRetry, retrying }: { onRetry: () => void; retrying?: boolean }) {
  return (
    <div role="status" className="flex items-center gap-3 border-b border-danger-border bg-danger-soft px-4 py-2 text-danger">
      <AlertCircle className="size-4 shrink-0" aria-hidden />
      <span className="flex-1">Couldn't refresh. You're seeing the last loaded data.</span>
      <Button variant="secondary" size="sm" className="h-7" onClick={onRetry} disabled={retrying}>
        <RotateCw className={cn("size-3", retrying && "animate-spin")} /> Retry
      </Button>
    </div>
  );
}

export function FormBanner({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <div role="alert" className="flex items-start gap-2 rounded-md border border-danger-border bg-danger-soft px-3 py-2.5 text-danger">
      <AlertCircle className="mt-0.5 size-4 shrink-0" aria-hidden />
      <span>{message}</span>
    </div>
  );
}

export function RowsSkeleton({ rows = 6, cols = [40, 280, 120, 90] }: { rows?: number; cols?: number[] }) {
  return (
    <div aria-busy="true" aria-label="Loading">
      {Array.from({ length: rows }).map((_, r) => (
        <div key={r} className="flex items-center gap-6 border-b border-border px-4 py-3.5 last:border-0">
          {cols.map((w, c) => (
            <div key={c} className={cn("flex flex-col gap-1.5", c === 1 && "flex-1")}>
              <Skeleton className="h-3" style={{ width: c === 1 ? `${60 + ((r * 13) % 30)}%` : w }} />
              {c === 1 && <Skeleton className="h-2.5 w-32" />}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

export function NotAllowed({ home }: { home: string }) {
  return (
    <div className="mx-auto mt-16 max-w-md rounded-lg border bg-card p-8 text-center shadow-sm">
      <span className="mx-auto mb-4 grid size-10 place-items-center rounded-lg bg-muted text-muted-foreground">
        <Lock className="size-5" aria-hidden />
      </span>
      <h1 className="text-base font-semibold">Not available for your role</h1>
      <p className="mt-1 text-muted-foreground">
        Your account doesn't have access to this page. If you think it should, ask a workspace admin.
      </p>
      <Button asChild className="mt-5" size="sm">
        <Link to={home}>Back to my home</Link>
      </Button>
    </div>
  );
}
