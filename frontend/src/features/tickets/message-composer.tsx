import { Send } from 'lucide-react'
import * as React from 'react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

type Visibility = 'PUBLIC' | 'INTERNAL'

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)

function draftKey(ticketId: number, tab: Visibility) {
  return `resolveai.draft.${ticketId}.${tab}`
}
function readDraft(ticketId: number, tab: Visibility) {
  try {
    return sessionStorage.getItem(draftKey(ticketId, tab)) ?? ''
  } catch {
    return ''
  }
}

export function MessageComposer({
  ticketId,
  allowInternal,
  prefill,
  focusSignal,
  onSend,
  className,
}: {
  ticketId: number
  allowInternal: boolean
  prefill?: string
  /** Bump to move focus into the reply box (e.g. "Reply manually" on a suppressed draft). */
  focusSignal?: number
  onSend: (body: string, visibility: Visibility) => Promise<void>
  className?: string
}) {
  const textareaRef = React.useRef<HTMLTextAreaElement>(null)
  const [tab, setTab] = React.useState<Visibility>('PUBLIC')
  // Audit U13: a half-written reply survives navigation and reloads (per ticket, per tab).
  const [body, setBody] = React.useState(() => prefill ?? readDraft(ticketId, 'PUBLIC'))
  const [saved, setSaved] = React.useState(false)
  const [sending, setSending] = React.useState(false)
  const [failed, setFailed] = React.useState(false)

  React.useEffect(() => {
    if (prefill) {
      setBody(prefill)
      setTab('PUBLIC')
      textareaRef.current?.focus()
    }
  }, [prefill])

  React.useEffect(() => {
    if (!focusSignal) return
    textareaRef.current?.focus()
    textareaRef.current?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [focusSignal])

  // Persist after a short pause, then say so - the reassurance is the point.
  React.useEffect(() => {
    setSaved(false)
    const t = window.setTimeout(() => {
      try {
        if (body.trim()) {
          sessionStorage.setItem(draftKey(ticketId, tab), body)
          setSaved(true)
        } else {
          sessionStorage.removeItem(draftKey(ticketId, tab))
        }
      } catch {
        // Storage full or blocked: the leave guard below still protects the text.
      }
    }, 600)
    return () => window.clearTimeout(t)
  }, [body, tab, ticketId])

  // Closing the tab still loses sessionStorage in some browsers - ask first.
  React.useEffect(() => {
    if (!body.trim()) return
    const guard = (e: BeforeUnloadEvent) => {
      e.preventDefault()
      e.returnValue = ''
    }
    window.addEventListener('beforeunload', guard)
    return () => window.removeEventListener('beforeunload', guard)
  }, [body])

  function switchTab(next: Visibility) {
    if (next === tab) return
    try {
      if (body.trim()) sessionStorage.setItem(draftKey(ticketId, tab), body)
    } catch {
      // Best effort.
    }
    setTab(next)
    setBody(readDraft(ticketId, next))
  }

  async function handleSend() {
    const text = body.trim()
    if (!text || sending) return
    setSending(true)
    setFailed(false)
    try {
      await onSend(text, tab)
      setBody('')
      try {
        sessionStorage.removeItem(draftKey(ticketId, tab))
      } catch {
        // Nothing to clear.
      }
    } catch {
      setFailed(true)
    } finally {
      setSending(false)
    }
  }

  const tabClass = (active: boolean, tone: 'primary' | 'warning') =>
    cn(
      '-mb-px min-h-10 border-b-2 px-4 text-sm font-medium',
      active ? (tone === 'primary' ? 'border-primary text-foreground' : 'border-warning text-foreground') : 'border-transparent text-muted-foreground hover:text-foreground',
    )

  return (
    <div className={cn('glass overflow-hidden rounded-xl focus-within:border-border-strong', className)}>
      {allowInternal && (
        <div className="flex border-b border-border" role="group" aria-label="Message type">
          <button type="button" aria-pressed={tab === 'PUBLIC'} onClick={() => switchTab('PUBLIC')} className={tabClass(tab === 'PUBLIC', 'primary')}>
            Reply to customer
          </button>
          <button type="button" aria-pressed={tab === 'INTERNAL'} onClick={() => switchTab('INTERNAL')} className={tabClass(tab === 'INTERNAL', 'warning')}>
            Internal note
          </button>
        </div>
      )}
      <textarea
        ref={textareaRef}
        id="reply-composer"
        aria-label={tab === 'PUBLIC' ? 'Reply' : 'Internal note'}
        value={body}
        onChange={(e) => setBody(e.target.value)}
        onKeyDown={(e) => {
          // Audit F2: ⌘/Ctrl+Enter sends.
          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
            e.preventDefault()
            void handleSend()
          }
        }}
        rows={3}
        placeholder={tab === 'PUBLIC' ? 'Write a reply…' : 'Note for the team…'}
        className={cn(
          'block max-h-[40svh] min-h-20 w-full resize-y border-0 bg-transparent p-3.5 text-base text-foreground placeholder:text-muted-foreground focus:outline-none',
          tab === 'INTERNAL' && 'bg-warning-soft',
        )}
      />
      {failed && <p className="px-3.5 text-xs text-danger" role="alert">Couldn&apos;t send — your text is safe. Try again.</p>}
      <div className="flex items-center justify-between gap-3 border-t border-border px-3.5 py-2">
        <p className="min-w-0 truncate text-xs text-muted-foreground" aria-live="polite">
          {sending ? 'Sending…' : saved ? 'Draft saved' : ''}
          <span className="hidden sm:inline">
            {sending || saved ? ' · ' : ''}
            <kbd className="font-mono">{isMac ? '⌘' : 'Ctrl'}</kbd>+<kbd className="font-mono">Enter</kbd> to {tab === 'PUBLIC' ? 'send' : 'save'}
          </span>
        </p>
        <Button size="sm" loading={sending} disabled={!body.trim()} onClick={handleSend}>
          <Send className="size-3.5" aria-hidden />
          {failed ? 'Retry' : tab === 'PUBLIC' ? 'Send reply' : 'Save note'}
        </Button>
      </div>
    </div>
  )
}
