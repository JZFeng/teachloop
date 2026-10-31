// 路由已挂：`/vocab/books/:id`，在 App.tsx 的 `features.vocab` 分支里（与 `/vocab/lists/:id` 同级）。
import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  ArrowLeftOutlined,
  Button,
  DataTable,
  ErrorState,
  InlineNote,
  Loading,
  PageHeader,
  Progress,
  Tag,
  useListState,
  useToast,
} from '../../ui'
import type { Column } from '../../ui'
import { BOOK_ENTRIES_PAGE, coveragePercent, fetchBook, fetchBookEntries, startBookReview } from './books'
import type { BookDetail, BookEntry } from './books'
import { useBooksCopy } from './copy'
import '../vocab.css'
import './books.css'

/**
 * 书详情（US-101 / US-102 / AC-101.2、101.3、102.1、102.4）。
 *
 * 版式按 mock `#book`：页头左侧「← 我的词单」，头部件给名称/简介/词数/覆盖率/我的加入进度，
 * 预览 50 词一页，**底部固定条只放主操作**「加入并开始背 / 继续背」（spec §5：
 * 不再放「先不加入」——它与页头返回重复，多一个按钮只会让主操作变弱）。
 *
 * 加入与开会话是一条链：`startBookReview()`（未加入先 join，再按 listId 开会话）。
 * 加入成功后跳既有 free-review 会话页，卡片/小结都复用那一套，不在这里重画。
 */
export default function BookDetailPage() {
  const bc = useBooksCopy()
  const { t } = useTranslation()
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()

  const [book, setBook] = useState<BookDetail | null>(null)
  const [entries, setEntries] = useState<BookEntry[] | null>(null)
  const [entriesError, setEntriesError] = useState<Error | null>(null)
  const [totalRows, setTotalRows] = useState(0)
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [starting, setStarting] = useState(false)
  // 预览固定 50/页（AC-101.3）：只借 useListState 的 page，页码进 URL，换页不丢位置。
  const { page, setPage } = useListState()

  const refresh = useCallback(() => setReloadKey((value) => value + 1), [])

  useEffect(() => {
    if (!id) return
    let alive = true
    setError(null)
    fetchBook(id)
      .then((value) => { if (alive) setBook(value) })
      .catch((loadError) => { if (alive) setError(loadError as Error) })
    return () => { alive = false }
  }, [id, reloadKey])

  useEffect(() => {
    if (!id) return
    let alive = true
    setEntries(null)
    setEntriesError(null)
    fetchBookEntries(id, page)
      .then((value) => {
        if (!alive) return
        setEntries(value.items)
        setTotalRows(value.total)
      })
      // 预览失败不能把整页打成错误页：书名/词数/覆盖率还在，「加入并开始背」照常可用。
      .catch((loadError) => { if (alive) setEntriesError(loadError as Error) })
    return () => { alive = false }
  }, [id, page, reloadKey])

  const start = async () => {
    if (starting) return
    setStarting(true)
    try {
      const started = await startBookReview(id)
      if (started.join) {
        toast.success(bc('vocab.books.detail.joinedToast', {
          created: started.join.created, duplicated: started.join.duplicated,
        }))
      }
      // 带上 `from=副本词单 id`：会话页据此显示「本次范围」窄条，并在小结里给「返回词单」。
      navigate(`/vocab/review/session/${started.sessionId}?from=${encodeURIComponent(started.listId)}`)
    } catch (startError) {
      toast.error((startError as Error).message)
      // join 可能已经成功、只是开会话失败：拉一次详情，让按钮如实变成「继续背」。
      refresh()
    } finally {
      setStarting(false)
    }
  }

  const columns: Column<BookEntry>[] = [
    {
      key: 'form',
      title: bc('vocab.books.detail.colForm'),
      value: (entry) => entry.form,
      render: (entry) => entry.form,
    },
    {
      key: 'status',
      title: bc('vocab.books.detail.colStatus'),
      value: (entry) => (entry.ready ? 'ready' : 'pending'),
      render: (entry) => (
        entry.ready
          ? <Tag tone="green">{bc('vocab.books.detail.readyTag')}</Tag>
          : <Tag tone="amber">{bc('vocab.books.detail.pendingTag')}</Tag>
      ),
    },
  ]

  const backToLists = (
    <div style={{ marginBottom: 8 }}>
      <Button variant="text" size="small" icon={<ArrowLeftOutlined />}
        onClick={() => navigate('/vocab/lists')}>
        {bc('vocab.books.detail.back')}
      </Button>
    </div>
  )

  if (error && !book) {
    return (
      <>
        {backToLists}
        <ErrorState error={error} onRetry={refresh} />
      </>
    )
  }
  if (book === null) return <Loading label={t('common.loading')} />

  const percent = coveragePercent(book.readyCount, book.wordCount)
  const coverage = bc('vocab.books.coverage', { ready: book.readyCount, total: book.wordCount })
  const wordCount = book.wordCount

  return (
    <>
      {backToLists}

      <PageHeader title={book.name} sub={book.description || undefined} />

      <div className="card">
        <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
          <span className="chip">
            {bc('vocab.books.detail.words')} · {wordCount}
          </span>
          <span className="chip">
            {bc('vocab.books.detail.ready')} · {percent}%
          </span>
          <span className="chip">
            {book.joined
              ? bc('vocab.books.detail.joinedProgress', { joined: book.joinedEntries, total: wordCount })
              : bc('vocab.books.detail.notJoined')}
          </span>
        </div>
        <div className="books-coverage">
          <Progress value={book.readyCount} max={wordCount} label={coverage} showText />
        </div>
        <div className="muted small">{coverage}</div>
      </div>

      <div className="card">
        <h2>
          {bc('vocab.books.detail.preview')}
          <small>{bc('vocab.books.detail.previewSub')}</small>
        </h2>
        {entriesError ? (
          <InlineNote tone="error">
            <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
              <span>{bc('vocab.books.detail.previewFailed')}</span>
              <Button size="small" onClick={refresh}>{t('common.retry')}</Button>
            </div>
          </InlineNote>
        ) : (
          <DataTable
            rows={entries ?? []}
            columns={columns}
            rowKey={(entry) => entry.id}
            page={page}
            pageSize={BOOK_ENTRIES_PAGE}
            pageSizeOptions={[BOOK_ENTRIES_PAGE]}
            total={totalRows || wordCount}
            loading={entries === null}
            onPageChange={setPage}
            empty={<div>{bc('vocab.books.detail.emptyEntries')}</div>}
          />
        )}
        <p className="muted small" style={{ marginTop: 8 }}>
          {bc('vocab.books.detail.total', { count: totalRows || wordCount })}
        </p>
      </div>

      <div className="books-sticky-bar">
        <span className="books-bar-note">{bc('vocab.books.detail.joinNote')}</span>
        <Button
          variant="primary"
          busy={starting}
          disabled={wordCount === 0}
          title={wordCount === 0 ? bc('vocab.books.detail.noWords') : undefined}
          onClick={() => void start()}
        >
          {book.joined ? bc('vocab.books.continue') : bc('vocab.books.join')}
        </Button>
      </div>
    </>
  )
}
