import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../../api/client'
import { Button, TextArea, useToast } from '../../ui'
import './import-panel.css'

/**
 * 粘贴 / 上传 + 预览 + 提交，一份组件两处用（AC-100.6 / FR-241）。
 *
 * 出处：原来整段在 `vocab/AddWordsPage.tsx` 里，013 的「书导入」要的是**同一套**解析、
 * 清洗、跳过与拼写提示，只是提交目标换成某本书。所以把这段抽成受控组件，两个页面各自
 * 传路径：
 *   - 加词页：`/api/vocab/import/{preview,commit}`，另带 `listName`（AC-69.5）；
 *   - 书导入：`/api/admin/vocab/books/{id}/entries/{preview,commit}`，不带 listName。
 *
 * 预览与提交的响应形状两边完全一致（后端的书导入复用 `WordImportService`），
 * 所以这里只定义一份类型。加词页的提交响应多一个 `list` 字段（把这一批词塞进词单），
 * 有就渲染、没有就不渲染。
 *
 * 文案：本组件内部沿用 `vocab.add.*`（加词页的既有 key，行为与文案不许变）；
 * 页面级的标题、提交按钮文字（`commitLabel`）与返回动作由调用方给。
 */

export interface ImportCounts {
  total: number
  created: number
  duplicated: number
  failed: number
}

export interface ImportPreview {
  lineCount: number
  counts: ImportCounts
  skipped: Array<{ line: number; text: string; reason: string }>
  cleanups: Array<{ line: number; rule: string; before: string; after: string }>
  spellingChecked: boolean
  /** 后端还返回 tokens / distinctWords / lineErrors / pending，这里只渲染界面要用的几项。 */
}

export interface ImportListAttachment {
  name: string
  listId: string | null
  created: boolean
  added: number
  duplicated: number
  skippedReason: string | null
}

export interface ImportCommitResult {
  counts: ImportCounts
  errors: Array<{ line: number; raw: string; reason: string }>
  /** 只有加词页（带了 listName）才有。 */
  list?: ImportListAttachment | null
}

export interface ImportPastePanelProps {
  /** 预览接口路径（POST，body `{ text, ...extraFields }`）。 */
  previewPath: string
  /**
   * 提交接口路径（POST，body 同上）。
   *
   * 不传就不渲染「确认导入」按钮：管理端新建词书面板把「建书 + 导入」合成一次「保存」
   * （2026-10-30 用户要求：新建时直接贴词条，别分两步），提交由页面自己做。
   */
  commitPath?: string
  /**
   * 受控文本。传了就以父组件为准（页面要在自己的「保存」里读到同一段粘贴内容）；
   * 不传则由组件自己存（加词页）。
   */
  text?: string
  onTextChange?: (value: string) => void
  /** 提交成功后的回调：页面在这里弹自己的成功提示、刷新数据或跳转。 */
  onCommitted?: (result: ImportCommitResult) => void
  /**
   * 追加到预览与提交 body 的字段。加词页传 `{ language: 'en', listName }`；
   * 书导入页不传（后端按契约只收 `text`）。
   */
  extraFields?: Record<string, unknown>
  /** 提交按钮文字；不传用加词页的 `vocab.add.commit`。 */
  commitLabel?: string
  /** 初始分页；加词页据 `?tab=` 决定落哪一页。 */
  defaultTab?: 'paste' | 'file'
  /** 文本域下方的一行说明（加词页的「填了词单名就把这一批词一起放进去」）。 */
  extraHint?: ReactNode
  /** 动作行最前面的额外控件（加词页的词单名输入框）。 */
  extraInput?: ReactNode
  /** 动作行末尾的次要动作（返回词库 / 返回词书）。 */
  secondaryAction?: ReactNode
  /** 提交成功卡片里的动作（去词库 / 返回词书）。 */
  doneAction?: ReactNode
  /**
   * 动作行常驻底部（书导入页：预览表再长，「确认导入」也不会掉出首屏）。
   * 加词页不开这个开关，保持原样。
   */
  stickyActions?: boolean
}

