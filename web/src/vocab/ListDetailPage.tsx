import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import {
  Alert,
  ArrowLeftOutlined,
  Button,
  DataTable,
  Empty,
  ErrorState,
  Input,
  Loading,
  Modal,
  PageHeader,
  TextArea,
  useConfirm,
  useListState,
  useToast,
} from '../ui'
import type { Column } from '../ui'
import {
  LIST_WORDS_PAGE,
  createReviewSession,
  fetchList,
  fetchListWords,
  fetchReviewWords,
  isPausedWord,
  removeWordFromList,
  splitForms,
} from './lists'
import type { ListWord, ReviewWords, VocabList } from './lists'
import './vocab.css'

/** 后端 `POST /lists/{id}/words` 一次最多 200 个词形，多了要分批。 */
const ADD_BATCH = 200
/** 单个词形的长度上限（后端会 400），超出的先在前端剔掉并提示。 */
const MAX_FORM_LENGTH = 64

/**
 * 词单详情（US-69 / AC-69.6、69.7、69.10）：名称、描述、逐词表、移出、编辑、删除。
 *
 * 三条刻意不做的（2026-10-25 拍板）：拖动排序、批量移出、词单内的「恢复复习」入口。
 * 暂停词仍留在表里并加「已暂停」chip，**不置灰**——暂停是全局进度，不是词单自己的状态。
 *
 * 数字口径：`total` 含暂停与已软删；chips 的「不在复习范围」= `paused + deleted`；
 * 按钮上的数 = `reviewable`（与 `review-words` 返回的 id 数同源）。
 */
