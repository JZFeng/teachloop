// TODO(S1): 路由挂载（见 docs/process/impl-runbook-011-013.md）——目标路径 `/vocab/books/:bookId/review`
//（`?listId=` 可选，给了就跳过详情接口直接开会话）。挂在 App.tsx 的 `features.vocab` 分支里。
import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Button, InlineNote, Loading, PageHeader } from '../../ui'
import { createBookReviewSession, fetchBook, joinBook } from './books'
import { useBooksCopy } from './copy'
import '../vocab.css'
import './books.css'

interface BookSession {
  sessionId: string
  listId: string
}

/**
 * 副本复习入口（US-102 / AC-102.1、102.4）。
 *
 * 只做一件事，做完就把自己换掉：
 *   1. 有 `?listId=` 就直接用它（调用的页面已经知道副本词单 id）；
 *   2. 否则 `GET /books/{id}` 拿 `listId`；为 null（未加入）就先 `POST join`；
 *   3. `POST /api/vocab/review-sessions { listId }` 开会话，然后 `replace` 跳到
 *      既有的 `/vocab/review/session/{id}`。
 *
 * **刻意不复制 `FreeReviewPage` 的卡片**：卡片、翻面、小结、暂停/跳过都在那一页，
 * 词书只负责「凑出这轮复习的范围」。推荐卡、书详情、词单页三个入口共用这一条链路，
 * 所以「加入」与「开会话」只有这一处写法。
 */
export default function BookCopyReviewPage() {
  const bc = useBooksCopy()
  const { t } = useTranslation()
  const { bookId = '' } = useParams()
  const [params] = useSearchParams()
  const directListId = params.get('listId') ?? ''
  const navigate = useNavigate()
  const [bookName, setBookName] = useState('')
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  /**
   * 同一次「找 listId + 开会话」只做一遍。
   *
   * React StrictMode 在开发环境会把同一个 effect 执行两遍，而这里的副作用是**真的建一个
   * 复习会话**：跑两遍就是两个会话（一个没人用）。所以把工作本身缓存成 promise，
   * 两份 effect 共享它；只有重试（key 变了）才重新做。跳转仍由「还活着」那次负责——
   * 只做「跑过一次就 return」的守卫会让第一次的清理把唯一一次跳转也取消掉。
   */
  const workRef = useRef<{ key: string; promise: Promise<BookSession> } | null>(null)

  useEffect(() => {
    const key = `${bookId}|${directListId}|${String(reloadKey)}`
    let alive = true
    setError(null)

    const begin = async (): Promise<BookSession> => {
      let listId = directListId
      if (!listId) {
        if (!bookId) throw new Error(bc('vocab.books.review.missingBook'))
        const detail = await fetchBook(bookId)
        setBookName(detail.name)
        listId = detail.listId ?? (await joinBook(bookId)).listId
      }
      const sessionId = await createBookReviewSession(listId)
      return { sessionId, listId }
    }

    if (workRef.current?.key !== key) workRef.current = { key, promise: begin() }
    workRef.current.promise
      .then((started) => {
        if (!alive) return
        // replace：别让「返回」回到这个只闪一下的中间页。
        navigate(`/vocab/review/session/${started.sessionId}?from=${encodeURIComponent(started.listId)}`,
          { replace: true })
      })
      .catch((loadError) => { if (alive) setError(loadError as Error) })
    return () => { alive = false }
  }, [bookId, directListId, reloadKey, navigate, bc])

  const title = bookName || bc('vocab.books.review.title')

  if (error) {
    return (
      <>
        <PageHeader title={title} />
        <InlineNote tone="error">
          <div>{bc('vocab.books.review.failed')}</div>
          <div className="small" style={{ marginTop: 4 }}>{error.message}</div>
          <div className="row" style={{ gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
            <Button variant="primary" size="small" onClick={() => setReloadKey((value) => value + 1)}>
              {t('common.retry')}
            </Button>
            {bookId && (
              <Button size="small" onClick={() => navigate(`/vocab/books/${bookId}`)}>
                {bc('vocab.books.review.backToDetail')}
              </Button>
            )}
            <Button size="small" onClick={() => navigate('/vocab/lists')}>
              {bc('vocab.books.review.backToLists')}
            </Button>
          </div>
        </InlineNote>
      </>
    )
  }

  return (
    <>
      <PageHeader title={title} />
      <Loading label={bc('vocab.books.review.preparing')} />
    </>
  )
}