export function ImportPastePanel({
  previewPath,
  commitPath,
  text,
  onTextChange,
  onCommitted,
  extraFields,
  commitLabel,
  defaultTab = 'paste',
  extraHint,
  extraInput,
  secondaryAction,
  doneAction,
  stickyActions = false,
}: ImportPastePanelProps) {
  const { t } = useTranslation()
  const toast = useToast()
  const [tab, setTab] = useState<'paste' | 'file'>(defaultTab)
  const [innerText, setInnerText] = useState('')
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  const [result, setResult] = useState<ImportCommitResult | null>(null)
  const [busy, setBusy] = useState(false)

  /** 受控/非受控共用一份取值：父组件传了 `text` 就完全听父组件的。 */
  const value = text ?? innerText

  const reset = () => {
    setPreview(null)
    setResult(null)
  }

  const setValue = (next: string) => {
    if (text === undefined) setInnerText(next)
    onTextChange?.(next)
    reset()
  }

  // 受控模式下父组件会自己清空文本（管理端「保存」成功后），此时上一次的预览/结果必须一起收掉，
  // 否则界面上还挂着一段已经不存在的文本的预览。
  useEffect(() => {
    if (text === undefined) return
    setPreview(null)
    setResult(null)
  }, [text])

  const body = () => ({ text: value, ...(extraFields ?? {}) })

  const doPreview = async () => {
    if (!value.trim()) return
    setBusy(true)
    try {
      setPreview(await api.post<ImportPreview>(previewPath, body()))
    } catch (previewError) {
      toast.error((previewError as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const doCommit = async () => {
    if (!commitPath || !value.trim()) return
    setBusy(true)
    try {
      const committed = await api.post<ImportCommitResult>(commitPath, body())
      setResult(committed)
      onCommitted?.(committed)
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
    setValue(await file.text())
  }

  return (
    <>
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
          <p className="muted small">{t('vocab.add.fileHint')}</p>
        </div>
      )}

      <TextArea rows={10} value={value} placeholder={t('vocab.add.placeholder')}
        onChange={(event) => setValue(event.target.value)} />

      {extraHint && <p className="muted small" style={{ margin: '8px 0 0' }}>{extraHint}</p>}

      <div className={stickyActions ? 'import-actions is-sticky' : 'import-actions'}>
        {extraInput}
        <Button onClick={() => void doPreview()} busy={busy}>{t('vocab.add.preview')}</Button>
        {commitPath && (
          <Button variant="primary" onClick={() => void doCommit()} busy={busy}>
            {commitLabel ?? t('vocab.add.commit')}
          </Button>
        )}
        {secondaryAction}
      </div>

      {preview && !result && (
        <div className="card vocab-preview-panel" style={{ marginTop: 12 }}>
          <div className="row" style={{ gap: 16, flexWrap: 'wrap' }}>
            <span>{t('vocab.add.countTotal', { count: preview.counts.total })}</span>
            <span>{t('vocab.add.countCreate', { count: preview.counts.created })}</span>
            <span>{t('vocab.add.countDuplicate', { count: preview.counts.duplicated })}</span>
            <span>{t('vocab.add.countFailed', { count: preview.counts.failed })}</span>
          </div>
          {!preview.spellingChecked && <p className="muted small">{t('vocab.add.spellingOff')}</p>}
          {preview.skipped.length > 0 && (
            <details style={{ marginTop: 8 }} open>
              <summary>{t('vocab.add.skippedSummary', { count: preview.skipped.length })}</summary>
              <ul className="small">
                {preview.skipped.map((line) => (
                  <li key={`${line.line}-${line.text}`}>#{line.line} {line.text} — {line.reason}</li>
                ))}
              </ul>
            </details>
          )}
          {preview.cleanups.length > 0 && (
            <details style={{ marginTop: 8 }} open>
              <summary>{t('vocab.add.cleanupsSummary', { count: preview.cleanups.length })}</summary>
              <ul className="small">
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
              ? <p className="small">{t('vocab.add.listLimitSkipped', { name: result.list.name })}</p>
              : <p className="small">{t('vocab.add.addedToList', { name: result.list.name, added: result.list.added })}</p>
          )}
          {result.errors.length > 0 && (
            <ul className="small">
              {result.errors.map((error) => (
                <li key={`${error.line}-${error.raw}`}>#{error.line} {error.raw} — {error.reason}</li>
              ))}
            </ul>
          )}
          {doneAction}
        </div>
      )}
    </>
  )
}

export default ImportPastePanel
