import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";

export function Field({
  id,
  label,
  hint,
  error,
  children,
  className,
  optional,
}: {
  id: string;
  label: string;
  hint?: React.ReactNode | undefined;
  error?: string | undefined;
  children: React.ReactNode;
  className?: string | undefined;
  optional?: boolean | undefined;
}) {
  return (
    <div className={cn("space-y-1.5", className)}>
      <Label htmlFor={id} className="text-sm font-semibold">
        {label}
        {optional && <span className="ml-1 font-normal text-muted-foreground">(optional)</span>}
      </Label>
      {children}
      {error ? (
        <p id={`${id}-error`} className="text-xs text-danger">
          {error}
        </p>
      ) : hint ? (
        <p className="text-xs text-muted-foreground">{hint}</p>
      ) : null}
    </div>
  );
}

export function PageHeader({ title, description, actions }: { title: string; description?: React.ReactNode | undefined; actions?: React.ReactNode | undefined }) {
  return (
    <div className="mb-8 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-[32px] font-semibold leading-[40px]">{title}</h1>
        {description && <div className="mt-1 text-[17px] text-muted-foreground">{description}</div>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}

export function Card({ className, children }: { className?: string | undefined; children: React.ReactNode }) {
  return <section className={cn("overflow-hidden rounded-2xl border bg-card", className)}>{children}</section>;
}

export function CardHeader({ title, count, actions }: { title: string; count?: number | undefined; actions?: React.ReactNode | undefined }) {
  return (
    <div className="flex min-h-[60px] items-center gap-2.5 border-b px-6 py-3">
      <h2 className="text-lg font-semibold">{title}</h2>
      {count !== undefined && <span className="rounded-full bg-muted px-2 font-mono text-xs leading-[18px] text-muted-foreground">{count}</span>}
      {actions && <div className="ml-auto flex items-center gap-2">{actions}</div>}
    </div>
  );
}
