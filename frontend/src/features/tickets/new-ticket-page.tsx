import { ArrowLeft, Clock, MessageSquare, Route } from 'lucide-react'
import { useDocumentTitle } from '@/components/layout/route-a11y'
import * as React from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Page, PageHeader } from '@/components/layout/page-header'
import { UnsavedGuard } from '@/components/app/unsaved-guard'
import { Button } from '@/components/ui/button'
import { FieldError, Label } from '@/components/ui/field-error'
import { Input, Textarea } from '@/components/ui/input'
import { ApiError } from '@/lib/api-client'
import { track } from '@/lib/analytics'
import { useCreateTicket } from './api'

const MAX_BODY = 20_000

const NEXT_STEPS = [
  { icon: Route, title: 'We route it', body: 'Your request is read and sent to the team that owns it — usually within a minute.' },
  { icon: Clock, title: 'A person replies', body: 'Every ticket carries a response target, so it is never left waiting unnoticed.' },
  { icon: MessageSquare, title: 'Keep the thread', body: 'Reply on the ticket any time; the whole conversation stays in one place.' },
]

export function NewTicketPage() {
  useDocumentTitle('New ticket')
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


  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setSubmitted(true)
    setFormError(null)
    if (!subject.trim() || !body.trim()) return
    try {
      const result = await createTicket.mutateAsync({ subject: subject.trim(), body: body.trim(), idempotencyKey })
      track('customer_ticket_created', { bodyLength: body.trim().length })
      navigate(`/tickets/${result.id}`, { state: { justCreated: true } })
    } catch (err) {
      setFormError(err instanceof ApiError ? err.problem.detail : 'Could not create your ticket. Nothing was lost — try again.')
    }
  }

  return (
    <Page width="wide">
      <UnsavedGuard when={!!(subject || body)} />
      <Link to="/tickets" className="mb-5 inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-3.5" aria-hidden /> My tickets
      </Link>
      <PageHeader title="New ticket" description="Tell us what's wrong. The more specific you are, the faster we can help." />

      <div className="grid gap-6 lg:grid-cols-[1fr_300px]">
      <form onSubmit={handleSubmit} className="glass space-y-5 rounded-xl p-6">
        {formError && (
          <div role="alert" className="rounded-md border border-danger/20 bg-danger-soft px-3 py-2 text-sm text-danger">
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
              <span className="ml-auto text-xs text-muted-foreground">
                {body.length.toLocaleString()} / {MAX_BODY.toLocaleString()}
              </span>
            )}
          </div>
        </div>

        <div className="flex items-center justify-between border-t border-border pt-5">
          <p className="text-xs text-muted-foreground">You can add more detail after submitting.</p>
          <Button type="submit" loading={createTicket.isPending}>
            Submit ticket
          </Button>
        </div>
      </form>

      <aside className="glass h-fit rounded-xl p-5">
        <p className="mb-4 text-xs font-medium uppercase tracking-[0.08em] text-muted-foreground">What happens next</p>
        <ol className="space-y-4">
          {NEXT_STEPS.map((step, i) => (
            <li key={step.title} className="flex gap-3">
              <span className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground">
                <step.icon className="size-3.5" aria-hidden />
              </span>
              <div>
                <p className="text-sm font-medium text-foreground">
                  <span className="mr-1 text-muted-foreground">{i + 1}.</span>
                  {step.title}
                </p>
                <p className="mt-0.5 text-sm leading-relaxed text-muted-foreground">{step.body}</p>
              </div>
            </li>
          ))}
        </ol>
      </aside>
      </div>
    </Page>
  )
}
