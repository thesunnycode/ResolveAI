import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import { Loader2 } from 'lucide-react'
import * as React from 'react'
import { cn } from '@/lib/utils'

// Doc 06 §5 "Component states — Button". Widths are never allowed to change
// between default and loading: a button that shrinks when its label becomes
// a spinner moves the layout, and the pointer that was over the button ends
// up over whatever was behind it.
const buttonVariants = cva(
  'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-md text-base font-medium tracking-[-0.005em] transition-all duration-150 disabled:pointer-events-none disabled:opacity-40 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary active:scale-[0.98]',
  {
    variants: {
      variant: {
        // Krea's primary: the inverse of the canvas (white on dark, black on
        // light). The accent never fills a button - it would read as a second
        // brand color competing with priority and SLA signals.
        primary: 'bg-action text-action-fg hover:bg-action-hover',
        secondary:
          'glass text-text hover:bg-surface-2 hover:border-border-strong',
        danger: 'bg-danger-bg text-danger border border-danger/25 hover:bg-danger/15',
        ghost: 'bg-transparent text-text-muted hover:bg-surface-2 hover:text-text',
        link: 'bg-transparent text-primary hover:text-primary-hover underline-offset-4 hover:underline p-0 h-auto active:scale-100',
      },
      size: {
        sm: 'h-8 px-3 text-sm',
        md: 'h-9 px-4',
        lg: 'h-11 px-5 text-base',
        icon: 'h-9 w-9',
      },
    },
    defaultVariants: { variant: 'primary', size: 'md' },
  },
)

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean
  loading?: boolean
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild, loading, disabled, children, ...props }, ref) => {
    const Comp = asChild ? Slot : 'button'
    return (
      <Comp
        ref={ref}
        className={cn(buttonVariants({ variant, size }), className)}
        disabled={disabled || loading}
        aria-busy={loading || undefined}
        {...props}
      >
        {loading ? (
          // Grid-overlay technique: both cells occupy the same track, so the
          // invisible label still sets the button's width while the spinner
          // renders on top of it. That is what "width locked" means here.
          <span className="grid *:col-start-1 *:row-start-1 grid-cols-1 place-items-center">
            <Loader2 className="size-3.5 animate-spin" aria-hidden />
            <span className="invisible flex items-center gap-1.5">{children}</span>
          </span>
        ) : (
          children
        )}
      </Comp>
    )
  },
)
Button.displayName = 'Button'
