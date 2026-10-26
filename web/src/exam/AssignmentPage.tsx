import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError, csrfHeaders, fmtDateTime } from '../api/client'
import { trackEvent } from '../api/events'
import type {
  AnswerSaveResponse,
  AttemptView,
  EntryView,
  ExamPaper,
  MyAttemptsView,
  OfferingView,
  SubmitResponse,
} from '../api/types'
import { RichTextEditor } from '../components/RichTextEditor'
import { Markdown } from '../components/Markdown'
import { ErrorState, Loading, PageHeader } from '../components/States'
import { Button, DownOutlined, InfoHint, InlineNote } from '../ui'
import { countWrittenWords, fmtScore } from './examUtils'
import './exam.css'

const LOCAL_DRAFT_PREFIX = 'teachloop.assignment.'

/** 本机草稿：富文本 HTML 与它的纯文本（字数/回退用）一起存，刷新后能原样恢复。 */
interface LocalDraft { text: string; html: string }

function readLocalDraft(offeringId: string): LocalDraft {
  try {
    const raw = window.localStorage.getItem(`${LOCAL_DRAFT_PREFIX}${offeringId}`) ?? ''
    if (!raw) return { text: '', html: '' }
    if (!raw.startsWith('{')) return { text: raw, html: '' } // 旧版只存纯文本
    const parsed = JSON.parse(raw) as Partial<LocalDraft>
    return {
      text: typeof parsed.text === 'string' ? parsed.text : '',
      html: typeof parsed.html === 'string' ? parsed.html : '',
    }
  } catch {
    return { text: '', html: '' }
  }
}

/** 旧版纯文本草稿 → 编辑器认的 HTML：先转义，再按空行分段、单换行变 <br>。 */
function textToHtml(text: string): string {
  const escaped = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return escaped
    .split(/\n{2,}/)
    .map((block) => `<p>${block.replace(/\n/g, '<br>')}</p>`)
    .join('')
}

/** 只有 HTML 时（换浏览器读到服务端富文本）用这个回填纯文本，保证字数与 contentText 不为空。 */
function htmlToText(html: string): string {
  if (!html) return ''
  const doc = new DOMParser().parseFromString(html, 'text/html')
  return (doc.body.textContent ?? '').replace(/\u00a0/g, ' ')
}

function writeLocalDraft(offeringId: string, draft: LocalDraft): void {
  try {
    window.localStorage.setItem(`${LOCAL_DRAFT_PREFIX}${offeringId}`, JSON.stringify(draft))
  } catch {
    // 本地存储不可用时静默跳过
  }
}

function clearLocalDraft(offeringId: string): void {
  try {
    window.localStorage.removeItem(`${LOCAL_DRAFT_PREFIX}${offeringId}`)
  } catch {
    // ignore
  }
}

/**
 * T190 / US-22：非试卷作业单页（说明 + 评分标准 + 提交区）。
 * 后端没有 rubric 接口，评分标准明确标「未接入」，不编造。
 */
