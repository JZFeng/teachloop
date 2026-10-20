import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { PaperImportItem, PaperImportResult, PaperView } from '../api/types'
import { ErrorState, PageHeader } from '../components/States'

interface EditableItem extends PaperImportItem {
  saving?: boolean
  saved?: boolean
}

/** 导入考卷：建卷 + 建部分 + 上传/粘贴整卷，解析后可逐题修正。 */
export default function ImportPaperPage() {
  const navigate = useNavigate()
  const [title, setTitle] = useState('')
  const [sectionTitle, setSectionTitle] = useState('综合')
  const [pointsPerItem, setPointsPerItem] = useState('5')
  const [mode, setMode] = useState<'file' | 'text'>('file')
  const [file, setFile] = useState<File | null>(null)
  const [text, setText] = useState('')
  const [paperId, setPaperId] = useState<string | null>(null)
  const [result, setResult] = useState<PaperImportResult | null>(null)
  const [rows, setRows] = useState<EditableItem[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const runImport = async () => {
    if (!title.trim()) { setError('先填试卷标题'); return }
    if (mode === 'file' && !file) { setError('选择 docx / md / txt / xlsx 文件'); return }
    if (mode === 'text' && !text.trim()) { setError('粘贴整卷文字'); return }
    setBusy(true); setError(null); setResult(null)
    try {
      const paper = await api.post<PaperView>('/api/papers', {
        title: title.trim(), defaultDurationMinutes: 90,
      })
      const section = await api.post<{ id: string }>(`/api/papers/${paper.id}/sections`, {
        title: sectionTitle.trim() || '综合', pointsPerItem: Number(pointsPerItem) || 1,
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

  return (
    <>
      <PageHeader
        title="导入考卷"
        sub="上传 docx/md/txt/xlsx，或直接粘贴整卷文字；解析后逐题校对，再进向导补评分项与发布"
        actions={<Link className="btn" to="/papers">返回试卷库</Link>}
      />

      <div className="card">
        <div className="row" style={{ alignItems: 'flex-start' }}>
          <label className="f" style={{ flex: 2 }}><b>试卷标题</b>
            <input value={title} placeholder="例如：高一英语期中卷" onChange={(e) => setTitle(e.target.value)} />
          </label>
          <label className="f" style={{ flex: 1 }}><b>部分名称</b>
            <input value={sectionTitle} onChange={(e) => setSectionTitle(e.target.value)} />
          </label>
          <label className="f" style={{ width: 130 }}><b>每题默认分</b>
            <input type="number" min={1} value={pointsPerItem} onChange={(e) => setPointsPerItem(e.target.value)} />
          </label>
        </div>

        <div className="row" style={{ marginTop: 8 }}>
          <button className={`chip${mode === 'file' ? ' on' : ''}`} onClick={() => setMode('file')}>上传文件</button>
          <button className={`chip${mode === 'text' ? ' on' : ''}`} onClick={() => setMode('text')}>粘贴文字</button>
        </div>

        {mode === 'file' ? (
          <div style={{ marginTop: 10 }}>
            <input type="file" accept=".docx,.md,.txt,.xlsx"
              onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null) }} />
            <div className="hint">支持 .docx / .md / .txt / .xlsx；.doc 请先另存为 .docx。题目和答案可以写在同一份文档里。</div>
          </div>
        ) : (
          <textarea style={{ width: '100%', minHeight: 180, marginTop: 10, fontFamily: 'monospace' }}
            placeholder={'1. 题干...\nA. 选项\nB. 选项\n\n答案\n1-5 ABCDA'}
            value={text} onChange={(e) => setText(e.target.value)} />
        )}

        <div className="row" style={{ marginTop: 10 }}>
          <button className="btn pri" disabled={busy} onClick={() => void runImport()}>
            {busy ? '解析中…' : '创建试卷并解析'}
          </button>
          <span className="small muted">会先创建一份草稿试卷和一个部分，解析结果可逐题修正。</span>
        </div>
        {error && <ErrorState error={new Error(error)} onRetry={() => setError(null)} />}
      </div>

      {result && (
        <div className="card">
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <h2 style={{ margin: 0 }}>校对导入结果</h2>
            <div className="row">
              <span className="tag green">已导入 {result.imported} 题</span>
              {result.missingAnswers.length > 0 && (
                <span className="tag amber">缺答案：{result.missingAnswers.join(', ')}</span>
              )}
              {paperId && <button className="btn" onClick={() => navigate(`/papers/${paperId}`)}>去向导补评分项</button>}
            </div>
          </div>
          {result.warnings.length > 0 && (
            <ul className="small muted" style={{ paddingLeft: 20 }}>
              {result.warnings.map((w, i) => <li key={i}>{w}</li>)}
            </ul>
          )}
          <table style={{ marginTop: 10 }}>
            <thead>
              <tr><th>#</th><th>题干</th><th>选项</th><th>答案</th><th>操作</th></tr>
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
                      {row.saving ? '保存中' : row.saved ? '已保存' : '保存'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {!result && paperId && <p className="muted">已创建试卷草稿，但没有解析出题目。</p>}
    </>
  )
}
