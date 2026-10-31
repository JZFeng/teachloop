// 路由已挂：`/admin/vocab/books/:bookId/import`（App.tsx，守卫 `nav.vocab.books`）；
// 返回目标是 `/admin/vocab/books?edit=<id>`。
import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowLeftOutlined, Button, ErrorState, Loading, PageHeader, useToast } from '../../ui'
import { ImportPastePanel } from '../import/ImportPastePanel'
import { fetchAdminBook } from './adminBooks'
import type { AdminBook } from './adminBooks'
import { useBooksCopy } from './copy'
import '../vocab.css'
import './books.css'

/**
 * 管理端「导入词条」（US-100 / AC-100.3、100.6）。
 *
 * 这里是 013 最容易写错的一屏：**不许**再写一套粘贴解析与预览。粘贴/上传、格式清洗、
 * 拼写检查、跳过行提示全部是加词页那一个组件（`vocab/import/ImportPastePanel.tsx`），
 * 本页只做三件事：
 *   1. 把 `previewPath / commitPath` 换成这本书的两个端点；
 *   2. 提交按钮叫「确认导入」，动作行常驻底部（`.import-actions.is-sticky`，
 *      对应 mock 的 `.pager.sticky`：预览表再长也不把按钮顶出首屏）；
 *   3. 返回 `/admin/vocab/books?edit=<id>`，回去还停在刚才那本书的编辑面板上。
 */
export default function BookImportPage() {
  const bc = useBooksCopy()
  const { t } = useTranslation()
  const { bookId = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const [book, setBook] = useState<AdminBook | null>(null)
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    if (!bookId) { setError(new Error(bc('vocab.books.review.missingBook'))); return }
    let alive = true
    setError(null)
    fetchAdminBook(bookId)
      .then((value) => { if (alive) setBook(value) })
      .catch((loadError) => { if (alive) setError(loadError as Error) })
    return () => { alive = false }
  }, [bookId, reloadKey, bc])

  const backToBooks = () => navigate(`/admin/vocab/books?edit=${encodeURIComponent(bookId)}`)

  if (error) {
    return (
      <>
        <div style={{ marginBottom: 8 }}>
          <Button variant="text" size="small" icon={<ArrowLeftOutlined />}
            onClick={() => navigate('/admin/vocab/books')}>
            {bc('vocab.books.import.back')}
          </Button>
        </div>
        <ErrorState error={error} onRetry={() => setReloadKey((value) => value + 1)} />
      </>
    )
  }

  if (book === null) return <Loading label={t('common.loading')} />

  return (
    <>
      <div style={{ marginBottom: 8 }}>
        <Button variant="text" size="small" icon={<ArrowLeftOutlined />} onClick={backToBooks}>
          {bc('vocab.books.import.back')}
        </Button>
      </div>

      <PageHeader
        title={bc('vocab.books.import.title', { name: book.name })}
        sub={bc('vocab.books.import.sub')}
      />

      <div className="card">
        <p className="muted small">{bc('vocab.books.admin.entriesCount', { count: book.wordCount })}</p>
        <ImportPastePanel
          previewPath={`/api/admin/vocab/books/${bookId}/entries/preview`}
          commitPath={`/api/admin/vocab/books/${bookId}/entries`}
          commitLabel={bc('vocab.books.import.commit')}
          stickyActions
          secondaryAction={(
            <Button onClick={backToBooks}>{bc('vocab.books.import.back')}</Button>
          )}
          onCommitted={(result) => {
            toast.success(bc('vocab.books.import.done', {
              created: result.counts.created, duplicated: result.counts.duplicated,
            }))
            // 本页只显示「共 N 条」，先把新增数加上；回列表页会重新拉一次真值。
            setBook((previous) => (previous
              ? { ...previous, wordCount: previous.wordCount + result.counts.created }
              : previous))
          }}
          doneAction={(
            <Button onClick={backToBooks}>{bc('vocab.books.import.back')}</Button>
          )}
        />
      </div>
    </>
  )
}
