import * as Select from '@radix-ui/react-select'
import { useDocumentTitle } from '@/components/layout/route-a11y'
import { ChevronDown } from 'lucide-react'
import * as React from 'react'
import { useNavigate } from 'react-router-dom'
import { UnsavedGuard } from '@/components/app/unsaved-guard'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/field-error'
import { Input, Textarea } from '@/components/ui/input'
import { useToast } from '@/components/ui/toast'
import { ApiError } from '@/lib/api-client'
import { useCreateKnowledgeDocument } from './api'

export function KnowledgeDocumentFormPage() {
  useDocumentTitle('Add a knowledge document')
  const navigate = useNavigate()
  const { push } = useToast()
  const createDoc = useCreateKnowledgeDocument()
  const [title, setTitle] = React.useState('')
  const [source, setSource] = React.useState('RUNBOOK')
  const [body, setBody] = React.useState('')
  const [uri, setUri] = React.useState('')

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    try {
      await createDoc.mutateAsync({ title, source, body, uri: uri || undefined })
      push('success', 'Document saved — indexing…')
      navigate('/knowledge')
    } catch (err) {
      push('error', err instanceof ApiError ? err.problem.detail : 'Could not save.')
    }
  }

  return (
    <div className="mx-auto max-w-2xl px-5 py-8 sm:px-8 lg:py-10 animate-slide-up">
      <UnsavedGuard when={!!(title || body || uri)} />
      <h1 className="mb-5 text-xl font-semibold text-foreground">Add knowledge document</h1>
      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <Label required>Title</Label>
          <Input value={title} onChange={(e) => setTitle(e.target.value)} required />
        </div>
        <div>
          <Label required>Source</Label>
          <Select.Root value={source} onValueChange={setSource}>
            <Select.Trigger className="glass flex h-10 w-48 items-center justify-between rounded-md px-3 text-base text-foreground">
              <Select.Value />
              <Select.Icon>
                <ChevronDown className="size-3.5 text-muted-foreground" />
              </Select.Icon>
            </Select.Trigger>
            <Select.Portal>
              <Select.Content className="rounded-md border border-border bg-popover shadow-popover">
                <Select.Viewport className="p-1">
                  {['RUNBOOK', 'ARTICLE'].map((s) => (
                    <Select.Item key={s} value={s} className="cursor-pointer rounded px-3 py-1.5 text-base outline-none data-[highlighted]:bg-muted">
                      <Select.ItemText>{s}</Select.ItemText>
                    </Select.Item>
                  ))}
                </Select.Viewport>
              </Select.Content>
            </Select.Portal>
          </Select.Root>
        </div>
        <div>
          <Label>Source URI (optional)</Label>
          <Input value={uri} onChange={(e) => setUri(e.target.value)} placeholder="https://…" />
        </div>
        <div>
          <Label required>Body</Label>
          <Textarea
            rows={14}
            value={body}
            onChange={(e) => setBody(e.target.value)}
            className="font-mono text-sm"
            required
          />
        </div>
        <Button type="submit" loading={createDoc.isPending}>
          Save
        </Button>
      </form>
    </div>
  )
}
