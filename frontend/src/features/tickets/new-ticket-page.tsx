import { ArrowLeft, Clock, MessageSquare, Route } from 'lucide-react'
import * as React from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Page, PageHeader } from '@/components/layout/page-header'
import { Button } from '@/components/ui/button'
import { FieldError, Label } from '@/components/ui/field-error'
import { Input, Textarea } from '@/components/ui/input'
import { ApiError } from '@/lib/api-client'
import { useCreateTicket } from './api'

const MAX_BODY = 20_000

const NEXT_STEPS = [
  { icon: Route, title: 'We route it', body: 'Your request is read and sent to the team that owns it — usually within a minute.' },
  { icon: Clock, title: 'A person replies', body: 'Every ticket carries a response target, so it is never left waiting unnoticed.' },
  { icon: MessageSquare, title: 'Keep the thread', body: 'Reply on the ticket any time; the whole conversation stays in one place.' },
]

export function NewTicketPage() {
  const navigate = useNavigate()
  const createTicket = useCreateTicket()
  const idempotencyKey = React.useRef(crypto.randomUUID()).current

  const [subject, setSubject] = React.useState('')
  const [body, setBody] = React.useState('')
  const [subjectTouched, setSubjectTouched] = React.useState(false)
  const [submitted, setSubmitted] = React.useState(false)
  const [formError, setFormError] = React.useState<string | null>(null)

  const subjectError = subjectTouched && subject.trim().length === 0 ? 'Subject is required.' : undefined
  const bodyError = submitted && body.trim().length === 0 ? 'Description is required.' : undefined

  React.useEffect(() => {
    function warn(e: BeforeUnloadEvent) {
      if (subject || body) e.preventDefault()
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [subject, body])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setSubmitted(true)
    setFormError(null)
    if (!subject.trim() || !body.trim()) return
    try {
      const result = await createTicket.mutateAsync({ subject: subject.trim(), body: body.trim(), idempotencyKey })
      navigate(`/tickets/${result.id}`)
    } catch (err) {
      setFormError(err instanceof ApiError ? err.problem.detail : 'Could not create your ticket. Nothing was lost — try again.')
    }
  }

  return (
    <Page width="wide">
      <Link to="/my-tickets" className="mb-5 inline-flex items-center gap-1.5 text-[13px] text-text-muted hover:text-text">
        <ArrowLeft className="size-3.5" aria-hidden /> My tickets
      </Link>
      <PageHeader title="New ticket" description="Tell us what's wrong. The more specific you are, the faster we can help." />

      <div className="grid gap-6 lg:grid-cols-[1fr_300px]">
      <form onSubmit={handleSubmit} className="glass space-y-5 rounded-xl p-6">
        {formError && (
          <div role="alert" className="rounded-md border border-danger/20 bg-danger-bg px-3 py-2 text-[13px] text-danger">
            {formError}
          </div>
        )}

        <div>
          <Label htmlFor="subject" required>
            Subject
          </Label>
          <Input
            id="subject"
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
            onBlur={() => setSubjectTouched(true)}
            error={!!subjectError}
            maxLength={200}
            placeholder="A short summary of the problem"
          />
          <FieldError message={subjectError} />
        </div>

        <div>
          <Label htmlFor="body" required>
            Describe the problem
          </Label>
          <Textarea
            id="body"
            rows={10}
            value={body}
            onChange={(e) => setBody(e.target.value.slice(0, MAX_BODY))}
            error={!!bodyError}
            placeholder="What happened, and when? Include any order or reference numbers."
          />
          <div className="mt-1 flex justify-between">
            <FieldError message={bodyError} />
            {body.length > MAX_BODY * 0.9 && (
              <span className="ml-auto text-[12px] text-text-subtle">
                {body.length.toLocaleString()} / {MAX_BODY.toLocaleString()}
              </span>
            )}
          </div>
        </div>

        <div className="flex items-center justify-between border-t border-border pt-5">
          <p className="text-[12px] text-text-subtle">You can add more detail after submitting.</p>
          <Button type="submit" loading={createTicket.isPending}>
            Submit ticket
          </Button>
        </div>
      </form>

      <aside className="glass h-fit rounded-xl p-5">
        <p className="mb-4 text-[12px] font-medium uppercase tracking-[0.08em] text-text-subtle">What happens next</p>
        <ol className="space-y-4">
          {NEXT_STEPS.map((step, i) => (
            <li key={step.title} className="flex gap-3">
              <span className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-surface-2 text-text-muted">
                <step.icon className="size-3.5" aria-hidden />
              </span>
              <div>
                <p className="text-[13px] font-medium text-text">
                  <span className="mr-1 text-text-subtle">{i + 1}.</span>
                  {step.title}
                </p>
                <p className="mt-0.5 text-[12.5px] leading-relaxed text-text-muted">{step.body}</p>
              </div>
            </li>
          ))}
        </ol>
      </aside>
      </div>
    </Page>
  )
}
