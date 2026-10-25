import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, fmtDateTime } from '../api/client'
import {
  Alert,
  Button,
  DataTable,
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
  LIST_MAX,
  countReviewableWords,
  createReviewSession,
  fetchLists,
  fetchReviewWords,
} from './lists'
import type { VocabList } from './lists'
import './vocab.css'

type Editor = 'new' | VocabList | null

/**
 * 我的词单（US-69 / AC-69.1–69.4、69.11）：列表页。
 *
 * 零一级导航：这一页从「我的词库」工具条的「词单」按钮进（AC-69.11）；侧栏仍是两项。
 * 每行直接给「开始复习（N）」，N = 排除暂停后的词数（AC-69.6 拍板）；0 词或全暂停时禁用。
 */
export default function VocabListsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const confirm = useConfirm()
  const toast = useToast()
  const [lists, setLists] = useState<VocabList[] | null>(null)
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  /** 每行「开始复习（N）」的 N：拿不到就不编数字，退回词数（后端未就绪时页面仍可用）。 */
  const [reviewable, setReviewable] = useState<Record<string, number>>({})
  const [startingId, setStartingId] = useState<string | null>(null)
  const [editor, setEditor] = useState<Editor>(null)
  const [draftName, setDraftName] = useState('')
  const [draftDesc, setDraftDesc] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const { sort, page, pageSize, setSort, setPage, setPageSize } = useListState(
    { key: 'updatedAt', dir: 'desc' },
    10,
  )

  const refresh = useCallback(() => setReloadKey((value) => value + 1), [])

  useEffect(() => {
    let alive = true
    setError(null)
    fetchLists()
      .then((value) => { if (alive) setLists(value) })
      .catch((loadError) => { if (alive) setError(loadError as Error) })
    return () => { alive = false }
  }, [reloadKey])

  // 词数与「排除暂停后的数量」不是一个数：逐行按 ?list= 维度取一次未暂停总数（size=1）
  useEffect(() => {
    if (!lists || lists.length === 0) return
    let alive = true
    void Promise.all(lists
      .filter((list) => (list.wordCount ?? 0) > 0)
      .map(async (list) => {
        const count = await countReviewableWords(list.id)
        if (!alive) return
        if (count !== null) {
          setReviewable((previous) => ({ ...previous, [list.id]: count }))
          return
        }
        // 计数查询失败时退回 review-words（与点按钮时用的同一个数），别拿 wordCount 冒充
        try {
          const scope = await fetchReviewWords(list.id)
          if (alive) setReviewable((previous) => ({ ...previous, [list.id]: scope.reviewable }))
        } catch {
          // 两个都没拿到：按钮仍可点，点击时会重新取范围并给出真实提示
        }
      }))
    return () => { alive = false }
  }, [lists])

  const reviewableOf = (list: VocabList) => reviewable[list.id] ?? (list.wordCount ?? 0)
  const atLimit = (lists?.length ?? 0) >= LIST_MAX

  const openCreate = () => {
    if (atLimit) {
      toast.info(t('vocab.lists.limitReached', { max: LIST_MAX }))
      return
    }
    setDraftName('')
    setDraftDesc('')
    setFormError(null)
    setEditor('new')
  }

  const openEdit = (list: VocabList) => {
    setDraftName(list.name)
    setDraftDesc(list.description ?? '')
    setFormError(null)
    setEditor(list)
  }

  const submit = async () => {
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
      if (editor === 'new') {
        const created = await api.post<VocabList>('/api/vocab/lists',
          { name, description: draftDesc.trim() || null })
        toast.success(t('vocab.lists.createdToast', { name: created?.name ?? name }))
      } else if (editor) {
        await api.put<VocabList>(`/api/vocab/lists/${editor.id}`,
          { name, description: draftDesc.trim() || null })
        toast.success(t('vocab.lists.savedToast'))
      }
      setEditor(null)
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

  const remove = async (list: VocabList) => {
    const ok = await confirm({
      title: t('vocab.lists.deleteTitle', { name: list.name }),
      // AC-69.3：确认条必须写明「不会删除词条与学习进度」
      content: t('vocab.lists.deleteBody', { count: list.wordCount ?? 0 }),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
      danger: true,
    })
    if (!ok) return
    try {
      await api.del(`/api/vocab/lists/${list.id}`)
      toast.success(t('vocab.lists.deletedToast', { name: list.name }))
      refresh()
    } catch (deleteError) {
      toast.error((deleteError as Error).message)
    }
  }

  /** 列表行直接开一轮复习：范围 = 这份词单里未暂停的词（AC-69.6 / AC-69.7）。 */
  const startReview = async (list: VocabList) => {
    if (startingId) return
    setStartingId(list.id)
    try {
      // 复习范围 = 未暂停、未软删的词，一次取全（不自己分页拼 id）
      const scope = await fetchReviewWords(list.id)
      const ids = scope.wordIds.slice(0, scope.sessionLimit)
      if (ids.length === 0) {
        toast.info(t('vocab.lists.reviewNoneHint'))
        return
      }
      if (scope.reviewable > ids.length) {
        toast.info(t('vocab.lists.reviewCapped', {
          limit: ids.length, count: scope.reviewable,
        }))
      }
      const sessionId = await createReviewSession(ids)
      navigate(`/vocab/review/session/${sessionId}?from=${encodeURIComponent(list.id)}`,
        { state: { listName: list.name } })
    } catch (startError) {
      toast.error((startError as Error).message)
    } finally {
      setStartingId(null)
    }
  }

  const columns: Column<VocabList>[] = [
    {
      key: 'name',
      title: t('vocab.lists.columnName'),
      value: (list) => list.name,
      sortable: true,
      render: (list) => (
        <div>
          <Button variant="text" size="small" onClick={() => navigate(`/vocab/lists/${list.id}`)}>
            {list.name}
          </Button>
          <div className="muted sm">{list.description || '—'}</div>
        </div>
      ),
    },
    {
      key: 'wordCount',
      title: t('vocab.lists.columnWords'),
      value: (list) => list.wordCount ?? 0,
      sortable: true,
      render: (list) => {
        const paused = Math.max(0, (list.wordCount ?? 0) - reviewableOf(list))
        return (
          <div>
            <span>{t('vocab.lists.wordCount', { count: list.wordCount ?? 0 })}</span>
            {paused > 0 && (
              <div className="muted sm">{t('vocab.lists.pausedCount', { count: paused })}</div>
            )}
          </div>
        )
      },
    },
    {
      key: 'updatedAt',
      title: t('vocab.lists.columnUpdated'),
      value: (list) => list.updatedAt ?? '',
      sortable: true,
      render: (list) => fmtDateTime(list.updatedAt),
    },
    {
      key: 'actions',
      title: t('vocab.lists.columnActions'),
      value: () => '',
      render: (list) => {
        const count = reviewableOf(list)
        return (
          <span className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
            <Button
              size="small"
              variant={count > 0 ? 'primary' : 'default'}
              disabled={count === 0 || startingId === list.id}
              busy={startingId === list.id}
              title={count === 0 ? t('vocab.lists.reviewNoneHint') : undefined}
              onClick={() => void startReview(list)}
            >
              {t('vocab.lists.startReview', { count })}
            </Button>
            <Button size="small" onClick={() => openEdit(list)}>{t('vocab.lists.edit')}</Button>
            <Button size="small" variant="danger" onClick={() => void remove(list)}>
              {t('vocab.lists.delete')}
            </Button>
          </span>
        )
      },
    },
  ]

  if (error) return <ErrorState error={error} onRetry={refresh} />
  if (lists === null) return <Loading label={t('common.loading')} />

  return (
    <>
      <PageHeader
        title={t('vocab.lists.title')}
        sub={t('vocab.lists.sub')}
        actions={(
          <Button variant="primary" disabled={atLimit} onClick={openCreate}
            title={atLimit ? t('vocab.lists.limitReached', { max: LIST_MAX }) : undefined}>
            {t('vocab.lists.newList')}
          </Button>
        )}
      />

      <DataTable
        rows={lists}
        columns={columns}
        rowKey={(list) => list.id}
        sort={sort}
        page={page}
        pageSize={pageSize}
        onSortChange={setSort}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
        empty={(
          <>
            <div>{t('vocab.lists.emptyBody')}</div>
            <div className="row" style={{ gap: 8, justifyContent: 'center', marginTop: 10 }}>
              <Button variant="primary" onClick={openCreate}>{t('vocab.lists.newList')}</Button>
              <Button onClick={() => navigate('/vocab/add?tab=paste')}>
                {t('vocab.lists.goAddWords')}
              </Button>
            </div>
          </>
        )}
      />

      <p className="muted sm">{t('vocab.lists.limitNote', { count: lists.length, max: LIST_MAX })}</p>

      <Modal
        open={editor !== null}
        title={editor === 'new' ? t('vocab.lists.newTitle') : t('vocab.lists.editTitle')}
        onCancel={() => setEditor(null)}
        onOk={() => void submit()}
        okText={editor === 'new' ? t('vocab.lists.create') : t('vocab.lists.save')}
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
            onPressEnter={() => void submit()}
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
        {formError && (
          <Alert type="error" showIcon message={formError} style={{ marginTop: 8 }} />
        )}
        <p className="muted sm" style={{ marginTop: 8 }}>
          {editor === 'new' ? t('vocab.lists.nameHint') : t('vocab.lists.editHint')}
        </p>
      </Modal>
    </>
  )
}
