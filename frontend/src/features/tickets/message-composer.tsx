import { Paperclip, Send } from 'lucide-react'
import * as React from 'react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export function MessageComposer({
  allowInternal,
  prefill,
  focusSignal,
  onSend,
}: {
  allowInternal: boolean
  prefill?: string
  /** Bump to move focus into the reply box (e.g. "Reply manually" on a suppressed draft). */
  focusSignal?: number
  onSend: (body: string, visibility: 'PUBLIC' | 'INTERNAL') => Promise<void>
}) {
  const textareaRef = React.useRef<HTMLTextAreaElement>(null)
  const [tab, setTab] = React.useState<'PUBLIC' | 'INTERNAL'>('PUBLIC')
  const [body, setBody] = React.useState(prefill ?? '')
  const [sending, setSending] = React.useState(false)
  const [failed, setFailed] = React.useState(false)

  React.useEffect(() => {
    if (prefill) {
      setBody(prefill)
      setTab('PUBLIC')
    }
  }, [prefill])

  React.useEffect(() => {
    if (!focusSignal) return
    textareaRef.current?.focus()
    textareaRef.current?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [focusSignal])

  async function handleSend() {
    if (!body.trim()) return
    setSending(true)
    setFailed(false)
    try {
      await onSend(body.trim(), tab)
      setBody('')
    } catch {
      setFailed(true)
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="glass overflow-hidden rounded-xl focus-within:border-border-strong">
      <div className="flex border-b border-border">
        <button
          onClick={() => setTab('PUBLIC')}
          className={cn(
            'px-4 py-2 text-[13px] font-medium border-b-2 -mb-px',
            tab === 'PUBLIC' ? 'border-primary text-text' : 'border-transparent text-text-muted',
          )}
        >
          {allowInternal ? 'Reply to customer' : 'Reply'}
        </button>
        {allowInternal && (
          <button
            onClick={() => setTab('INTERNAL')}
            className={cn(
              'px-4 py-2 text-[13px] font-medium border-b-2 -mb-px',
              tab === 'INTERNAL' ? 'border-warning text-text' : 'border-transparent text-text-muted',
            )}
          >
            Internal note
          </button>
        )}
      </div>
      <textarea
        ref={textareaRef}
        aria-label={tab === 'PUBLIC' ? 'Reply' : 'Internal note'}
        value={body}
        onChange={(e) => setBody(e.target.value)}
        rows={4}
        placeholder={tab === 'PUBLIC' ? 'Write a reply…' : 'Note for the team…'}
        className={cn(
          'w-full resize-y border-0 bg-transparent p-3.5 text-[14px] text-text placeholder:text-text-subtle focus:outline-none',
          tab === 'INTERNAL' && 'bg-warning-bg',
        )}
      />
      {failed && (
        <p className="px-3.5 text-[12px] text-danger">Couldn&apos;t send — your text is safe. Try again.</p>
      )}
      <div className="flex items-center justify-between border-t border-border px-3.5 py-2">
        <button className="flex items-center gap-1.5 text-[13px] text-text-muted hover:text-text" type="button">
          <Paperclip className="size-3.5" aria-hidden /> Attach
        </button>
        <Button size="sm" loading={sending} disabled={!body.trim()} onClick={handleSend}>
          <Send className="size-3.5" aria-hidden />
          {failed ? 'Retry' : tab === 'PUBLIC' ? 'Send reply' : 'Save note'}
        </Button>
      </div>
    </div>
  )
}
