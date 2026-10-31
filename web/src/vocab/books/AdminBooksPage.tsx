// 路由已挂：`/admin/vocab/books`，在 App.tsx 里与 `/admin/ai` 同级、守卫 `nav.vocab.books`
//（`?edit=<id>` 选中右侧编辑面板，`?edit=new` 是新建面板）。
// 2026-10-30 用户要求：**新建时直接贴词条，别分两步**——词条区长在面板里，
// 「保存」一次完成「建书 + 导入」；`/admin/vocab/books/:bookId/import` 只留重定向（旧链接不 404）。
import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, fmtDateTime } from '../../api/client'
import {
  Button,
  CloseOutlined,
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
  useEscapeClose,
  useListState,
  useToast,
} from '../../ui'
import type { Column } from '../../ui'
import { ImportPastePanel } from '../import/ImportPastePanel'
import type { ImportCommitResult } from '../import/ImportPastePanel'
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

/**
 * T592：语言只列系统真能服务的值（内容包与取词缺省都只有 en；zh/es 等语言档案 T433 落地后再放开）。
 * 级别是 CEFR 六档，留空表示未标注；目前只在推荐卡上展示，不参与筛选与推荐。
 */
const LANGUAGE_OPTIONS = [{ value: 'en', label: 'English (en)' }]
const CEFR_LEVELS = ['A1', 'A2', 'B1', 'B2', 'C1', 'C2']

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
 * 焦点是否落在可编辑控件里。
 *
 * 面板里全是表单（名称/简介/排序位/粘贴区），焦点在输入框时按 Esc 属于「输入法取消候选词」
 * 或原生控件的行为，不该顺带把面板关掉——关闭会丢没保存的元信息。
 */
function isEditingField(): boolean {
  if (typeof document === 'undefined') return false
  const active = document.activeElement as HTMLElement | null
  if (!active) return false
  if (active.isContentEditable) return true
  return active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.tagName === 'SELECT'
}

/**
 * 管理端词书列表 + 编辑面板（US-100 / US-105 / AC-100.1–100.5）。
 *
 * 三件按 mock `#admin` 定下来的事：
 *   1. 左侧全部词书（**含草稿**，草稿打标记），右侧编辑面板；两列在 1100 宽以下折成一列。
 *      左右版式的判据见 `docs/process/ui-review-checklist.md` B5（2026-10-30 立）：
 *      条目可枚举 / 反复原地编辑 / 保存后要对照列表——三条全中，所以这里用主从而不是上下。
 *      未选中任何书时右侧面板**整块不渲染**（2026-10-30 用户要求：原来那张空卡没用），
 *      表格独占整宽；空态也只剩一句话（里面原来的「新建词书」与页头按钮重复，已删）；
 *   2. 面板 = 元信息（名称/简介/语言/级别/排序位）+ 覆盖率 + **词条区** + 发布状态；
 *      词条区就是加词页那同一块粘贴/上传 + 预览组件（AC-100.6），**新建时也在**：
 *      2026-10-30 用户要求「新建时直接贴词条，别分两步」，所以「保存」一次完成
 *      「建书 + 导入」，不再跳到 `/admin/vocab/books/:bookId/import`（那条路由只留重定向）；
 *   3. **保存 / 下架常驻底部**（`.books-savebar`）：834 宽下表单一长，按钮掉到折叠线以下
 *      就等于没法保存（mock 的 `.savebar` 就是为这个加的）。
 *
 * 两条不允许出现的东西：**物理删除按钮**（AC-100.5：只能下架），以及**价格输入**
 * （v1 只允许 FREE，AC-105.2/105.4）。
 *
 * 2026-10-30（用户问「保存/发布后它也不消失，是 bug 吗」）：这**不是弹层**，是主从详情面板——
 * 开关只由 URL `?edit=` 决定，保存 / 发布都停在原地（保存后要看得见导入统计与刷新后的覆盖率，
 * 新建后导入失败还要留在面板上重试）。缺的从来不是「操作完自动关闭」，而是**出口**：
 * 这里补上右上角 ✕ 与 Esc（焦点在输入框里时 Esc 不生效），AC-100.8。
 */
