import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import {
  ArrowLeftOutlined,
  Button,
  Empty,
  ErrorState,
  Loading,
  Modal,
  PageHeader,
  TextArea,
  useToast,
} from '../ui'
import { VocabReviewCard } from './VocabReviewCard'
import AddToListsModal from './AddToListsModal'
import { prepareWords, type PrepareResult } from './prepare'
import './vocab.css'

interface Sense {
  pos?: string
  zh?: string
  en?: string
}

interface Example {
  en?: string
  zh?: string
}

interface FamilyMember {
  form: string
  freq: number
  head: boolean
}

interface Family {
  head: string
  band: number
  familyFreq: number
  matchedFolded: boolean
  members: FamilyMember[]
}

interface Source {
  kind: string
  label?: string
}

interface Content {
  senses?: Sense[]
  examples?: Example[]
  analysis?: string
  family?: string[]
  phonetic?: { uk?: string; us?: string }
}

interface Detail {
  inLibrary: boolean
  id: string | null
  form: string
  language: string
  contentStatus: string | null
  favorite: boolean
  paused: boolean
  tags: string[]
  state: string | null
  dueAt: string | null
  content: Content | null
  sources: Source[]
  family: Family | null
}

/**
 * T515 / AC-60.13：查词即生成的状态。
 *
 * <p>`PENDING` = 已为这个词形入队 AI 生成（不建词条），页面每 2 秒问一次；
 * `READY` = 内容到了，卡背直接渲染；`FAILED` = 生成失败或超时，给「重试」；
 * `DISABLED` = 后端关掉了查词即生成（`teachloop.vocab.lookup.generate-on-miss=false`），回落到「＋ 加入词库」。
 */
type LookupState = 'IDLE' | 'PENDING' | 'READY' | 'FAILED' | 'DISABLED'

interface LookupResponse {
  state: LookupState
  detail: Detail
}

/** 查词轮询上限：worker 失败会退避到 5 分钟，页面等太久没意义，到点给「重试」。 */
const LOOKUP_POLL_MS = 2000
const LOOKUP_GIVE_UP_MS = 120000

/**
 * 查词页（US-60 / T436）：和复习卡**同一张卡**，只是不带词表相关的东西——
 * 没有评分、没有「下一个」、没有「暂停复习」/快捷键行；底部只留「返回」。
 *
 * <p>在库的词可收藏；不在库的词给「＋ 加入词库」，加入后内容才异步生成。
 *
 * <p>T515（2026-10-26 用户 Bug：「查词为啥不直接接 AI 查询，然后跳转到 card back」）：不在库的词进页面
 * 就直接为这个词形触发一次 AI 生成（不建词条、不进复习队列），生成完自动渲染到卡背；「＋ 加入词库」
 * 仍然保留，但不再是看到内容的前提。
 */
