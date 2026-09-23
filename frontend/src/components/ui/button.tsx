import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import { Loader2 } from 'lucide-react'
import * as React from 'react'
import { cn } from '@/lib/utils'

// Doc 06 §5 "Component states — Button". Widths are never allowed to change
// between default and loading: a button that shrinks when its label becomes
// a spinner moves the layout, and the pointer that was over the button ends
// up over whatever was behind it.
//
// Variant/size names kept from the pre-reskin API (primary/secondary/danger/
// ghost/link, sm/md/lg/icon) so existing call sites don't need to change;
// only the visual treatment underneath is Lovable's.
const buttonVariants = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-xl text-sm font-semibold cursor-pointer transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50 disabled:cursor-not-allowed active:scale-[0.98] [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        primary: 'bg-primary text-primary-foreground hover:bg-primary/90',
        secondary:
          'border border-border bg-card text-foreground hover:bg-accent hover:text-accent-foreground',
        danger: 'bg-destructive text-destructive-foreground shadow-sm hover:bg-destructive/90',
        ghost: 'bg-transparent text-muted-foreground hover:bg-accent hover:text-accent-foreground',
        link: 'bg-transparent text-primary underline-offset-4 hover:underline p-0 h-auto active:scale-100',
      },
      size: {
        sm: 'h-9 rounded-lg px-3.5 text-[13px]',
        md: 'h-11 px-5',
        lg: 'h-12 rounded-xl px-7 text-base',
        icon: 'h-10 w-10',
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

export { buttonVariants }