export default function AdminBooksPage() {
  const bc = useBooksCopy()
  const { t } = useTranslation()
  const toast = useToast()
  const [params, setParams] = useSearchParams()
  const editKey = params.get('edit')

  const [books, setBooks] = useState<AdminBook[] | null>(null)
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [form, setForm] = useState<BookForm>(EMPTY_FORM)
  const [formKey, setFormKey] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  /** 面板里那段待导入的词条文本；新建与编辑共用，由底部「保存」一并落库。 */
  const [entryText, setEntryText] = useState('')
  const [importResult, setImportResult] = useState<ImportCommitResult | null>(null)
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

  /** 面板节点：窄屏折成一栏后要能把它滚进视野（见下面的 effect）。 */
  const panelRef = useRef<HTMLDivElement>(null)
  /** 上一次选中的面板 key（`new` 或书 id）：用来判断「是不是又回到了同一本」。 */
  const lastPanelKey = useRef<string | null>(null)
  /** 面板是不是已经处于「打开」状态：只在关 → 开那一下滚动。 */
  const panelWasOpen = useRef(false)

  /**
   * 打开某本 / 新建 / 关闭（`null`）面板。
   *
   * 关闭时**保留**还没提交的粘贴内容：误触 ✕ 或 Esc 不该把刚贴好的词条弄丢，重新打开同一本
   * 还能接着贴；只有真的换了目标（切到另一本、或切到新建）才清，免得把 A 书的词条贴进 B 书。
   * 保存成功后的自动跳转不走这里（要先显示导入结果）。
   */
  const selectBook = (key: string | null) => {
    if (key !== null && key !== lastPanelKey.current) {
      setEntryText('')
      setImportResult(null)
    }
    if (key !== null) lastPanelKey.current = key
    setFormError(null)
    setEditKey(key)
  }

  /**
   * 窄屏（`books.css` 的 1100 断点）两栏折成一栏，面板落到表格下方：点行之后不滚过去，
   * 屏幕上看起来就是「点了没反应」。只在「关 → 开」那一下滚；保存 / 发布（`?edit` 从 new 变 id、
   * 列表刷新）不重滚，否则刚在底部点完保存就被弹回面板顶部。
   */
  useEffect(() => {
    if (editKey === null) { panelWasOpen.current = false; return }
    if (books === null) return               // 面板还没渲染（还在 Loading），等列表回来再滚
    if (panelWasOpen.current) return
    panelWasOpen.current = true
    if (typeof window === 'undefined' || !window.matchMedia('(max-width: 1100px)').matches) return
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    panelRef.current?.scrollIntoView({ block: 'start', behavior: reduced ? 'auto' : 'smooth' })
  }, [editKey, books])

  // Esc = 收起面板（与右上角 ✕ 同一个动作）。焦点在输入框里时不关：面板里全是表单，
  // 误按一下就把没保存的元信息丢了；`canClose` 返回 false 时这次键击也不会穿透给别的弹层。
  useEscapeClose(editKey !== null, () => selectBook(null), {
    canClose: () => !busy && !isEditingField(),
  })

  const applyBook = (book: AdminBook) => {
    setBooks((previous) => {
      if (!previous) return [book]
      return previous.some((row) => row.id === book.id)
        ? previous.map((row) => (row.id === book.id ? book : row))
        : [...previous, book]
    })
  }

  const refresh = () => setReloadKey((value) => value + 1)

  /** 把面板里的词条文本提交给某本书（`POST /api/admin/vocab/books/{id}/entries`）。 */
  const importEntries = async (bookId: string) => {
    if (!entryText.trim()) return null
    const committed = await api.post<ImportCommitResult>(
      `/api/admin/vocab/books/${bookId}/entries`, { text: entryText })
    setImportResult(committed)
    setEntryText('')
    return committed
  }

  /**
   * 一次「保存」= 元信息落库 + 面板里贴的词条入书（2026-10-30 用户要求）。
   *
   * 新建时先建书再导入：书已经创建出来但导入失败，也必须把话说明白并把人留在那本书的面板上
   * （`setEditKey(created.id)` 之后粘贴内容还在，再点一次「保存」就是重试导入）。
   */
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
        try {
          const committed = await importEntries(created.id)
          toast.success(committed
            ? bc('vocab.books.admin.createdWithEntries', { name: created.name, created: committed.counts.created })
            : bc('vocab.books.admin.created', { name: created.name }))
        } catch (importError) {
          setFormError(bc('vocab.books.admin.importAfterCreateFailed', {
            message: (importError as Error).message,
          }))
        }
        setEditKey(created.id)
        // 建书响应里 wordCount/readyCount 都是 0，导入过就必须重新拉一次真值（列表与覆盖率都靠它）。
        refresh()
      } else if (selected) {
        const updated = await updateAdminBook(selected.id, { name, description, language, level, position })
        applyBook(updated)
        setForm(formOf(updated))
        try {
          const committed = await importEntries(updated.id)
          toast.success(committed
            ? bc('vocab.books.admin.savedWithEntries', { created: committed.counts.created })
            : bc('vocab.books.admin.saved'))
        } catch (importError) {
          setFormError(bc('vocab.books.admin.importFailed', { message: (importError as Error).message }))
        }
        refresh()
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

  const columns: Column<AdminBook>[] = [
    {
      key: 'name',
      title: bc('vocab.books.admin.colName'),
      value: (book) => book.name,
      sortable: true,
      // 2026-10-30（用户质疑）：书名下面原来还挂了一行 slug（`book` / `book-2`），
      // 那是后端自动生成的内部标识（AdminBookEndpoint 的 uniqueSlug：中文书名转不出 ascii
      // 就退化成 `book` 加后缀），前端任何入口都不改它、路由也不用它，摆在这里只是噪音。
      render: (book) => (
        <Button variant="text" size="small" onClick={() => selectBook(book.id)}>{book.name}</Button>
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
      // 一级动作只有「编辑」；发布/下架收进「⋯」（AC-25.1）。
      // 2026-10-30：原来的「导入词条」在这里，现在词条导入就长在编辑面板里，这一项不再需要。
      render: (book) => (
        <RowActions
          main={{
            key: 'edit',
            label: bc('vocab.books.admin.edit'),
            onClick: () => selectBook(book.id),
          }}
          items={[
            {
              key: 'publish',
              label: book.published ? bc('vocab.books.admin.unpublish') : bc('vocab.books.admin.publish'),
              onClick: () => {
                selectBook(book.id)
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

  // 空态只留一句话（2026-10-30 用户要求）：原来里面还有个「新建词书」按钮，
  // 和页头右上角那个是同一个动作，纯重复。
  const emptyList = <div>{bc('vocab.books.admin.emptyList')}</div>

  // 库里可能已有旧值（例如手输过 zh 或 b1）：原样列出来让人看见，保存时由后端白名单拦下。
  const languageChoices = LANGUAGE_OPTIONS.some((option) => option.value === form.language)
    ? LANGUAGE_OPTIONS
    : [{ value: form.language, label: form.language }, ...LANGUAGE_OPTIONS]
  const levelChoices = form.level && !CEFR_LEVELS.includes(form.level)
    ? [form.level, ...CEFR_LEVELS]
    : CEFR_LEVELS

  return (
    <>
      <PageHeader
        title={bc('vocab.books.admin.title')}
        sub={bc('vocab.books.admin.sub')}
        actions={(
          <Button variant="primary" onClick={() => selectBook(NEW_KEY)}>
            {bc('vocab.books.admin.new')}
          </Button>
        )}
      />

      {/* 2026-10-30：未选中时不渲染右侧面板（理由见组件头部第 1 条），表格独占整宽。 */}
      <div className={editKey === null ? 'books-admin-grid no-panel' : 'books-admin-grid'}>
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
            onRowClick={(book) => selectBook(book.id)}
            empty={emptyList}
          />
        </div>

        {editKey !== null && (
          <div className="card books-admin-panel" ref={panelRef}>
            {/* 标题行 + 关闭出口（AC-100.8）：主从面板也得能收起，此前只能手改 URL 里的 `edit`。 */}
            <div className="books-admin-panel-head">
              {selectedId && !selected ? null : (
                <h2>{creating
                  ? bc('vocab.books.admin.newTitle')
                  : bc('vocab.books.admin.editTitle', { name: selected?.name ?? '' })}
                </h2>
              )}
              {/* 2026-10-30（用户反馈 minor）：`text` 变体的图标与 14px 标题同色同重，第一眼看不见。
               * 改成有边框的次要按钮：仍是 UI 库的标准样式（不做手写按钮），靠边框把自己和标题分开。 */}
              <Button variant="default" size="small" disabled={busy}
                aria-label={bc('vocab.books.admin.close')}
                title={bc('vocab.books.admin.close')}
                onClick={() => selectBook(null)}>
                <CloseOutlined />
              </Button>
            </div>

            {selectedId && !selected ? (
              /* 链接里带着一个列表里没有的 id（书被别的会话下架/删掉、或手改 URL）：
               * 说清「找不到」，别渲染一张空表单让人白填。 */
              <p className="muted small">{bc('vocab.books.admin.notFound')}</p>
            ) : (
              <>
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
                  <Field label={bc('vocab.books.admin.language')} htmlFor="book-language"
                    hint={bc('vocab.books.admin.languageHint')}>
                    <select id="book-language" value={form.language}
                      onChange={(event) => setForm({ ...form, language: event.target.value })}>
                      {languageChoices.map((option) => (
                        <option key={option.value} value={option.value}>{option.label}</option>
                      ))}
                    </select>
                  </Field>
                  <Field label={bc('vocab.books.admin.level')} htmlFor="book-level"
                    hint={bc('vocab.books.admin.levelHint')}>
                    <select id="book-level" value={form.level}
                      onChange={(event) => setForm({ ...form, level: event.target.value })}>
                      <option value="">{bc('vocab.books.admin.levelEmpty')}</option>
                      {levelChoices.map((level) => (
                        <option key={level} value={level}>{level}</option>
                      ))}
                    </select>
                  </Field>
                </div>

                <Field label={bc('vocab.books.admin.position')} hint={bc('vocab.books.admin.positionHint')}>
                  <Input value={form.position} inputMode="numeric"
                    onChange={(event) => setForm({ ...form, position: event.target.value })} />
                </Field>

                {selected && (
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
                )}

                {/* Entries: the same paste/preview panel in both modes. A new book previews against
                    /draft/entries/preview, then Save creates the book and imports this text. */}
                <Field
                  label={bc('vocab.books.admin.entries')}
                  hint={creating
                    ? bc('vocab.books.admin.entriesCreateHint')
                    : bc('vocab.books.admin.entriesHint')}
                >
                  <ImportPastePanel
                    previewPath={selected
                      ? `/api/admin/vocab/books/${selected.id}/entries/preview`
                      : '/api/admin/vocab/books/draft/entries/preview'}
                    extraFields={selected ? undefined : { language: form.language.trim() || 'en' }}
                    text={entryText}
                    onTextChange={setEntryText}
                  />
                </Field>

                {importResult && (
                  <InlineNote tone={importResult.counts.failed > 0 ? 'warning' : 'success'}>
                    {bc('vocab.books.admin.importResult', {
                      created: importResult.counts.created,
                      duplicated: importResult.counts.duplicated,
                      failed: importResult.counts.failed,
                    })}
                    {importResult.errors.length > 0 && (
                      <ul className="small" style={{ margin: '6px 0 0', paddingLeft: 18 }}>
                        {importResult.errors.map((line) => (
                          <li key={`${line.line}-${line.raw}`}>#{line.line} {line.raw} — {line.reason}</li>
                        ))}
                      </ul>
                    )}
                  </InlineNote>
                )}

                {selected && (
                  <div className="books-status-row">
                    <span className="small">{bc('vocab.books.admin.statusRow')}</span>
                    {selected.published
                      ? <Tag tone="green">{bc('vocab.books.admin.published')}</Tag>
                      : <Tag tone="amber">{bc('vocab.books.admin.draft')}</Tag>}
                  </div>
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
        )}
      </div>
    </>
  )
}