export default function VocabWordDetailPage() {
  const { form = '' } = useParams()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const toast = useToast()
  const [detail, setDetail] = useState<Detail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [feedbackOpen, setFeedbackOpen] = useState(false)
  const [feedbackNote, setFeedbackNote] = useState('')
  // AC-69.5：查词页（word-details）是「加入词单」的三个入口之一
  const [addToListOpen, setAddToListOpen] = useState(false)
  const [prep, setPrep] = useState<PrepareResult | null>(null)
  const [retrying, setRetrying] = useState(false)
  // T515：查词即生成的状态（见 LookupState）；lookupStarted 保证同一个词只自动入队一次
  const [lookupState, setLookupState] = useState<LookupState>('IDLE')
  const lookupStarted = useRef(false)
  const lookupDeadline = useRef(0)
  // AC-60.1：查词页默认面**必须是反面**——查词 = 复习时选「不认识」，不认识就要看详细信息，
  // 所以进页面直接落到 review-back，看正面才按空格 / 点卡片（2026-10-26 用户定死，别改成 false）。
  const [flipped, setFlipped] = useState(true)

  const refresh = useCallback(() => setReloadKey((value) => value + 1), [])

  const refreshSilently = useCallback(async () => {
    const value = await api.get<Detail>(`/api/vocab/words/by-form/${encodeURIComponent(form)}`)
    setDetail(value)
  }, [form])

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    api.get<Detail>(`/api/vocab/words/by-form/${encodeURIComponent(form)}`)
      .then((value) => { if (alive) setDetail(value) })
      .catch((loadError) => { if (alive) setError(loadError as Error) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [form, reloadKey])

  // 空格翻面，与复习卡一致
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== ' ') return
      const target = event.target as HTMLElement | null
      if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
      event.preventDefault()
      setFlipped((value) => !value)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const wordId = detail?.id ?? null
  const preparing = !!detail?.inLibrary && detail?.contentStatus === 'PENDING'
  const contentFailed = !!detail?.inLibrary && detail?.contentStatus === 'FAILED'

  // T515：查词即生成——不在库的词进页面就为这个词形入队一次 AI 生成，结果只写全局内容。
  // 同一个词只自动触发一次（lookupStarted），换词（form 变）才重置。
  const askLookup = useCallback(async (retry: boolean) => {
    const result = await api.post<LookupResponse>('/api/vocab/words/lookup',
      { form, language: detail?.language ?? 'en', retry })
    if (result.detail) setDetail(result.detail)
    setLookupState(result.state)
  }, [form, detail?.language])

  useEffect(() => {
    lookupStarted.current = false
    lookupDeadline.current = 0
    setLookupState('IDLE')
  }, [form])

  useEffect(() => {
    if (!detail || detail.inLibrary || detail.content || lookupStarted.current) return
    lookupStarted.current = true
    lookupDeadline.current = Date.now() + LOOKUP_GIVE_UP_MS
    void askLookup(false).catch(() => setLookupState('FAILED'))
  }, [detail, askLookup])

  // 生成中：每 2 秒问一次（后端幂等，已有任务不会重复入队）；到点还没好就给「重试」
  useEffect(() => {
    if (lookupState !== 'PENDING') return
    if (!detail || detail.inLibrary || detail.content) return
    if (lookupDeadline.current === 0) lookupDeadline.current = Date.now() + LOOKUP_GIVE_UP_MS
    const timer = setInterval(() => {
      if (Date.now() > lookupDeadline.current) {
        setLookupState('FAILED')
        return
      }
      void askLookup(false).catch(() => setLookupState('FAILED'))
    }, LOOKUP_POLL_MS)
    return () => clearInterval(timer)
  }, [lookupState, detail?.inLibrary, detail?.content, askLookup])

  const retryLookup = useCallback(async () => {
    setRetrying(true)
    try {
      await askLookup(true)
    } catch {
      setLookupState('FAILED')
    } finally {
      setRetrying(false)
    }
  }, [askLookup])

  // T426：打开详情时只为这一个词按需准备内容
  useEffect(() => {
    if (!wordId || !preparing) return
    void prepareWords('WORD', 1, { wordId }).then(setPrep).catch(() => { /* 轮询会再试 */ })
  }, [wordId, preparing])

  // 数据准备中：每 2 秒看一次进度，好了自动出现内容
  useEffect(() => {
    if (!preparing || !wordId) return
    let alive = true
    const tick = async () => {
      try {
        const result = await prepareWords('WORD', 1, { wordId })
        if (!alive) return
        setPrep(result)
        await refreshSilently()
      } catch {
        // 单次失败不打断等待
      }
    }
    const timer = setInterval(() => void tick(), 2000)
    return () => { alive = false; clearInterval(timer) }
  }, [preparing, wordId, refreshSilently])

  const retryContent = useCallback(async () => {
    if (!wordId) return
    setRetrying(true)
    try {
      await api.post('/api/vocab/words/retry', { ids: [wordId] })
      await refreshSilently()
      setPrep(await prepareWords('WORD', 1, { wordId }))
    } catch (retryError) {
      toast.error((retryError as Error).message)
    } finally {
      setRetrying(false)
    }
  }, [wordId, refreshSilently, toast])

  const toggleFavorite = async () => {
    if (!detail?.id) return
    await api.put(`/api/vocab/words/${detail.id}/favorite`, { favorite: !detail.favorite })
    refresh()
  }

  const addToLibrary = async () => {
    const result = await api.post<{ created: number; duplicated: number }>('/api/vocab/words',
      { form: detail?.form ?? form, language: detail?.language ?? 'en' })
    toast.success(result.created > 0
      ? t('vocab.detail.addedToast', { word: detail?.form ?? form })
      : t('vocab.detail.alreadyToast'))
    refresh()
  }

  const submitFeedback = async () => {
    if (!detail?.id) return
    await api.post(`/api/vocab/words/${detail.id}/feedback`, { note: feedbackNote.trim() })
    setFeedbackOpen(false)
    setFeedbackNote('')
    toast.success(t('vocab.detail.feedbackThanks'))
  }

  if (loading) return <Loading label={t('common.loading')} />
  if (error) return <ErrorState error={error} onRetry={refresh} />
  if (!detail) return <Empty>{t('vocab.detail.notFound')}</Empty>

  // T515：不在库的词有三种卡背提示——AI 生成中 / 生成失败（给重试）/ 查词即生成被关掉（回落原提示）
  const lookupHint = lookupState === 'PENDING'
    ? t('vocab.detail.lookupPending')
    : lookupState === 'FAILED'
      ? t('vocab.detail.lookupFailed')
      : t('vocab.detail.notInLibraryHint')

  const emptyHint = preparing
    ? `${t('vocab.review.preparing')}${prep ? `（${t('vocab.review.preparingCount', { ready: prep.ready, total: prep.requested })}）` : ''}`
    : contentFailed
      ? t('vocab.review.contentFailed')
      : !detail.inLibrary
        ? lookupHint
        : t('vocab.detail.noContent')

  return (
    <>
      <PageHeader
        title={t('vocab.detail.title')}
        actions={detail.inLibrary
          ? (
            <Button size="small" onClick={() => setFeedbackOpen(true)}>
              {t('vocab.detail.feedback')}
            </Button>
          )
          : undefined}
      />

      <div className="review-stage review-stage--today">
        <div className="review-card-column">
          <VocabReviewCard
            word={{
              id: detail.id ?? detail.form,
              form: detail.form,
              language: detail.language,
              favorite: detail.favorite,
              content: detail.content,
            }}
            flipped={flipped}
            onFlip={() => setFlipped((value) => !value)}
            onToggleFavorite={detail.inLibrary ? () => void toggleFavorite() : undefined}
            onAdd={detail.inLibrary ? undefined : () => void addToLibrary()}
            onAddToList={() => setAddToListOpen(true)}
            emptyHint={emptyHint}
            // 2026-10-26 用户要求：查词页卡片正面与 review-front 同一版式（单词居中、音标另起一行居中、
            // 图标浮右上角）。反面由 `.is-centered:not(.is-flipped)` 自动回落左对齐，不受影响。
            centered
          />
          {contentFailed && (
            <div className="review-session-foot">
              <Button size="small" loading={retrying} onClick={() => void retryContent()}>
                {t('vocab.review.retryContent')}
              </Button>
            </div>
          )}
          {!detail.inLibrary && lookupState === 'FAILED' && (
            <div className="review-session-foot">
              <Button size="small" loading={retrying} onClick={() => void retryLookup()}>
                {t('vocab.detail.lookupRetry')}
              </Button>
            </div>
          )}
        </div>

        <Button
          size="large"
          className="review-exit"
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate(-1)}
        >
          {t('common.back')}
        </Button>
      </div>

      <AddToListsModal
        open={addToListOpen}
        form={detail.form}
        inLibrary={detail.inLibrary}
        onClose={() => setAddToListOpen(false)}
        onDone={() => { void refreshSilently() }}
      />

      <Modal open={feedbackOpen} title={t('vocab.detail.feedbackTitle')}
        onCancel={() => setFeedbackOpen(false)}
        onOk={() => void submitFeedback()} okText={t('vocab.detail.feedbackSubmit')}
        cancelText={t('common.cancel')}>
        <TextArea rows={4} value={feedbackNote} placeholder={t('vocab.detail.feedbackPlaceholder')}
          onChange={(event) => setFeedbackNote(event.target.value)} />
      </Modal>
    </>
  )
}
