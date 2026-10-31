// 路由已挂：`/admin/vocab/books`，在 App.tsx 里与 `/admin/ai` 同级、守卫 `nav.vocab.books`
//（`?edit=<id>` 选中右侧编辑面板）。词条导入页是 `/admin/vocab/books/:bookId/import`。
import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { fmtDateTime } from '../../api/client'
import {
  Button,
  DataTable,
  ErrorState,
  Field,
  InlineNote,
  Input,
  Loading,
  PageHeader,
  Progress,
  RowActions,
  Tag,
  TextArea,
  useListState,
  useToast,
} from '../../ui'
import type { Column } from '../../ui'
import { createAdminBook, fetchAdminBooks, updateAdminBook } from './adminBooks'
import type { AdminBook } from './adminBooks'
import { coveragePercent } from './books'
import { useBooksCopy } from './copy'
import './books.css'

/** 面板表单：全部用字符串存，数字（排序位）在提交时解析，避免受控输入出现 NaN。 */
interface BookForm {
  name: string
  description: string
  language: string
  level: string
  position: string
}

const EMPTY_FORM: BookForm = { name: '', description: '', language: 'en', level: '', position: '0' }

function formOf(book: AdminBook): BookForm {
  return {
    name: book.name,
    description: book.description ?? '',
    language: book.language ?? 'en',
    level: book.level ?? '',
    position: String(book.position ?? 0),
  }
}

/** `?edit=new` = 新建；`?edit=<id>` = 编辑某本；没有 = 未选中。 */
const NEW_KEY = 'new'

/**
 * 管理端词书列表 + 编辑面板（US-100 / US-105 / AC-100.1–100.5）。
 *
 * 三件按 mock `#admin` 定下来的事：
 *   1. 左侧全部词书（**含草稿**，草稿打标记），右侧编辑面板；两列在 1100 宽以下折成一列；
 *   2. 编辑面板只改元信息（名称/简介/语言/级别/排序位）+ 覆盖率 + 发布状态 + 「导入词条」入口；
 *   3. **保存 / 下架常驻底部**（`.books-savebar`）：834 宽下表单一长，按钮掉到折叠线以下
 *      就等于没法保存（mock 的 `.savebar` 就是为这个加的）。
 *
 * 两条不允许出现的东西：**物理删除按钮**（AC-100.5：只能下架），以及**价格输入**
 * （v1 只允许 FREE，AC-105.2/105.4）。
 */
