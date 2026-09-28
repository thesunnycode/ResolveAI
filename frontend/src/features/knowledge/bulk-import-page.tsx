import * as Select from '@radix-ui/react-select'
import { ChevronDown, FileText, Upload, X } from 'lucide-react'
import * as React from 'react'
import { useNavigate } from 'react-router-dom'
import { useDocumentTitle } from '@/components/layout/route-a11y'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/field-error'
import { useToast } from '@/components/ui/toast'
import { ApiError } from '@/lib/api-client'
import { useBulkImportKnowledgeDocuments, type BulkImportResult } from './api'

const TEMPLATE = `title,body,source,uri
"Resetting your password","Ask the customer to use the ""Forgot password"" link on the sign-in page. Links expire after 24 hours.",ARTICLE,
`

function downloadTemplate() {
  const blob = new Blob([TEMPLATE], { type: 'text/csv' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'knowledge-import-template.csv'
  a.click()
  URL.revokeObjectURL(url)
}

function formatBytes(bytes: number): string {
  return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`
}

export function BulkImportPage() {
  useDocumentTitle('Bulk import knowledge documents')
  const navigate = useNavigate()
  const { push } = useToast()
  const bulkImport = useBulkImportKnowledgeDocuments()
  const [files, setFiles] = React.useState<File[]>([])
  const [pdfSource, setPdfSource] = React.useState('ARTICLE')
  const [result, setResult] = React.useState<BulkImportResult | null>(null)
  const fileInputRef = React.useRef<HTMLInputElement>(null)

  function addFiles(list: FileList | null) {
    if (!list) return
    setFiles((prev) => [...prev, ...Array.from(list)])
    setResult(null)
  }

  function removeFile(index: number) {
    setFiles((prev) => prev.filter((_, i) => i !== index))
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (files.length === 0) {
      push('error', 'Choose at least one .csv or .pdf file.')
      return
    }
    const formData = new FormData()
    for (const file of files) formData.append('files', file)
    formData.append('source', pdfSource)

    try {
      const res = await bulkImport.mutateAsync(formData)
      setResult(res)
      setFiles([])
      if (fileInputRef.current) fileInputRef.current.value = ''
      push('success', `${res.imported} document(s) imported.`)
    } catch (err) {
      push('error', err instanceof ApiError ? err.problem.detail : 'Import failed.')
    }
  }

  return (
    <div className="mx-auto max-w-2xl px-5 py-8 sm:px-8 lg:py-10 animate-slide-up">
      <h1 className="mb-2 text-xl font-semibold text-foreground">Bulk import knowledge documents</h1>
      <p className="mb-5 text-sm text-muted-foreground">
        Upload a CSV (one document per row) and/or one or more PDFs (one document per file) to
        migrate an existing help center or runbook set at once.
      </p>

      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <Label>CSV format</Label>
          <p className="mb-1.5 text-sm text-muted-foreground">
            Columns: <code className="font-mono text-xs">title,body,source,uri</code> —{' '}
            <code className="font-mono text-xs">source</code> must be{' '}
            <code className="font-mono text-xs">RUNBOOK</code> or{' '}
            <code className="font-mono text-xs">ARTICLE</code>; <code className="font-mono text-xs">uri</code>{' '}
            may be blank.{' '}
            <button type="button" onClick={downloadTemplate} className="font-medium text-primary hover:underline">
              Download a template
            </button>
            .
          </p>
        </div>

        <div>
          <Label required>Files</Label>
          <label className="flex cursor-pointer flex-col items-center gap-2 rounded-lg border border-dashed border-input p-6 text-center hover:bg-muted/50">
            <Upload className="size-5 text-muted-foreground" aria-hidden />
            <span className="text-sm text-muted-foreground">Click to choose .csv or .pdf files</span>
            <input
              ref={fileInputRef}
              type="file"
              accept=".csv,.pdf"
              multiple
              className="hidden"
              onChange={(e) => addFiles(e.target.files)}
            />
          </label>
          {files.length > 0 && (
            <ul className="mt-2 space-y-1">
              {files.map((f, i) => (
                <li
                  key={`${f.name}-${i}`}
                  className="flex items-center justify-between rounded-md border border-border bg-muted/30 px-3 py-1.5 text-sm"
                >
                  <span className="flex items-center gap-2 text-foreground">
                    <FileText className="size-3.5 text-muted-foreground" aria-hidden />
                    {f.name} <span className="text-xs text-muted-foreground">{formatBytes(f.size)}</span>
                  </span>
                  <button
                    type="button"
                    onClick={() => removeFile(i)}
                    className="text-muted-foreground hover:text-foreground"
                    aria-label={`Remove ${f.name}`}
                  >
                    <X className="size-3.5" aria-hidden />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div>
          <Label>Import PDFs as</Label>
          <p className="mb-1.5 text-xs text-muted-foreground">
            Only affects uploaded PDFs — CSV rows always use their own <code className="font-mono text-xs">source</code>{' '}
            column.
          </p>
          <Select.Root value={pdfSource} onValueChange={setPdfSource}>
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
                    <Select.Item
                      key={s}
                      value={s}
                      className="cursor-pointer rounded px-3 py-1.5 text-base outline-none data-[highlighted]:bg-muted"
                    >
                      <Select.ItemText>{s}</Select.ItemText>
                    </Select.Item>
                  ))}
                </Select.Viewport>
              </Select.Content>
            </Select.Portal>
          </Select.Root>
        </div>

        <div className="flex gap-2">
          <Button type="submit" loading={bulkImport.isPending}>
            Import
          </Button>
          <Button type="button" variant="secondary" onClick={() => navigate('/knowledge')}>
            Back to knowledge base
          </Button>
        </div>
      </form>

      {result && result.skipped.length > 0 && (
        <div className="mt-8">
          <h2 className="mb-2 text-sm font-semibold text-foreground">
            {result.skipped.length} item(s) skipped
          </h2>
          <div className="overflow-hidden rounded-lg border border-border bg-muted/30">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-border text-xs uppercase tracking-[0.08em] text-muted-foreground">
                  <th className="px-4 py-2.5 font-medium">Source</th>
                  <th className="px-4 py-2.5 font-medium">Title</th>
                  <th className="px-4 py-2.5 font-medium">Reason</th>
                </tr>
              </thead>
              <tbody>
                {result.skipped.map((s, i) => (
                  <tr key={i} className="border-b border-border last:border-0">
                    <td className="px-4 py-2.5 text-muted-foreground">{s.source}</td>
                    <td className="px-4 py-2.5 font-medium text-foreground">{s.title || '—'}</td>
                    <td className="px-4 py-2.5 text-muted-foreground">{s.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}
