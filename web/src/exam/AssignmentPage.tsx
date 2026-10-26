import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
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
import { Button, InlineNote } from '../ui'
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
  const showResult = submitResult ?? null
  // 已提交正文：后端 /my-submissions 回传（刷新后仍在）；本次刚交的这份先用提交时的正文副本兜底。
  const submittedHtml = latest?.contentHtml ?? submittedSnapshot?.html ?? null
  const submittedText = latest?.contentText ?? submittedSnapshot?.text ?? null
  // 单 ESSAY 作业（新建作业只写一段题目要求）：不渲染 <ol> 序号，改成一段正文卡片。
  const singleEssay =
    paper && paper.items.length === 1 && paper.items[0].questionType === 'ESSAY'
      ? paper.items[0]
      : null

  return (
    <>
      <PageHeader
        title={entry.paperTitle || t('exam.assignmentTitle')}
        sub={`${t('exam.assignmentSub')} · ${t('exam.dueAtLabel')} ${fmtDateTime(entry.dueAt)}`}
        actions={(
          <button type="button" className="btn" onClick={() => navigate('/')}>{t('exam.backHome')}</button>
        )}
      />

      {paper && paper.items.length > 0 && (
        <div className="card">
          <h2>{t('exam.assignmentPrompts')}</h2>
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
        </div>
      )}

      <div className="card">
        <h2>{t('exam.assignmentNotes')}</h2>
        {/* notice 已包含「这是作业、无倒计时」的说明，别再重复一行 */}
        {entry.notice && <div className="inline-note">{entry.notice}</div>}
        {/* entry.notes 是「考试计时」的通用说明，作业页不适用，这里不显示 */}
        <div className="assign-meta" style={{ marginTop: 12 }}>
          <span className="tag gray">{t('exam.assignmentKind')}</span>
          <span className="small muted">{t('exam.dueAtLabel')} {fmtDateTime(entry.dueAt)}</span>
          {offering && (
            <span className="small muted">
              {t('exam.graceLabel', { hours: offering.graceHours })}
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

      <div className="card">
        <h2>{t('exam.assignmentRubric')}</h2>
        {entry.rubric && entry.rubric.trim() ? (
          <Markdown className="md-body" text={entry.rubric} />
        ) : (
          <InlineNote tone="warning">{t('exam.rubricMissing')}</InlineNote>
        )}
      </div>

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

      {showResult && (
        <div className="card">
          <h2>{t('exam.assignmentSubmitted')}</h2>
          <div className="row" style={{ alignItems: 'flex-end', gap: 24 }}>
            <div className="score-big">
              {showResult.objectiveScore == null ? (
                <span className="small muted">{t('exam.scorePending')}</span>
              ) : (
                <>
                  {fmtScore(showResult.objectiveScore)}
                  <small> / {fmtScore(showResult.totalPoints)}</small>
                </>
              )}
            </div>
            <div className="small muted">
              {showResult.objectiveScore == null ? (
                <div>{t('exam.scorePendingHint')}</div>
              ) : (
                <>
                  <div>{t('exam.resultObjective')}: {fmtScore(showResult.objectiveScore)}</div>
                  <div>{t('exam.resultSubjective')}: {t('exam.pendingGrade')}</div>
                </>
              )}
            </div>
          </div>
          <div className="row" style={{ marginTop: 12 }}>
            {showResult.unreadable
              ? <span className="tag red">{t('exam.unreadable')}</span>
              : <span className="tag amber">{t('exam.pendingGrade')}</span>}
            {showResult.lateBySeconds > 0 && <span className="tag amber">{t('exam.lateTag')}</span>}
            <button type="button" className="btn" onClick={() => navigate(`/feedback/${offeringId}`)}>
              {t('exam.viewResult')}
            </button>
          </div>
          <SubmittedContentSection
            html={submittedHtml}
            text={submittedText}
            attachmentName={showResult.attachmentName ?? null}
            attachmentHref={`/api/submissions/${showResult.submissionId}/attachment`}
          />
        </div>
      )}

      {!rejected && submitted && !showResult && latest && (
        <div className="card">
          <h2>{t('exam.assignmentSubmitted')}</h2>
          <div className="row" style={{ alignItems: 'flex-end', gap: 24 }}>
            <div className="score-big">
              {latest.totalScore == null ? (
                <span className="small muted">{t('exam.scorePending')}</span>
              ) : (
                <>
                  {fmtScore(latest.totalScore)}
                  <small> / {fmtScore(entry.totalPoints)}</small>
                </>
              )}
            </div>
            <div className="small muted">
              {latest.totalScore == null ? (
                <div>{t('exam.scorePendingHint')}</div>
              ) : (
                <>
                  <div>{t('exam.resultObjective')}: {fmtScore(latest.objectiveScore)}</div>
                  <div>{t('exam.resultSubjective')}: {fmtScore(latest.subjectiveScore)}</div>
                </>
              )}
              <div>{t('exam.submittedAtLabel')}: {fmtDateTime(latest.submittedAt)}</div>
            </div>
          </div>
          <div className="row" style={{ marginTop: 12 }}>
            {latest.totalScore != null && latest.gradingState === 'DONE'
              ? <span className="tag green">{t('exam.resultGraded')}</span>
              : <span className="tag amber">{t('exam.pendingGrade')}</span>}
            {latest.lateBySeconds > 0 && <span className="tag amber">{t('exam.lateTag')}</span>}
            {latest.autoSubmitted && <span className="tag gray">{t('exam.submittedAuto')}</span>}
            {attempts?.canRetry && (
              <button type="button" className="btn" disabled={busy} onClick={() => void startAttempt()}>
                {t('exam.retrySubmit')}
              </button>
            )}
            <button type="button" className="btn" onClick={() => navigate(`/feedback/${offeringId}`)}>
              {t('exam.viewResult')}
            </button>
          </div>
          <SubmittedContentSection
            html={submittedHtml}
            text={submittedText}
            attachmentName={latest.attachmentName}
            attachmentHref={latest.submissionId ? `/api/submissions/${latest.submissionId}/attachment` : null}
          />
        </div>
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
 * 已提交正文直接回显（T248）：学生交完就在本页看到自己交了什么，不用再点按钮跳走。
 * html 是后端写入时已消毒的 content_html，可直接渲染；纯文本按原样换行。
 */
function SubmittedContentSection({ html, text, attachmentName, attachmentHref }: {
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
    <div style={{ marginTop: 16 }}>
      <h3 style={{ margin: '0 0 6px' }}>{t('exam.submittedContent')}</h3>
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