export default function AdminBooksPage() {
  const bc = useBooksCopy()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const toast = useToast()
  const [params, setParams] = useSearchParams()
  const editKey = params.get('edit')

  const [books, setBooks] = useState<AdminBook[] | null>(null)
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [form, setForm] = useState<BookForm>(EMPTY_FORM)
  const [formKey, setFormKey] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const { sort, page, pageSize, setSort, setPage, setPageSize } = useListState(undefined, 10)

  const creating = editKey === NEW_KEY
  const selectedId = editKey && editKey !== NEW_KEY ? editKey : null
  const selected = selectedId ? (books ?? []).find((book) => book.id === selectedId) ?? null : null

  useEffect(() => {
    let alive = true
    setError(null)
    fetchAdminBooks()
      .then((value) => { if (alive) setBooks(value) })
      .catch((loadError) => { if (alive) setError(loadError as Error) })
    return () => { alive = false }
  }, [reloadKey])

  // 切换选中项时把表单填成那本书。保存成功后由保存逻辑自己回填（`formKey` 已对齐），
  // 所以列表刷新不会把用户正在输的内容冲掉。
  useEffect(() => {
    if (editKey === null) { setFormKey(null); return }
    if (editKey === NEW_KEY) {
      if (formKey !== NEW_KEY) { setForm(EMPTY_FORM); setFormError(null); setFormKey(NEW_KEY) }
      return
    }
    const found = (books ?? []).find((book) => book.id === editKey)
    if (found && formKey !== editKey) {
      setForm(formOf(found))
      setFormError(null)
      setFormKey(editKey)
    }
  }, [editKey, books, formKey])

  const setEditKey = (key: string | null) => {
    setParams((previous) => {
      const next = new URLSearchParams(previous)
      if (key) next.set('edit', key)
      else next.delete('edit')
      return next
    }, { replace: true })
  }

  const applyBook = (book: AdminBook) => {
    setBooks((previous) => {
      if (!previous) return [book]
      return previous.some((row) => row.id === book.id)
        ? previous.map((row) => (row.id === book.id ? book : row))
        : [...previous, book]
    })
  }

  const refresh = () => setReloadKey((value) => value + 1)

  const save = async () => {
    setFormError(null)
    const name = form.name.trim()
    if (!name) { setFormError(bc('vocab.books.admin.nameRequired')); return }
    if (name.length > 80) { setFormError(bc('vocab.books.admin.nameTooLong')); return }
    const rawPosition = form.position.trim()
    const position = rawPosition === '' ? undefined : Number(rawPosition)
    if (position !== undefined && !Number.isInteger(position)) {
      setFormError(bc('vocab.books.admin.positionInvalid'))
      return
    }
    const description = form.description.trim() || null
    const level = form.level.trim() || null
    const language = form.language.trim() || undefined

    setBusy(true)
    try {
      if (creating) {
        const created = await createAdminBook({ name, description, language, level, position })
        applyBook(created)
        setForm(formOf(created))
        setFormKey(created.id)
        setEditKey(created.id)
        toast.success(bc('vocab.books.admin.created', { name: created.name }))
      } else if (selected) {
        const updated = await updateAdminBook(selected.id, { name, description, language, level, position })
        applyBook(updated)
        setForm(formOf(updated))
        toast.success(bc('vocab.books.admin.saved'))
      }
    } catch (saveError) {
      setFormError((saveError as Error).message)
    } finally {
      setBusy(false)
    }
  }

  /** 发布 / 下架只动 `published` 一个字段（AC-100.5）：不删数据、不清学生的词与副本。 */
  const togglePublished = async () => {
    if (!selected) return
    const next = !selected.published
    setBusy(true)
    try {
      const updated = await updateAdminBook(selected.id, { published: next })
      applyBook(updated)
      toast.success(bc(next ? 'vocab.books.admin.publishedToast' : 'vocab.books.admin.unpublishedToast',
        { name: updated.name }))
    } catch (toggleError) {
      toast.error((toggleError as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const openImport = (book: AdminBook) => navigate(`/admin/vocab/books/${book.id}/import`)

  const columns: Column<AdminBook>[] = [
    {
      key: 'name',
      title: bc('vocab.books.admin.colName'),
      value: (book) => book.name,
      sortable: true,
      render: (book) => (
        <div>
          <Button variant="text" size="small" onClick={() => setEditKey(book.id)}>{book.name}</Button>
          <div className="muted small">{book.slug}</div>
        </div>
      ),
    },
    {
      key: 'wordCount',
      title: bc('vocab.books.admin.colWords'),
      value: (book) => book.wordCount,
      sortable: true,
    },
    {
      key: 'coverage',
      title: bc('vocab.books.admin.colCoverage'),
      value: (book) => coveragePercent(book.readyCount, book.wordCount),
      sortable: true,
      render: (book) => {
        const percent = coveragePercent(book.readyCount, book.wordCount)
        return (
          <div>
            <span>{percent}%</span>
            <div className="muted small">
              {bc('vocab.books.coverage', { ready: book.readyCount, total: book.wordCount })}
            </div>
          </div>
        )
      },
    },
    {
      key: 'joinCount',
      title: bc('vocab.books.admin.colJoins'),
      value: (book) => book.joinCount,
      sortable: true,
    },
    {
      key: 'status',
      title: bc('vocab.books.admin.colStatus'),
      value: (book) => (book.published ? 'published' : 'draft'),
      render: (book) => (
        book.published
          ? <Tag tone="green">{bc('vocab.books.admin.published')}</Tag>
          : <Tag tone="amber">{bc('vocab.books.admin.draft')}</Tag>
      ),
    },
    {
      key: 'updatedAt',
      title: bc('vocab.books.admin.colUpdated'),
      value: (book) => book.updatedAt ?? '',
      sortable: true,
      render: (book) => fmtDateTime(book.updatedAt),
    },
    {
      key: 'actions',
      title: bc('vocab.books.admin.colActions'),
      value: () => '',
      // 一级动作只有「编辑」；导入与发布/下架收进「⋯」（AC-25.1）。
      render: (book) => (
        <RowActions
          main={{
            key: 'edit',
            label: bc('vocab.books.admin.edit'),
            onClick: () => setEditKey(book.id),
          }}
          items={[
            { key: 'import', label: bc('vocab.books.admin.importEntries'), onClick: () => openImport(book) },
            {
              key: 'publish',
              label: book.published ? bc('vocab.books.admin.unpublish') : bc('vocab.books.admin.publish'),
              onClick: () => {
                setEditKey(book.id)
                void updateAdminBook(book.id, { published: !book.published })
                  .then((updated) => {
                    applyBook(updated)
                    toast.success(bc(updated.published
                      ? 'vocab.books.admin.publishedToast'
                      : 'vocab.books.admin.unpublishedToast', { name: updated.name }))
                  })
                  .catch((toggleError) => toast.error((toggleError as Error).message))
              },
            },
          ]}
        />
      ),
    },
  ]

  if (error) return <ErrorState error={error} onRetry={refresh} />
  if (books === null) return <Loading label={t('common.loading')} />

  const percent = selected ? coveragePercent(selected.readyCount, selected.wordCount) : 0

  return (
    <>
      <PageHeader
        title={bc('vocab.books.admin.title')}
        sub={bc('vocab.books.admin.sub')}
        actions={(
          <Button variant="primary" onClick={() => setEditKey(NEW_KEY)}>
            {bc('vocab.books.admin.new')}
          </Button>
        )}
      />

      <div className="books-admin-grid">
        <div>
          <DataTable
            rows={books}
            columns={columns}
            rowKey={(book) => book.id}
            sort={sort}
            page={page}
            pageSize={pageSize}
            onSortChange={setSort}
            onPageChange={setPage}
            onPageSizeChange={setPageSize}
            onRowClick={(book) => setEditKey(book.id)}
            empty={(
              <>
                <div>{bc('vocab.books.admin.emptyList')}</div>
                <div className="row" style={{ gap: 8, justifyContent: 'center', marginTop: 10 }}>
                  <Button variant="primary" onClick={() => setEditKey(NEW_KEY)}>
                    {bc('vocab.books.admin.new')}
                  </Button>
                </div>
              </>
            )}
          />
        </div>

        <div className="card books-admin-panel">
          {editKey === null ? (
            <p className="muted small">{bc('vocab.books.admin.selectHint')}</p>
          ) : selectedId && !selected ? (
            /* 链接里带着一个列表里没有的 id（书被别的会话下架/删掉、或手改 URL）：
             * 说清「找不到」，别渲染一张空表单让人白填。 */
            <p className="muted small">{bc('vocab.books.admin.notFound')}</p>
          ) : (
            <>
              <h2>{creating
                ? bc('vocab.books.admin.newTitle')
                : bc('vocab.books.admin.editTitle', { name: selected?.name ?? '' })}
              </h2>

              <Field label={bc('vocab.books.admin.name')} required>
                <Input
                  value={form.name}
                  maxLength={80}
                  placeholder={bc('vocab.books.admin.namePlaceholder')}
                  onChange={(event) => { setForm({ ...form, name: event.target.value }); setFormError(null) }}
                />
              </Field>

              <Field label={bc('vocab.books.admin.description')}>
                <TextArea
                  rows={3}
                  value={form.description}
                  maxLength={300}
                  placeholder={bc('vocab.books.admin.descriptionPlaceholder')}
                  onChange={(event) => setForm({ ...form, description: event.target.value })}
                />
              </Field>

              <div className="books-field-row">
                <Field label={bc('vocab.books.admin.language')}>
                  <Input value={form.language} maxLength={8}
                    onChange={(event) => setForm({ ...form, language: event.target.value })} />
                </Field>
                <Field label={bc('vocab.books.admin.level')}>
                  <Input value={form.level} maxLength={24}
                    onChange={(event) => setForm({ ...form, level: event.target.value })} />
                </Field>
              </div>

              <Field label={bc('vocab.books.admin.position')} hint={bc('vocab.books.admin.positionHint')}>
                <Input value={form.position} inputMode="numeric"
                  onChange={(event) => setForm({ ...form, position: event.target.value })} />
              </Field>

              {selected && (
                <>
                  <div className="books-coverage">
                    <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                      <span className="small">
                        {bc('vocab.books.admin.coverageLine', {
                          percent, ready: selected.readyCount, total: selected.wordCount,
                        })}
                      </span>
                      <span className="muted small">
                        {bc('vocab.books.admin.entriesCount', { count: selected.wordCount })}
                      </span>
                    </div>
                    <Progress value={selected.readyCount} max={selected.wordCount}
                      label={bc('vocab.books.admin.coverageLine', {
                        percent, ready: selected.readyCount, total: selected.wordCount,
                      })} />
                  </div>

                  <Field label={bc('vocab.books.admin.entries')} hint={bc('vocab.books.admin.entriesHint')}>
                    <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                      <Button onClick={() => openImport(selected)}>
                        {bc('vocab.books.admin.importEntries')}
                      </Button>
                    </div>
                  </Field>

                  <div className="books-status-row">
                    <span className="small">{bc('vocab.books.admin.statusRow')}</span>
                    {selected.published
                      ? <Tag tone="green">{bc('vocab.books.admin.published')}</Tag>
                      : <Tag tone="amber">{bc('vocab.books.admin.draft')}</Tag>}
                  </div>
                </>
              )}

              {formError && <InlineNote tone="error">{formError}</InlineNote>}

              <div className="books-savebar">
                <Button variant="primary" busy={busy} onClick={() => void save()}>
                  {t('common.save')}
                </Button>
                {selected && (
                  <Button busy={busy} onClick={() => void togglePublished()}>
                    {selected.published
                      ? bc('vocab.books.admin.unpublish')
                      : bc('vocab.books.admin.publish')}
                  </Button>
                )}
              </div>

              <p className="muted small" style={{ marginTop: 8 }}>{bc('vocab.books.admin.noDelete')}</p>
            </>
          )}
        </div>
      </div>
    </>
  )
}
