import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { PaperDetail, PaperImportItem, PaperImportResult, PaperView } from '../api/types'
import { PageHeader } from '../components/States'

interface EditableItem extends PaperImportItem {
  saving?: boolean
  saved?: boolean
}

interface AnswerTableResult {
  updated: number
  unknownNumbers: number[]
  warnings: string[]
}

/** 导入考卷：建卷 + 建部分 + 上传/粘贴整卷，解析后可逐题修正。 */
export default function ImportPaperPage() {
  const { t } = useTranslation('translation')
  const navigate = useNavigate()
  const [title, setTitle] = useState('')
  const [sectionTitle, setSectionTitle] = useState('')
  const [pointsPerItem, setPointsPerItem] = useState('5')
  const [mode, setMode] = useState<'file' | 'text'>('file')
  const [file, setFile] = useState<File | null>(null)
  const [text, setText] = useState('')
  const [paperId, setPaperId] = useState<string | null>(null)
  const [result, setResult] = useState<PaperImportResult | null>(null)
  const [rows, setRows] = useState<EditableItem[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [answerText, setAnswerText] = useState('')
  const [answerBusy, setAnswerBusy] = useState(false)
  const [answerNote, setAnswerNote] = useState<string | null>(null)
  const [answerWarnings, setAnswerWarnings] = useState<string[]>([])

  /** 缺答案一律从当前行数据现算，补完答案后提示会自己消失。 */
  const missingNumbers = rows.filter((row) => !(row.answerKey ?? '').trim()).map((row) => row.number)

  const runImport = async () => {
    if (!title.trim()) { setError(t('teacher.import.errTitle')); return }
    if (mode === 'file' && !file) { setError(t('teacher.import.errFile')); return }
    if (mode === 'text' && !text.trim()) { setError(t('teacher.import.errText')); return }
    setBusy(true); setError(null); setResult(null)
    try {
      const paper = await api.post<PaperView>('/api/papers', {
        title: title.trim(), defaultDurationMinutes: 90,
      })
      const section = await api.post<{ id: string }>(`/api/papers/${paper.id}/sections`, {
        title: sectionTitle.trim() || t('teacher.import.defaultSection'),
        pointsPerItem: Number(pointsPerItem) || 1,
      })
      let imported: PaperImportResult
      if (mode === 'file' && file) {
        const body = new FormData()
        body.append('file', file)
        const res = await fetch(`/api/papers/${paper.id}/import?sectionId=${section.id}`, {
          method: 'POST', credentials: 'include', body,
        })
        const payload = await res.json().catch(() => null)
        if (!res.ok) throw new Error(payload?.detail || payload?.message || `HTTP ${res.status}`)
        imported = payload as PaperImportResult
      } else {
        imported = await api.post<PaperImportResult>(`/api/papers/${paper.id}/import/text`, {
          text, sectionId: section.id,
        })
      }
      setPaperId(paper.id)
      setResult(imported)
      setRows(imported.items.map((i) => ({ ...i })))
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const updateRow = (number: number, patch: Partial<EditableItem>) => {
    setRows((current) => current.map((r) => (r.number === number ? { ...r, ...patch, saved: false } : r)))
  }

  const saveRow = async (row: EditableItem) => {
    if (!paperId) return
    setRows((current) => current.map((r) => (r.number === row.number ? { ...r, saving: true } : r)))
    try {
      await api.put(`/api/papers/${paperId}/items/${row.number}`, {
        stem: row.stem, options: row.options, answerKey: row.answerKey,
      })
      setRows((current) => current.map((r) => (r.number === row.number ? { ...r, saving: false, saved: true } : r)))
    } catch (e) {
      setError((e as Error).message)
      setRows((current) => current.map((r) => (r.number === row.number ? { ...r, saving: false } : r)))
    }
  }

  /**
   * 粘贴答案表：走独立端点补齐已有题项，不动卷面（Gradescope 的 Answer Key 独立页形态）。
   * 补完重拉一次题项，缺答案提示按真实数据重算。
   */
  const fillAnswers = async () => {
    if (!paperId || !answerText.trim()) return
    setAnswerBusy(true)
    setAnswerNote(null)
    setAnswerWarnings([])
    try {
      const res = await api.post<AnswerTableResult>(`/api/papers/${paperId}/answers`, {
        text: answerText,
      })
      const detail = await api.get<PaperDetail>(`/api/papers/${paperId}`)
      setRows(detail.sections.flatMap((section) => section.items.map((item) => ({
        number: item.number,
        stem: item.stem ?? '',
        options: item.options,
        answerKey: item.answerKey,
        points: item.points,
        questionType: item.questionType,
      }))))
      setAnswerWarnings(res.warnings ?? [])
      const unknown = res.unknownNumbers?.length
        ? t('teacher.import.unknownNumbers', { numbers: res.unknownNumbers.join(', ') })
        : ''
      setAnswerNote(t('teacher.import.filled', { n: res.updated }) + unknown)
    } catch (e) {
      setAnswerNote((e as Error).message)
    } finally {
      setAnswerBusy(false)
    }
  }

  return (
    <>
      <PageHeader
        title={t('teacher.import.title')}
        sub={t('teacher.import.sub')}
        actions={<Link className="btn" to="/papers">{t('teacher.import.backToLibrary')}</Link>}
      />

      <div className="card">
        <div className="row" style={{ alignItems: 'flex-start' }}>
          <label className="f" style={{ flex: 2 }}><b>{t('teacher.import.paperTitle')} <i>*</i></b>
            <input value={title} placeholder={t('teacher.import.titlePlaceholder')}
              onChange={(e) => setTitle(e.target.value)} />
            <div className="hint">{t('teacher.import.titleHint')}</div>
          </label>
          <label className="f" style={{ flex: 1 }}><b>{t('teacher.import.sectionName')}</b>
            <input value={sectionTitle} placeholder={t('teacher.import.defaultSection')}
              onChange={(e) => setSectionTitle(e.target.value)} />
          </label>
          <label className="f" style={{ width: 130 }}><b>{t('teacher.import.pointsPerItem')}</b>
            <input type="number" min={1} value={pointsPerItem} onChange={(e) => setPointsPerItem(e.target.value)} />
          </label>
        </div>

        <div className="row" style={{ marginTop: 8 }}>
          <button className={`chip${mode === 'file' ? ' on' : ''}`} onClick={() => setMode('file')}>
            {t('teacher.import.tabFile')}
          </button>
          <button className={`chip${mode === 'text' ? ' on' : ''}`} onClick={() => setMode('text')}>
            {t('teacher.import.tabText')}
          </button>
        </div>

        {mode === 'file' ? (
          <div style={{ marginTop: 10 }}>
            <input type="file" accept=".docx,.md,.txt,.xlsx"
              onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null) }} />
            <div className="hint">{t('teacher.import.fileHint')}</div>
          </div>
        ) : (
          <textarea style={{ width: '100%', minHeight: 180, marginTop: 10, fontFamily: 'monospace' }}
            placeholder={t('teacher.import.textPlaceholder')}
            value={text} onChange={(e) => setText(e.target.value)} />
        )}

        <div className="row" style={{ marginTop: 10 }}>
          <button className="btn pri" disabled={busy || !title.trim() || (mode === 'file' ? !file : !text.trim())}
            onClick={() => void runImport()}>
            {busy ? t('teacher.import.running') : t('teacher.import.run')}
          </button>
          <span className="small muted">{t('teacher.import.runHint')}</span>
        </div>
        {error && <div className="inline-note danger">{error}</div>}
      </div>

      {result && (
        <div className="card">
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <h2 style={{ margin: 0 }}>{t('teacher.import.reviewTitle')}</h2>
            <div className="row">
              <span className="tag green">{t('teacher.import.imported', { n: result.imported })}</span>
              {missingNumbers.length > 0 && (
                <span className="tag amber">
                  {t('teacher.import.missing', { numbers: missingNumbers.join(', ') })}
                </span>
              )}
              {paperId && (
                <button className="btn" onClick={() => navigate(`/papers/${paperId}`)}>
                  {t('teacher.import.gotoWizard')}
                </button>
              )}
            </div>
          </div>
          {result.warnings.length > 0 && (
            <ul className="small muted" style={{ paddingLeft: 20 }}>
              {result.warnings.map((w, i) => <li key={i}>{w}</li>)}
            </ul>
          )}

          {paperId && (
            <div className="inline-note" style={{ marginTop: 10 }}>
              <b>{t('teacher.import.answerPanel')}</b>
              <div className="hint" style={{ marginBottom: 6 }}>
                {t('teacher.import.answerIntro')}
                <br />
                {t('teacher.import.answerChoice')}
                {t('teacher.import.answerRange')}
                {t('teacher.import.answerMulti')}
                <br />
                {t('teacher.import.answerFill')}
                <br />
                {t('teacher.import.answerNoBlank')}
              </div>
              <textarea
                style={{ width: '100%', minHeight: 110, fontFamily: 'monospace' }}
                placeholder={'1 A\n2 B\n3 B\n4 C'}
                value={answerText}
                onChange={(e) => setAnswerText(e.target.value)}
              />
              <div className="row" style={{ marginTop: 8 }}>
                <button className="btn pri" disabled={answerBusy || !answerText.trim()}
                  onClick={() => void fillAnswers()}>
                  {answerBusy ? t('teacher.import.filling') : t('teacher.import.fill')}
                </button>
                {answerNote && <span className="small muted">{answerNote}</span>}
              </div>
              {answerWarnings.length > 0 && (
                <ul className="small muted" style={{ paddingLeft: 20 }}>
                  {answerWarnings.map((w, i) => <li key={i}>{w}</li>)}
                </ul>
              )}
            </div>
          )}

          <table style={{ marginTop: 10 }}>
            <thead>
              <tr>
                <th>{t('teacher.import.colNumber')}</th>
                <th>{t('teacher.import.colStem')}</th>
                <th>{t('teacher.import.colOptions')}</th>
                <th>{t('teacher.import.colAnswer')}</th>
                <th>{t('teacher.import.colActions')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.number}>
                  <td className="mono">{row.number}</td>
                  <td><input value={row.stem} onChange={(e) => updateRow(row.number, { stem: e.target.value })} /></td>
                  <td><textarea rows={3} value={row.options ?? ''} onChange={(e) => updateRow(row.number, { options: e.target.value })} /></td>
                  <td><input style={{ width: 90 }} value={row.answerKey ?? ''} onChange={(e) => updateRow(row.number, { answerKey: e.target.value })} /></td>
                  <td>
                    <button className="btn sm" disabled={row.saving} onClick={() => void saveRow(row)}>
                      {row.saving
                        ? t('teacher.import.saving')
                        : row.saved ? t('teacher.import.saved') : t('teacher.import.save')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {!result && paperId && <p className="muted">{t('teacher.import.emptyDraft')}</p>}
    </>
  )
}