export default function VocabListDetailPage() {
  const { id = '' } = useParams()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const confirm = useConfirm()
  const toast = useToast()

  const [list, setList] = useState<VocabList | null>(null)
  const [scope, setScope] = useState<ReviewWords | null>(null)
  const [words, setWords] = useState<ListWord[] | null>(null)
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [starting, setStarting] = useState(false)
  const [addOpen, setAddOpen] = useState(false)
  const [addText, setAddText] = useState('')
  const [addError, setAddError] = useState<string | null>(null)
  const [editOpen, setEditOpen] = useState(false)
  const [draftName, setDraftName] = useState('')
  const [draftDesc, setDraftDesc] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const { page, pageSize, setPage, setPageSize } = useListState(undefined, 20)

  const refresh = useCallback(() => setReloadKey((value) => value + 1), [])

  // 头部四个数：GET /lists/{id} + review-words 一次拿全（不要自己分页数）
  useEffect(() => {
    if (!id) return
    let alive = true
    setError(null)
    const load = async () => {
      const [detail, reviewInfo] = await Promise.all([fetchList(id), fetchReviewWords(id)])
      if (!alive) return
      setList(detail)
      setScope(reviewInfo)
    }
    void load().catch((loadError) => { if (alive) setError(loadError as Error) })
    return () => { alive = false }
  }, [id, reloadKey])

  // 逐词表：服务端分页（后端 size 上限 200）
  useEffect(() => {
    if (!id) return
    let alive = true
    setWords(null)
    fetchListWords(id, page, Math.min(pageSize, LIST_WORDS_PAGE))
      .then((value) => { if (alive) setWords(value.items ?? []) })
      .catch((loadError) => { if (alive) setError(loadError as Error) })
    return () => { alive = false }
  }, [id, page, pageSize, reloadKey])

  const total = scope?.total ?? list?.wordCount ?? 0
  const outOfScope = (scope?.paused ?? 0) + (scope?.deleted ?? 0)
  const reviewable = scope?.reviewable ?? 0

  const startReview = async () => {
    if (starting) return
    setStarting(true)
    try {
      const current = scope ?? await fetchReviewWords(id)
      const ids = current.wordIds.slice(0, current.sessionLimit)
      if (ids.length === 0) {
        toast.info(t('vocab.lists.reviewNoneHint'))
        return
      }
      if (current.reviewable > ids.length) {
        toast.info(t('vocab.lists.reviewCapped', {
          limit: ids.length, count: current.reviewable,
        }))
      }
      const sessionId = await createReviewSession(ids)
      // 会话页据 `from` 显示「本次范围」窄条，并在小结里给「返回词单」
      navigate(`/vocab/review/session/${sessionId}?from=${encodeURIComponent(id)}`,
        { state: { listName: list?.name } })
    } catch (startError) {
      toast.error((startError as Error).message)
    } finally {
      setStarting(false)
    }
  }

  /** 移出单个词：只删引用，不二次确认（AC-69.6 拍板）。 */
  const removeWord = async (word: ListWord) => {
    try {
      await removeWordFromList(id, word.id)
      toast.success(t('vocab.lists.removedToast'))
      if ((words?.length ?? 0) <= 1 && page > 1) setPage(page - 1)
      else refresh()
    } catch (removeError) {
      toast.error((removeError as Error).message)
    }
  }

  const addWords = async () => {
    const parsed = splitForms(addText)
    if (parsed.length === 0) {
      setAddError(t('vocab.lists.addWordsEmpty'))
      return
    }
    const forms = parsed.filter((form) => form.length <= MAX_FORM_LENGTH)
    const tooLong = parsed.length - forms.length
    if (forms.length === 0) {
      setAddError(t('vocab.lists.formsTooLong', { count: tooLong }))
      return
    }
    setBusy(true)
    setAddError(null)
    try {
      let added = 0
      let duplicated = 0
      for (let index = 0; index < forms.length; index += ADD_BATCH) {
        const batch = forms.slice(index, index + ADD_BATCH)
        const result = await api.post<{ added?: number; duplicated?: number }>(
          `/api/vocab/lists/${id}/words`, { forms: batch })
        added += result?.added ?? 0
        duplicated += result?.duplicated ?? 0
      }
      toast.success(t('vocab.lists.addWordsDone', { added, duplicated }))
      if (tooLong > 0) toast.info(t('vocab.lists.formsTooLong', { count: tooLong }))
      setAddOpen(false)
      setAddText('')
      refresh()
    } catch (saveError) {
      setAddError((saveError as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const saveListInfo = async () => {
    const name = draftName.trim()
    if (!name) {
      setFormError(t('vocab.lists.nameRequired'))
      return
    }
    if (name.length > 80) {
      setFormError(t('vocab.lists.nameTooLong'))
      return
    }
    setBusy(true)
    setFormError(null)
    try {
      // 描述显式传空串才是「清空」（后端：不传 = 保持原值）
      await api.put<VocabList>(`/api/vocab/lists/${id}`,
        { name, description: draftDesc.trim() })
      toast.success(t('vocab.lists.savedToast'))
      setEditOpen(false)
      refresh()
    } catch (saveError) {
      const status = (saveError as { status?: number }).status
      setFormError(status === 409
        ? t('vocab.lists.duplicateName', { name })
        : (saveError as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const deleteList = async () => {
    const name = list?.name ?? ''
    const ok = await confirm({
      title: t('vocab.lists.deleteTitle', { name }),
      // AC-69.3：确认条必须写明「不会删除词条与学习进度」
      content: t('vocab.lists.deleteBody', { count: total }),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
      danger: true,
    })
    if (!ok) return
    try {
      await api.del(`/api/vocab/lists/${id}`)
      toast.success(t('vocab.lists.deletedToast', { name }))
      navigate('/vocab/lists')
    } catch (deleteError) {
      toast.error((deleteError as Error).message)
    }
  }

  const openAdd = () => {
    setAddText('')
    setAddError(null)
    setAddOpen(true)
  }

  const openEdit = () => {
    setDraftName(list?.name ?? '')
    setDraftDesc(list?.description ?? '')
    setFormError(null)
    setEditOpen(true)
  }

  const openWord = (word: ListWord) => {
    navigate(`/vocab/words/by-form/${encodeURIComponent(word.form)}`)
  }

  const bandLabel = (word: ListWord) => {
    if (typeof word.band === 'number') return t('vocab.detail.familyBand', { count: word.band * 1000 })
    if (typeof word.band === 'string' && word.band.trim()) return word.band
    return '—'
  }

  const columns: Column<ListWord>[] = [
    {
      key: 'form',
      title: t('vocab.lists.columnWord'),
      value: (word) => word.form,
      render: (word) => (
        <span className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
          <span
            className="vocab-word"
            role="link"
            tabIndex={0}
            onClick={() => openWord(word)}
            onKeyDown={(event) => { if (event.key === 'Enter') openWord(word) }}
          >
            {word.form}
          </span>
          {isPausedWord(word) && <span className="chip warn">{t('vocab.lists.pausedWordChip')}</span>}
          {word.deleted === true && (
            <span className="chip err">{t('vocab.lists.deletedWordChip')}</span>
          )}
        </span>
      ),
    },
    {
      key: 'band',
      title: t('vocab.lists.columnBand'),
      value: (word) => bandLabel(word),
      render: (word) => bandLabel(word),
    },
    {
      key: 'addedAt',
      title: t('vocab.lists.columnAddedAt'),
      value: (word) => word.addedAt ?? '',
      render: (word) => (word.addedAt ? new Date(word.addedAt).toLocaleDateString() : '—'),
    },
    {
      key: 'actions',
      title: t('vocab.lists.columnActions'),
      value: () => '',
      render: (word) => (
        <Button size="small" variant="text" onClick={() => void removeWord(word)}>
          {t('vocab.lists.remove')}
        </Button>
      ),
    },
  ]

  if (error) return <ErrorState error={error} onRetry={refresh} />
  if (list === null) return <Loading label={t('common.loading')} />

  const name = list.name

  return (
    <>
      <div style={{ marginBottom: 8 }}>
        <Button variant="text" size="small" icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/vocab/lists')}>
          {t('vocab.lists.backToList')}
        </Button>
      </div>

      <PageHeader
        title={name}
        sub={list.description || undefined}
        actions={(
          <span className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <Button
              variant="primary"
              disabled={reviewable === 0 || starting}
              busy={starting}
              title={reviewable === 0 ? t('vocab.lists.reviewNoneHint') : undefined}
              onClick={() => void startReview()}
            >
              {t('vocab.lists.startReview', { count: reviewable })}
            </Button>
            <Button onClick={openAdd}>{t('vocab.lists.addWords')}</Button>
            <Button onClick={openEdit}>{t('vocab.lists.editTitle')}</Button>
            <Button onClick={() => void deleteList()}>{t('vocab.lists.deleteThisList')}</Button>
          </span>
        )}
      />

      <div className="row" style={{ gap: 6, marginBottom: 12, flexWrap: 'wrap' }}>
        <span className="chip">{t('vocab.lists.wordCount', { count: total })}</span>
        {outOfScope > 0
          ? <span className="chip">{t('vocab.lists.pausedChip', { count: outOfScope })}</span>
          : <span className="chip">{t('vocab.lists.noPausedChip')}</span>}
        <span className="chip">{t('vocab.lists.orderChip')}</span>
      </div>

      {reviewable === 0 && total > 0 && (
        <p className="muted sm">{t('vocab.lists.reviewNone')}</p>
      )}

      {total === 0 ? (
        <Empty>
          <div>{t('vocab.lists.emptyDetailTitle', { name })}</div>
          <div className="muted sm" style={{ margin: '6px 0 12px' }}>
            {t('vocab.lists.emptyDetailBody')}
          </div>
          <Button variant="primary" onClick={openAdd}>
            {t('vocab.lists.addWords')}
          </Button>
        </Empty>
      ) : (
        <DataTable
          rows={words ?? []}
          columns={columns}
          rowKey={(word) => word.id}
          page={page}
          pageSize={Math.min(pageSize, LIST_WORDS_PAGE)}
          total={total}
          loading={words === null}
          onPageChange={setPage}
          onPageSizeChange={setPageSize}
        />
      )}

      <p className="muted sm">{t('vocab.lists.removeHint')}</p>
      {outOfScope > 0 && <p className="muted sm">{t('vocab.lists.pausedNote')}</p>}

      <Modal
        open={addOpen}
        title={t('vocab.lists.addWordsTitle', { name })}
        onCancel={() => setAddOpen(false)}
        onOk={() => void addWords()}
        okText={t('vocab.lists.addWordsSubmit', { count: splitForms(addText).length })}
        cancelText={t('common.cancel')}
        confirmLoading={busy}
      >
        <p className="muted sm">{t('vocab.lists.addWordsHint')}</p>
        <TextArea
          rows={5}
          value={addText}
          placeholder={t('vocab.lists.addWordsPlaceholder')}
          onChange={(event) => { setAddText(event.target.value); setAddError(null) }}
        />
        {addError && <Alert type="error" showIcon message={addError} style={{ marginTop: 8 }} />}
      </Modal>

      <Modal
        open={editOpen}
        title={t('vocab.lists.editTitle')}
        onCancel={() => setEditOpen(false)}
        onOk={() => void saveListInfo()}
        okText={t('vocab.lists.save')}
        cancelText={t('common.cancel')}
        confirmLoading={busy}
      >
        <div style={{ marginBottom: 10 }}>
          <div className="muted sm">{t('vocab.lists.nameLabel')}</div>
          <Input
            value={draftName}
            maxLength={80}
            placeholder={t('vocab.lists.namePlaceholder')}
            onChange={(event) => { setDraftName(event.target.value); setFormError(null) }}
            onPressEnter={() => void saveListInfo()}
          />
        </div>
        <div style={{ marginBottom: 10 }}>
          <div className="muted sm">{t('vocab.lists.descLabel')}</div>
          <TextArea
            rows={2}
            value={draftDesc}
            maxLength={200}
            placeholder={t('vocab.lists.descPlaceholder')}
            onChange={(event) => setDraftDesc(event.target.value)}
          />
        </div>
        {formError && <Alert type="error" showIcon message={formError} style={{ marginTop: 8 }} />}
        <p className="muted sm" style={{ marginTop: 8 }}>{t('vocab.lists.editHint')}</p>
      </Modal>
    </>
  )
}
