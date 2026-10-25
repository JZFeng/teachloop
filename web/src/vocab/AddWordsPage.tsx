import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { Button, Input, PageHeader, TextArea, useToast } from '../ui'
import './vocab.css'

interface Preview {
  lineCount: number
  distinctWords: string[]
  counts: { total: number; created: number; duplicated: number; failed: number }
  skipped: Array<{ line: number; text: string; reason: string }>
  cleanups: Array<{ line: number; rule: string; before: string; after: string }>
  spellingChecked: boolean
}

interface ListAttachment {
  name: string
  listId: string | null
  created: boolean
  added: number
  duplicated: number
  skippedReason: string | null
}

interface CommitResult {
  counts: { total: number; created: number; duplicated: number; failed: number }
  errors: Array<{ line: number; raw: string; reason: string }>
  /** AC-69.5：提交时带了词单名才有；null/undefined 表示没指定。 */
  list?: ListAttachment | null
}

export default function VocabAddWordsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const toast = useToast()
  const [params] = useSearchParams()
  const [tab, setTab] = useState<'paste' | 'file'>(params.get('tab') === 'file' ? 'file' : 'paste')
  const [text, setText] = useState('')
  const [label, setLabel] = useState('')
  const [listName, setListName] = useState('')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [result, setResult] = useState<CommitResult | null>(null)
  const [busy, setBusy] = useState(false)

  const doPreview = async () => {
    if (!text.trim()) return
    setBusy(true)
    try {
      setPreview(await api.post<Preview>('/api/vocab/import/preview', { text, language: 'en' }))
    } catch (previewError) {
      toast.error((previewError as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const doCommit = async () => {
    if (!text.trim()) return
    setBusy(true)
    try {
      const committed = await api.post<CommitResult>('/api/vocab/import/commit',
        { text, language: 'en', label: label.trim() || null, listName: listName.trim() || null })
      setResult(committed)
      const attached = committed.list
      if (attached?.skippedReason) {
        toast.error(t('vocab.add.listLimitSkipped', { name: attached.name }))
      } else if (attached) {
        toast.success(t('vocab.add.addedToList', { name: attached.name, added: attached.added }))
      } else {
        toast.success(t('vocab.add.done'))
      }
    } catch (commitError) {
      toast.error((commitError as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const readFile = async (file: File | null) => {
    if (!file) return
    if (file.size > 20 * 1024 * 1024) {
      toast.error(t('vocab.add.tooLarge'))
      return
    }
    setText(await file.text())
    setPreview(null)
    setResult(null)
  }

  return (
    <>
      <PageHeader title={t('vocab.add.title')} />
      <div className="card" style={{ maxWidth: 820 }}>
        <div className="row" style={{ gap: 8, marginBottom: 10 }}>
          <Button size="small" variant={tab === 'paste' ? 'primary' : 'default'}
            onClick={() => setTab('paste')}>{t('vocab.add.tabPaste')}</Button>
          <Button size="small" variant={tab === 'file' ? 'primary' : 'default'}
            onClick={() => setTab('file')}>{t('vocab.add.tabFile')}</Button>
        </div>

        {tab === 'file' && (
          <div style={{ marginBottom: 10 }}>
            <input type="file" accept=".csv,.md,.txt,text/plain,text/markdown,text/csv"
              onChange={(event) => void readFile(event.target.files?.[0] ?? null)} />
            <p className="muted sm">{t('vocab.add.fileHint')}</p>
          </div>
        )}

        <TextArea rows={10} value={text} placeholder={t('vocab.add.placeholder')}
          onChange={(event) => { setText(event.target.value); setPreview(null); setResult(null) }} />

        <p className="muted sm" style={{ margin: '8px 0 0' }}>{t('vocab.add.listNameHint')}</p>

        <div className="row" style={{ gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
          <Input value={label} placeholder={t('vocab.add.sourceLabel')} style={{ maxWidth: 260 }}
            onChange={(event) => setLabel(event.target.value)} />
          <Input value={listName} placeholder={t('vocab.add.listName')} style={{ maxWidth: 240 }}
            onChange={(event) => setListName(event.target.value)} />
          <Button onClick={() => void doPreview()} busy={busy}>{t('vocab.add.preview')}</Button>
          <Button variant="primary" onClick={() => void doCommit()} busy={busy}>{t('vocab.add.commit')}</Button>
          <Button onClick={() => navigate('/vocab/library')}>{t('vocab.add.back')}</Button>
        </div>

        {preview && !result && (
          <div className="card vocab-preview-panel" style={{ marginTop: 12 }}>
            <div className="row" style={{ gap: 16, flexWrap: 'wrap' }}>
              <span>{t('vocab.add.countTotal', { count: preview.counts.total })}</span>
              <span>{t('vocab.add.countCreate', { count: preview.counts.created })}</span>
              <span>{t('vocab.add.countDuplicate', { count: preview.counts.duplicated })}</span>
              <span>{t('vocab.add.countFailed', { count: preview.counts.failed })}</span>
            </div>
            {!preview.spellingChecked && <p className="muted sm">{t('vocab.add.spellingOff')}</p>}
            {preview.skipped.length > 0 && (
              <details style={{ marginTop: 8 }}>
                <summary>{t('vocab.add.skippedSummary', { count: preview.skipped.length })}</summary>
                <ul className="sm">
                  {preview.skipped.map((line) => (
                    <li key={`${line.line}-${line.text}`}>#{line.line} {line.text} — {line.reason}</li>
                  ))}
                </ul>
              </details>
            )}
            {preview.cleanups.length > 0 && (
              <details style={{ marginTop: 8 }}>
                <summary>{t('vocab.add.cleanupsSummary', { count: preview.cleanups.length })}</summary>
                <ul className="sm">
                  {preview.cleanups.map((cleanup, index) => (
                    <li key={index}>#{cleanup.line} {cleanup.rule}：{cleanup.before} → {cleanup.after}</li>
                  ))}
                </ul>
              </details>
            )}
          </div>
        )}

        {result && (
          <div className="card" style={{ marginTop: 12 }}>
            <p>{t('vocab.add.resultLine', {
              created: result.counts.created,
              duplicated: result.counts.duplicated,
              failed: result.counts.failed,
            })}</p>
            {result.list && (
              result.list.skippedReason
                ? <p className="sm">{t('vocab.add.listLimitSkipped', { name: result.list.name })}</p>
                : <p className="sm">{t('vocab.add.addedToList', { name: result.list.name, added: result.list.added })}</p>
            )}
            {result.errors.length > 0 && (
              <ul className="sm">
                {result.errors.map((error) => (
                  <li key={`${error.line}-${error.raw}`}>#{error.line} {error.raw} — {error.reason}</li>
                ))}
              </ul>
            )}
            <Button onClick={() => navigate('/vocab/library')}>{t('vocab.add.goLibrary')}</Button>
          </div>
        )}
      </div>
    </>
  )
}
