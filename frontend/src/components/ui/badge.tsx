import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

const badgeVariants = cva(
  'inline-flex items-center gap-1 rounded-sm px-2 py-0.5 text-xs font-medium leading-none',
  {
    variants: {
      variant: {
        neutral: 'bg-surface-2 text-text-muted',
        primary: 'bg-primary-bg text-primary',
        success: 'bg-success-bg text-success',
        warning: 'bg-warning-bg text-warning',
        danger: 'bg-danger-bg text-danger',
        outline: 'border border-border text-text-muted bg-transparent',
      },
    },
    defaultVariants: { variant: 'neutral' },
  },
)

export interface BadgeProps
  extends React.HTMLAttributes<HTMLSpanElement>,
    VariantProps<typeof badgeVariants> {}

export function Badge({ className, variant, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ variant }), className)} {...props} />
}
