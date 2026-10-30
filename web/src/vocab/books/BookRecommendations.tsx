import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Button, InlineNote, Loading, Progress } from '../../ui'
import { coveragePercent, fetchRecommendedBooks } from './books'
import type { BookSummary } from './books'
import { useBooksCopy } from './copy'
import './books.css'

/**
 * 「我的词单」页词单列表下方的推荐区（US-104 / AC-104.1–104.4）。
 *
 * 三件事按顺序定下来：
 *   1. **位置**：词单列表下方（AC-104.1），不是页头、不是页面顶端——先看自己的东西，
 *      再看推荐，与电商「猜你喜欢」同位；
 *   2. **始终在**：词单为空时推荐区照常渲染（AC-104.2）。0 本书时**渲染一行提示**而不是
 *      整块隐藏：隐藏会让「为什么这里什么都没有」无从解释，也会让布局在两次渲染间跳动；
 *   3. **失败只影响本区块**（AC-104.4）：错误态吃掉的是这 2 张卡，不给页面抛错、不动
 *      词单列表与「新建词单」，就地给「重试」。
 *
 * 这个组件没有自己的路由：它由 `vocab/ListsPage.tsx` 直接渲染（路由挂载归 S1）。
 */
export default function BookRecommendations() {
  const bc = useBooksCopy()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [books, setBooks] = useState<BookSummary[] | null>(null)
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  // 失败原因不往界面上贴（后端 message 可能带内部措辞），只给一句「没加载出来」+ 重试。
  useEffect(() => {
    let alive = true
    setError(null)
    fetchRecommendedBooks()
      .then((value) => { if (alive) setBooks(value) })
      .catch((loadError) => { if (alive) setError(loadError as Error) })
    return () => { alive = false }
  }, [reloadKey])

  const openBook = (book: BookSummary) => navigate(`/vocab/books/${book.id}`)
  // 「加入并开始背 / 继续背」都进副本复习入口页：它负责「未加入则先加入」再开会话，
  // 卡片这里不重复写一遍加入逻辑（三个入口共用一条链路）。
  const startBook = (book: BookSummary) => navigate(`/vocab/books/${book.id}/review`)

  return (
    <section className="card" style={{ marginTop: 16 }} aria-labelledby="books-reco-title">
      <h2 id="books-reco-title">
        {bc('vocab.books.reco.title')}
        <small>{bc('vocab.books.reco.sub')}</small>
      </h2>

      {error ? (
        <InlineNote tone="error">
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <span>{bc('vocab.books.reco.failed')}</span>
            <Button size="small" onClick={() => setReloadKey((value) => value + 1)}>
              {t('common.retry')}
            </Button>
          </div>
        </InlineNote>
      ) : books === null ? (
        <Loading />
      ) : books.length === 0 ? (
        <p className="muted small">{bc('vocab.books.reco.empty')}</p>
      ) : (
        <div className="books-reco-grid">
          {books.map((book) => {
            const percent = coveragePercent(book.readyCount, book.wordCount)
            const coverage = bc('vocab.books.coverage', {
              ready: book.readyCount, total: book.wordCount,
            })
            return (
              <div className="books-reco-card" key={book.id}>
                <div className="books-reco-name">{book.name}</div>
                <div className="muted small">
                  {bc('vocab.books.wordCount', { count: book.wordCount })}
                  {book.level ? ` · ${book.level}` : ''}
                </div>
                <Progress value={book.readyCount} max={book.wordCount} label={coverage} />
                <div className="muted small">
                  {coverage}
                  {' · '}
                  {bc('vocab.books.coverageShort', { percent })}
                </div>
                <div className="books-reco-foot">
                  <Button variant="primary" size="small" onClick={() => startBook(book)}>
                    {book.joined ? bc('vocab.books.continue') : bc('vocab.books.join')}
                  </Button>
                  <Button variant="text" size="small" onClick={() => openBook(book)}>
                    {bc('vocab.books.reco.viewBook')}
                  </Button>
                </div>
              </div>
            )
          })}
        </div>
      )}
    </section>
  )
}