export default function AssignmentPage() {
  const { offeringId = '' } = useParams<{ offeringId: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()

  const [entry, setEntry] = useState<EntryView | null>(null)
  const [paper, setPaper] = useState<ExamPaper | null>(null)
  const [offering, setOffering] = useState<OfferingView | null>(null)
  const [attempts, setAttempts] = useState<MyAttemptsView | null>(null)
  const [content, setContent] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [lastSaved, setLastSaved] = useState<string | null>(null)
  const [submitResult, setSubmitResult] = useState<SubmitResponse | null>(null)
  // 刚交完这一页的正文副本：提交后 load() 会清空草稿，回显先靠它，服务端字段回来后再以服务端为准。
  const [submittedSnapshot, setSubmittedSnapshot] = useState<{ html: string | null; text: string } | null>(null)
  const [richHtml, setRichHtml] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [uploading, setUploading] = useState(false)
  // 题目要求 / 评分标准是否展开：null = 还没手动点过，跟随「是否已交卷」。
  // 已交卷的学生进页面默认看到收起的两行标题（用户 2026-10-26 要求：做完以后页面只留重要信息）。
  const [promptsOverride, setPromptsOverride] = useState<boolean | null>(null)
  const [rubricOverride, setRubricOverride] = useState<boolean | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const load = useCallback(async () => {
    if (!offeringId) return
    setLoading(true)
    setError(null)
    try {
      const [loadedEntry, loadedAttempts, loadedOffering, loadedPaper] = await Promise.all([
        api.get<EntryView>(`/api/offerings/${offeringId}/entry`),
        api.get<MyAttemptsView>(`/api/offerings/${offeringId}/my-submissions`),
        api.get<OfferingView>(`/api/offerings/${offeringId}`),
        // 学生这次作业的题目要求就写在试卷题项里（ESSAY 题的题干），不含标准答案
        api.get<ExamPaper>(`/api/offerings/${offeringId}/paper`).catch(() => null),
      ])
      setPaper(loadedPaper)
      setEntry(loadedEntry)
      setAttempts(loadedAttempts)
      setOffering(loadedOffering)
      const latest = latestOf(loadedAttempts)
      const hasDraft = !!latest && latest.active && !latest.submittedAt && !latest.rejectedAt
      // 草稿正文优先用本机（可能比服务端新，且离线也丢不了）；本机没有时用服务端已保存的那份
      // （换浏览器 / 换端口 / 换设备时不至于格式全丢）。
      const local = hasDraft ? readLocalDraft(offeringId) : null
      const hasLocal = Boolean(local && (local.text.trim() || local.html.trim()))
      const serverHtml = latest?.contentHtml ?? ''
      const serverText = latest?.contentText ?? ''
      // 编辑器只认 HTML：本地草稿优先（可能比服务端新），旧版纯文本草稿当场转成段落。
      if (hasLocal) {
        const html = local!.html.trim() ? local!.html : textToHtml(local!.text)
        setRichHtml(html)
        setContent(local!.text.trim() ? local!.text : htmlToText(html))
      } else {
        setRichHtml(serverHtml)
        setContent(serverText || htmlToText(serverHtml))
      }
    } catch (loadError) {
      setError(loadError as Error)
    } finally {
      setLoading(false)
    }
  }, [offeringId])

  useEffect(() => {
    void load()
  }, [load])

  const latest = useMemo(() => latestOf(attempts), [attempts])
  const hasDraft = !!latest && latest.active && !latest.submittedAt && !latest.rejectedAt
  const rejected = !!latest?.rejectedAt
  const submitted = !rejected && (entry?.submitted ?? false)
  // 决策 025：未公布成绩时后端把三个分数都置空，所以 totalScore == null 就是「还没有结果」。
  // 「查看结果」只在这个口径下可点——学生点进去只会看到「待老师公布」，那不算结果。
  const graded = latest?.gradingState === 'DONE'
  const published = latest != null && latest.totalScore !== null
  const canStart = !!attempts?.canRetry && !hasDraft && !submitted
  const attemptsLeft = attempts ? Math.max(0, attempts.maxAttempts - attempts.attemptsUsed) : null

  // 草稿正文只存本机：后端没有「读取草稿正文」的接口；HTML 与纯文本都存。
  useEffect(() => {
    if (!hasDraft) return
    writeLocalDraft(offeringId, { text: content, html: richHtml })
  }, [content, richHtml, hasDraft, offeringId])

  const startAttempt = async () => {
    setBusy(true)
    setActionError(null)
    try {
      const path = attempts && attempts.attemptsUsed > 0
        ? `/api/offerings/${offeringId}/attempts`
        : `/api/offerings/${offeringId}/start`
      await api.post(path)
      await load()
    } catch (startFailure) {
      setActionError((startFailure as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const saveDraft = async () => {
    if (!hasDraft || busy) return
    setBusy(true)
    setActionError(null)
    try {
      const response = await api.put<AnswerSaveResponse>(`/api/offerings/${offeringId}/answers`, {
        contentText: content,
        contentHtml: richHtml.trim() ? richHtml : null,
        answers: [],
      })
      setLastSaved(response.serverTime)
    } catch (saveFailure) {
      if (saveFailure instanceof ApiError && saveFailure.status === 409) {
        setActionError(saveFailure.message)
        await load()
      } else {
        setActionError((saveFailure as Error).message)
      }
    } finally {
      setBusy(false)
    }
  }

  const submit = async () => {
    if (busy) return
    setBusy(true)
    setActionError(null)
    try {
      const response = await api.post<SubmitResponse>(`/api/offerings/${offeringId}/submissions`, {
        contentText: content,
        contentHtml: richHtml.trim() ? richHtml : null,
        answers: [],
      })
      setSubmitResult(response)
      setSubmittedSnapshot({ html: richHtml.trim() ? richHtml : null, text: content })
      trackEvent('submit', { offeringId, mode: 'rich' })
      clearLocalDraft(offeringId)
      await load()
    } catch (submitFailure) {
      if (submitFailure instanceof ApiError && submitFailure.status === 409) {
        setActionError(submitFailure.message)
        await load()
      } else {
        setActionError((submitFailure as Error).message)
      }
    } finally {
      setBusy(false)
    }
  }

  const uploadFile = async () => {
    if (!file || uploading) return
    setUploading(true)
    setActionError(null)
    try {
      const body = new FormData()
      body.append('file', file)
      const res = await fetch(`/api/offerings/${offeringId}/submissions/file`, {
        method: 'POST', credentials: 'include', headers: csrfHeaders(), body,
      })
      const payload = await res.json().catch(() => null)
      if (!res.ok) {
        throw new Error(payload?.detail || payload?.message || `HTTP ${res.status}`)
      }
      setSubmitResult(payload as SubmitResponse)
      trackEvent('submit', { offeringId, mode: 'file' })
      setFile(null)
      await load()
    } catch (uploadError) {
      setActionError((uploadError as Error).message)
    } finally {
      setUploading(false)
    }
  }

  if (loading) return <Loading label={t('exam.loadingAssignment')} />
  if (error) return <ErrorState error={error} onRetry={() => void load()} />
  if (!entry) return null

  const words = countWrittenWords(content)
  const pastDue = Date.parse(entry.dueAt) < Date.now()
  // 已提交正文：后端 /my-submissions 回传（刷新后仍在）；本次刚交的这份先用提交时的正文副本兜底。
  const submittedHtml = latest?.contentHtml ?? submittedSnapshot?.html ?? null
  const submittedText = latest?.contentText ?? submittedSnapshot?.text ?? null
  // 单 ESSAY 作业（新建作业只写一段题目要求）：不渲染 <ol> 序号，改成一段正文卡片。
  const singleEssay =
    paper && paper.items.length === 1 && paper.items[0].questionType === 'ESSAY'
      ? paper.items[0]
      : null
  // 交卷后题目要求 / 评分标准只剩回查价值：默认收起，标题整行可点；未交卷时默认展开，别多一次点击。
  const promptsOpen = promptsOverride ?? !submitted
  const rubricOpen = rubricOverride ?? !submitted

  const promptsCard = paper && paper.items.length > 0 ? (
    <SectionCard
      title={t('exam.assignmentPrompts')}
      open={promptsOpen}
      onToggle={() => setPromptsOverride(!promptsOpen)}
    >
      {singleEssay ? (
        <div>
          <div className="small muted">
            {t('exam.points', { points: singleEssay.points })}
            {singleEssay.sectionTitle ? ` · ${singleEssay.sectionTitle}` : ''}
          </div>
          {singleEssay.stem?.trim() ? (
            <Markdown className="md-body" text={singleEssay.stem} />
          ) : (
            <div className="inline-note">{t('exam.noStem')}</div>
          )}
        </div>
      ) : (
        <ol className="assign-prompts" style={{ paddingLeft: 20, margin: 0 }}>
          {paper.items.map((item) => (
            <li key={item.number} style={{ marginBottom: 14 }}>
              <div className="small muted">
                {t('exam.points', { points: item.points })}
                {item.sectionTitle ? ` · ${item.sectionTitle}` : ''}
              </div>
              <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.75 }}>
                {item.stem?.trim() || t('exam.noStem')}
              </div>
            </li>
          ))}
        </ol>
      )}
    </SectionCard>
  ) : null

  const rubricCard = (
    <SectionCard
      title={t('exam.assignmentRubric')}
      open={rubricOpen}
      onToggle={() => setRubricOverride(!rubricOpen)}
    >
      {entry.rubric && entry.rubric.trim() ? (
        <Markdown className="md-body" text={entry.rubric} />
      ) : (
        <InlineNote tone="warning">{t('exam.rubricMissing')}</InlineNote>
      )}
    </SectionCard>
  )

  // 作业说明只留一眼要用的事实：种类、截止、宽限期、还能交几次、是否算迟交。
  // 「没有倒计时、过了截止仍可交」那句留在后端 notice 里，页面不再重复（用户 2026-10-26 要求删）。
  const notesCard = (
    <div className="card">
      <h2>{t('exam.assignmentNotes')}</h2>
      {/* entry.notes 是「考试计时」的通用说明，作业页不适用，这里不显示 */}
      <div className="assign-meta">
        <span className="tag gray">{t('exam.assignmentKind')}</span>
        <span className="small muted">{t('exam.dueAtLabel')} {fmtDateTime(entry.dueAt)}</span>
        {offering && (
          <span className="grace-label small muted">
            {t('exam.graceLabel', { hours: offering.graceHours })}
            <InfoHint label={t('common.moreInfo')}>
              <p>{t('exam.graceHint')}</p>
            </InfoHint>
          </span>
        )}
        {attemptsLeft !== null && (
          <span className="tag blue">
            {t('exam.attemptsLeft', { left: attemptsLeft, max: attempts?.maxAttempts ?? 0 })}
          </span>
        )}
        {pastDue && <span className="tag amber">{t('exam.lateHint')}</span>}
      </div>
    </div>
  )

  // 我交上去的正文单独一张卡：交卷后顺序是 结果 → 题目要求▽ → 评分标准▽ → 我提交的内容 → 作业说明。
  const submittedContentCard = (
    <SubmittedContentCard
      html={submittedHtml}
      text={submittedText}
      attachmentName={latest?.attachmentName ?? submitResult?.attachmentName ?? null}
      attachmentHref={latest?.submissionId ? `/api/submissions/${latest.submissionId}/attachment` : null}
    />
  )

  return (
    <>
      <PageHeader
        title={entry.paperTitle || t('exam.assignmentTitle')}
        sub={`${t('exam.assignmentSub')} · ${t('exam.dueAtLabel')} ${fmtDateTime(entry.dueAt)}`}
        actions={(
          <button type="button" className="btn" onClick={() => navigate('/')}>{t('exam.backHome')}</button>
        )}
      />

      {/*
       * 已提交：先给「结果到哪一步了 + 我交了什么 + 下一步动作」，题目要求与评分标准退到后面折叠。
       * 用户 2026-10-26：这块信息乱——分数、状态、时间、两个按钮挤在一行，长解释悬在半空。
       */}
      {submitted && latest && (
        <div className="card submission-card">
          <h2>
            {t('exam.assignmentSubmitted')}
            {published
              ? <span className="tag green">{t('exam.scorePublished')}</span>
              : !graded && <span className="tag amber">{t('exam.pendingGrade')}</span>}
            {latest.lateBySeconds > 0 && <span className="tag amber">{t('exam.lateTag')}</span>}
            {latest.autoSubmitted && <span className="tag gray">{t('exam.submittedAuto')}</span>}
            {submitResult?.unreadable && <span className="tag red">{t('exam.unreadable')}</span>}
          </h2>
          <div className="exam-result-score">
            {published ? (
              <div className="score-big">
                {fmtScore(latest.totalScore)}
                <small> / {fmtScore(entry.totalPoints)}</small>
              </div>
            ) : (
              // 分数槽位：还没结果时这里只说一句「待老师公布」，规则收进 ⓘ（不再是行间那段悬空说明）
              <div className="score-pending">
                {t('exam.scorePending')}
                <InfoHint label={t('common.moreInfo')}>
                  <p>{t('exam.scorePendingHint')}</p>
                </InfoHint>
              </div>
            )}
            <div className="result-facts">
              {published && (
                <>
                  <div className="kv"><span>{t('exam.resultObjective')}</span><span className="mono">{fmtScore(latest.objectiveScore)}</span></div>
                  <div className="kv"><span>{t('exam.resultSubjective')}</span><span className="mono">{fmtScore(latest.subjectiveScore)}</span></div>
                </>
              )}
              {latest.submittedAt && (
                <div className="kv"><span>{t('exam.submittedAtLabel')}</span><span className="mono">{fmtDateTime(latest.submittedAt)}</span></div>
              )}
            </div>
          </div>
          <div className="result-actions">
            {/* 有结果才可点：未公布时学生点进去只看到「待老师公布」，等于死胡同（用户 2026-10-26 要求） */}
            <span title={published ? undefined : t('exam.viewResultLocked')}>
              <Button
                variant="primary"
                disabled={!published}
                onClick={() => navigate(`/feedback/${offeringId}`)}
              >
                {t('exam.viewResult')}
              </Button>
            </span>
            {attempts?.canRetry && (
              <Button disabled={busy} onClick={() => void startAttempt()}>
                {t('exam.retrySubmit')}
              </Button>
            )}
          </div>
        </div>
      )}

      {rejected && latest && (
        <div className="card">
          <h2>{t('exam.rejectedTitle')}</h2>
          <div className="exam-banner danger">
            {t('exam.rejectedReason', { reason: latest.rejectReason || t('exam.rejectedNoReason') })}
          </div>
          {canStart && (
            <button type="button" className="btn pri" disabled={busy} onClick={() => void startAttempt()}>
              {busy ? t('exam.working') : t('exam.resubmit')}
            </button>
          )}
          {!canStart && <InlineNote tone="warning">{t('exam.noAttemptsLeft')}</InlineNote>}
        </div>
      )}

      {/*
       * 交完卷：题目要求与评分标准收成两行标题（箭头向下，点开看），作业说明最后。
       * 还没交：题目要求与评分标准展开，作业说明夹在中间——和改版前一样。
       */}
      {submitted ? (
        <>
          {promptsCard}
          {rubricCard}
          {submittedContentCard}
          {notesCard}
        </>
      ) : (
        <>
          {promptsCard}
          {notesCard}
          {rubricCard}
        </>
      )}

      {hasDraft && !rejected && (
        <div className="card">
          <h2>{t('exam.assignmentSubmission')}</h2>
          <p className="small muted" style={{ marginTop: 0 }}>{t('exam.richHint')}</p>
          <RichTextEditor
            initialHtml={richHtml}
            placeholder={t('exam.richPlaceholder')}
            disabled={busy}
            expandable
            onChange={(html, text) => {
              setRichHtml(html)
              setContent(text)
            }}
          />
          <div className="wcount"><span className="wn">{words}</span> {t('exam.assignmentWordCount')}</div>
          <input
            ref={fileInputRef}
            type="file"
            accept=".pdf,.docx,.txt,.md"
            hidden
            onChange={(e) => {
              setFile(e.target.files?.[0] ?? null)
              e.target.value = ''
            }}
          />
          {file && (
            <div className="inline-note" style={{ marginTop: 10 }}>
              <div className="row" style={{ alignItems: 'center', gap: 10 }}>
                <b>{t('exam.fileSelected', { name: file.name })}</b>
                <span className="small muted">{Math.round(file.size / 1024)} KB</span>
                <Button variant="primary" busy={uploading} onClick={() => void uploadFile()}>
                  {t('exam.uploadSubmit')}
                </Button>
                <Button disabled={uploading} onClick={() => setFile(null)}>
                  {t('common.cancel')}
                </Button>
              </div>
              <div className="small muted" style={{ marginTop: 6 }}>{t('exam.fileHint')}</div>
            </div>
          )}
          <div className="row" style={{ marginTop: 12 }}>
            <Button busy={busy} onClick={() => void saveDraft()}>
              {t('exam.saveDraft')}
            </Button>
            <Button variant="primary" busy={busy} onClick={() => void submit()}>
              {t('exam.submitAssignment')}
            </Button>
            <Button disabled={busy || uploading} onClick={() => fileInputRef.current?.click()}>
              {t('exam.upload')}
            </Button>
            <div style={{ flex: 1 }} />
            <span className="small muted">
              {lastSaved ? t('exam.savedAt', { time: fmtDateTime(lastSaved) }) : t('exam.draftLocalOnly')}
            </span>
          </div>
        </div>
      )}

      {!hasDraft && !submitted && !rejected && (
        <div className="card">
          <h2>{t('exam.assignmentSubmission')}</h2>
          {canStart ? (
            <>
              <p className="small muted" style={{ marginTop: 0 }}>{t('exam.assignmentStartHint')}</p>
              <button type="button" className="btn pri" disabled={busy} onClick={() => void startAttempt()}>
                {busy ? t('exam.working') : t('exam.startAssignment')}
              </button>
            </>
          ) : (
            <InlineNote tone="warning" style={{ marginTop: 0 }}>{t('exam.noAttemptsLeft')}</InlineNote>
          )}
        </div>
      )}

      {actionError && <div className="exam-banner danger">{actionError}</div>}
    </>
  )
}

function latestOf(attempts: MyAttemptsView | null): AttemptView | null {
  const list = attempts?.attempts ?? []
  if (!list.length) return null
  return [...list].sort((a, b) => a.attemptNo - b.attemptNo)[list.length - 1]
}

/**
 * 可折叠的分区卡片（用户 2026-10-26）：标题整行可点，右侧向下箭头，展开后箭头翻转。
 * 交卷后题目要求 / 评分标准默认收起——学生已经做完，页面留给分数、提交正文与动作。
 * 未交卷时默认展开，避免为了看一眼题目再多点一次。
 */
function SectionCard({ title, open, onToggle, children }: {
  title: string
  open: boolean
  onToggle: () => void
  children: ReactNode
}) {
  return (
    <div className="card">
      <button type="button" className="fold-head" aria-expanded={open} onClick={onToggle}>
        <span className="fold-title">{title}</span>
        <DownOutlined className={open ? 'fold-caret is-open' : 'fold-caret'} aria-hidden="true" />
      </button>
      {open && <div className="fold-body">{children}</div>}
    </div>
  )
}

/**
 * 已提交正文单独一张卡（T248）：学生交完就在本页看到自己交了什么，不用再点按钮跳走。
 * html 是后端写入时已消毒的 content_html，可直接渲染；纯文本按原样换行。
 * 放在结果与两份参考资料之后——正文通常很长，不该把分数和动作挤到屏幕外。
 */
function SubmittedContentCard({ html, text, attachmentName, attachmentHref }: {
  html: string | null
  text: string | null
  attachmentName: string | null
  attachmentHref: string | null
}) {
  const { t } = useTranslation()
  const rich = html && html.trim() ? html : null
  const plain = text && text.trim() ? text : null
  if (!rich && !plain && !attachmentName) return null
  return (
    <div className="card">
      <h2>{t('exam.submittedContent')}</h2>
      {rich && <div className="submitted-body rich" dangerouslySetInnerHTML={{ __html: rich }} />}
      {!rich && plain && <div className="submitted-body">{plain}</div>}
      {attachmentName && attachmentHref && (
        <a className="btn" style={{ marginTop: 8 }} href={attachmentHref}>
          {t('exam.downloadAttachment')} · {attachmentName}
        </a>
      )}
    </div>
  )
}
