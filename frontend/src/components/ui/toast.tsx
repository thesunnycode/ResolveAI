import * as ToastPrimitive from '@radix-ui/react-toast'
import { CheckCircle2, Info, X, XCircle } from 'lucide-react'
import * as React from 'react'
import { cn } from '@/lib/utils'

type ToastKind = 'success' | 'info' | 'error'
interface ToastItem {
  id: number
  kind: ToastKind
  message: string
}

const ToastContext = React.createContext<{
  push: (kind: ToastKind, message: string) => void
} | null>(null)

const ICONS: Record<ToastKind, React.ReactNode> = {
  success: <CheckCircle2 className="size-4 text-success" aria-hidden />,
  info: <Info className="size-4 text-primary" aria-hidden />,
  error: <XCircle className="size-4 text-danger" aria-hidden />,
}

let idSeq = 0

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [items, setItems] = React.useState<ToastItem[]>([])

  const push = React.useCallback((kind: ToastKind, message: string) => {
    const id = ++idSeq
    setItems((prev) => [...prev, { id, kind, message }])
  }, [])

  const remove = (id: number) => setItems((prev) => prev.filter((t) => t.id !== id))

  return (
    <ToastContext.Provider value={{ push }}>
      <ToastPrimitive.Provider swipeDirection="right" duration={4000}>
        {children}
        {items.map((item) => (
          <ToastPrimitive.Root
            key={item.id}
            onOpenChange={(open) => !open && remove(item.id)}
            className={cn(
              'flex items-start gap-2.5 rounded-xl border border-border bg-surface px-4 py-3 shadow-popover',
              'data-[state=open]:animate-slide-up data-[state=closed]:animate-fade-in',
            )}
          >
            {ICONS[item.kind]}
            <ToastPrimitive.Description className="flex-1 text-[13px] text-text">
              {item.message}
            </ToastPrimitive.Description>
            <ToastPrimitive.Close aria-label="Dismiss" className="text-text-subtle hover:text-text">
              <X className="size-3.5" aria-hidden />
            </ToastPrimitive.Close>
          </ToastPrimitive.Root>
        ))}
        <ToastPrimitive.Viewport className="fixed bottom-4 right-4 z-[100] flex w-96 max-w-[calc(100vw-2rem)] flex-col gap-2 outline-none" />
      </ToastPrimitive.Provider>
    </ToastContext.Provider>
  )
}

export function useToast() {
  const ctx = React.useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within ToastProvider')
  return ctx
}
