import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { useAuth } from '../api/auth'
import { ApiError, api } from '../api/client'
import type { PaperDetail, PaperImportResult, PaperView } from '../api/types'
import { CheckOutlined, ErrorState, Loading, PageHeader, Tag, useConfirm, useToast } from '../ui'
import { RubricPreview } from '../components/RubricPreview'
import { summarizeRubric } from '../components/rubric'
import { errorMessage, fmtNum, TeacherPage } from './common'
import { readMarkdownFile } from './markdownImport'
import {
  PROGRESS_STAGES,
  WIZARD_STEPS,
  applyMetaHints,
  blankDraft,
  compareTarget,
  deleteSummary,
  draftFromDetail,
  essayRubricCheck,
  essayTabs,
  findMetaProblem,
  metaPayload,
  needsReplaceConfirm,
  normalizeDraft,
  previewRows,
  previewSummary,
  publishCheck,
  rubricBlocks,
  rubricGate,
  stageIndex,
  toNumberOrNull,
  type DraftV2,
  type EssayTab,
  type WizardStep,
} from './paperFlow'

/** 进度条最短展示时间：解析通常几百毫秒，太短会一闪而过，看不见「在读 / 在切题号」。 */
const MIN_PROGRESS_MS = 700

/**
 * 评分项自动保存的防抖窗口（2026-10-25 用户改主意：去掉「保存本题评分标准」按钮）。
 *
 * 为什么是 800ms 而不是每次按键都发：Vditor 的 input 回调按字触发，逐字 PUT 会把后端打成鼓点，
 * 而老师停下来想一想通常远长于 800ms。离开这一步（下一步 / 上一步 / 换 tab / 卸载）都会立刻冲刷。
 */
const RUBRIC_AUTOSAVE_MS = 800

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

/** 后端把定位信息放在 ApiError.detail（缺答案的题号 / 合计与目标），展示时一起给出来。 */
function describeError(err: unknown): string {
  if (err instanceof ApiError && err.detail) return `${err.message}（${err.detail}）`
  return errorMessage(err)
}

/** T341：与新建作业共用 Vditor 编辑器 chunk；只在需要时加载。 */
const VditorMarkdown = lazy(() => import('../components/VditorMarkdown'))

/**
 * 新建 / 编辑试卷（T185 重写，D-34 / AC-4.9、AC-4.24–4.29）。
 *
 * 四步：① 基本信息 ② 内容（Markdown 原文是唯一内容源，一个「生成考题」按钮）
 * ③ 评分项（写作题一题一 tab）④ 预览与发布。
 *
 * 与旧实现的区别（都是用户 2026-10-25 拍板的）：
 * - 不再手工建部分 / 逐题建题项 / 逐题补答案：原文解析出什么就是什么；
 * - 再次生成 = **替换重建**（确认框会数出会丢的三类界面内编辑）；
 * - 第 3 步（标准答案）删除；第 4 步取消参考范文输入；
 * - 新增命题人 / 审题人 / 目标总分；目标总分只做发布闸门，不覆盖自动合计。
 *
 * 纯判定都在 `paperFlow.ts`（有单测），这里只做取值、渲染与发请求。
 */
export default function PaperWizardPage() {
  const { t } = useTranslation('translation')
  const { paperId: paperIdParam } = useParams()
  const { me } = useAuth()
  const confirmDialog = useConfirm()
  const toast = useToast()

  const [draft, setDraft] = useState<DraftV2 | null>(null)
  const [detail, setDetail] = useState<PaperDetail | null>(null)
  const [ready, setReady] = useState(false)
  const [loadError, setLoadError] = useState<Error | null>(null)
  const [busy, setBusy] = useState<'meta' | 'generate' | 'publish' | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [note, setNote] = useState<{ tone: 'ok' | 'err' | 'warn' | 'info'; text: string } | null>(null)
  const [pendingFile, setPendingFile] = useState<File | null>(null)
  const [elapsed, setElapsed] = useState(0)
  const [tab, setTab] = useState(0)
  const [rubricEdits, setRubricEdits] = useState<Record<number, string>>({})
  /** 自动保存的状态机：idle（还没改）/ saving（等防抖或请求中）/ saved / error。 */
  const [rubricSave, setRubricSave] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle')
  const [rubricSavedAt, setRubricSavedAt] = useState('')
  /** 自动保存失败的具体原因（不塞 formError：那条红字属于「这一步过不去」，自动保存失败会自己重试）。 */
  const [rubricError, setRubricError] = useState<string | null>(null)

  const storageKey = 'teachloop.paperDraft.v2'
  const step = draft?.step ?? 1
  const locked = detail?.paper.status === 'PUBLISHED'

  const loadDetail = useCallback(async (id: string, keepStep: WizardStep = 1) => {
    const fresh = await api.get<PaperDetail>(`/api/papers/${id}`)
    setDetail(fresh)
    setDraft((current) => {
      // 已经打开过同一张卷：只刷新服务端字段，别把老师正在编辑的原文/步数冲掉。
      const base = current && current.paperId === id ? current : draftFromDetail(fresh, keepStep)
      return {
        ...base,
        paperId: id,
        title: base.title || fresh.paper.title,
        durationMinutes: base.durationMinutes,
        authorName: base.authorName || (fresh.paper.authorName ?? ''),
        reviewerName: base.reviewerName || (fresh.paper.reviewerName ?? ''),
        targetTotalPoints:
          base.targetTotalPoints !== ''
            ? base.targetTotalPoints
            : fresh.paper.targetTotalPoints === null || fresh.paper.targetTotalPoints === undefined
              ? ''
              : String(fresh.paper.targetTotalPoints),
        contentMarkdown: base.contentMarkdown || (fresh.contentMarkdown ?? ''),
      }
    })
  }, [])

  /**
   * 评分项自动保存（2026-10-25 用户改主意：删掉「保存本题评分标准」按钮）。
   *
   * 三条硬要求：
   * 1) 只发这一道题的 `rubricMarkdown`（AC-4.28② 的接口不变）；
   * 2) 连续输入只保留最后一次（防抖 `RUBRIC_AUTOSAVE_MS`），离开这一步立刻冲刷；
   * 3) 失败不静默丢字：失败的条目放回 pending，下次输入或「下一步」重试，错误当场显示。
   */
  const paperIdRef = useRef<string | null>(null)
  const rubricPending = useRef<Record<number, string>>({})
  const rubricTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    paperIdRef.current = draft?.paperId ?? null
  }, [draft?.paperId])

  const flushRubric = useCallback(async (): Promise<boolean> => {
    if (rubricTimer.current !== null) {
      clearTimeout(rubricTimer.current)
      rubricTimer.current = null
    }
    const paperId = paperIdRef.current
    const entries = Object.entries(rubricPending.current)
    if (paperId === null || entries.length === 0) return true
    rubricPending.current = {}
    setRubricSave('saving')
    try {
      for (const [number, text] of entries) {
        const saved = text.trim()
        await api.put(`/api/papers/${paperId}/items/${number}`, { rubricMarkdown: saved })
        // 就地更新详情，不整页刷新：第 2 步「替换重建」要数的已填 rubric、
        // 第 4 步的评分项预览都读 detail，刷新会把老师正在看的 tab 冲回去。
        setDetail((current) =>
          current === null
            ? current
            : {
                ...current,
                sections: current.sections.map((section) => ({
                  ...section,
                  items: section.items.map((item) =>
                    item.number === Number(number) ? { ...item, rubricMarkdown: saved } : item,
                  ),
                })),
              },
        )
      }
      setRubricSave('saved')
      setRubricSavedAt(new Date().toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hour12: false }))
      setRubricError(null)
      return true
    } catch (err) {
      // 放回去：不能让一次断网 / 404 把老师刚写的内容吞掉。
      rubricPending.current = { ...Object.fromEntries(entries), ...rubricPending.current }
      setRubricSave('error')
      setRubricError(describeError(err))
      return false
    }
  }, [])

  const queueRubricSave = useCallback(
    (number: number, text: string) => {
      rubricPending.current = { ...rubricPending.current, [number]: text }
      setRubricSave('saving')
      if (rubricTimer.current !== null) clearTimeout(rubricTimer.current)
      rubricTimer.current = setTimeout(() => {
        rubricTimer.current = null
        void flushRubric()
      }, RUBRIC_AUTOSAVE_MS)
    },
    [flushRubric],
  )

  // 离开页面（切路由、关标签）也把最后一段输入发出去；卸载后 setState 是空操作，不会报错。
  useEffect(
    () => () => {
      if (rubricTimer.current !== null) clearTimeout(rubricTimer.current)
      void flushRubric()
    },
    [flushRubric],
  )

  // 载入：有 paperId 就先取详情；新建模式优先恢复本地草稿（v2）。
  useEffect(() => {
    let alive = true
    setReady(false)
    setLoadError(null)
    setFormError(null)
    setNote(null)
    if (paperIdParam) {
      loadDetail(paperIdParam)
        .catch((err: unknown) => {
          if (alive) setLoadError(err instanceof Error ? err : new Error(String(err)))
        })
        .finally(() => {
          if (alive) setReady(true)
        })
      return () => {
        alive = false
      }
    }
    let restored: DraftV2 | null = null
    try {
      const raw = localStorage.getItem(storageKey)
      restored = raw === null ? null : normalizeDraft(JSON.parse(raw))
    } catch {
      restored = null
    }
    const next = restored ?? { ...blankDraft(), authorName: me?.displayName ?? '' }
    setDraft(next.authorName.trim() === '' && me ? { ...next, authorName: me.displayName } : next)
    if (next.paperId) {
      loadDetail(next.paperId, next.step).catch(() => {
        // 草稿指向的卷被删了：保留原文与 meta，让老师重新生成时再建卷。
        setDraft((current) => (current ? { ...current, paperId: null } : current))
      })
    }
    setReady(true)
    return () => {
      alive = false
    }
    // me 只在登录后从 null 变为对象一次，不需要触发重新载入
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [paperIdParam, loadDetail])

  // useAuth 的 me 可能比本页挂载晚一次：只在命题人还空着时补填，不覆盖老师已经改过的值。
  useEffect(() => {
    if (!draft || !me) return
    if (draft.paperId !== null || draft.authorName.trim() !== '') return
    setDraft({ ...draft, authorName: me.displayName })
  }, [me, draft])

  // 草稿自动保存；已发布的卷不再写草稿，避免下次打开停在只读页却提示「恢复了草稿」。
  useEffect(() => {
    if (!draft || detail?.paper.status === 'PUBLISHED') return
    try {
      localStorage.setItem(storageKey, JSON.stringify(draft))
    } catch {
      // 浏览器禁用 localStorage 时向导仍可在当前页面完成。
    }
  }, [draft, detail?.paper.status, storageKey])

  // 生成中的计时器：只用于「已用 N 秒 + 阶段」的不确定进度（AC-4.24⑤）。
  useEffect(() => {
    if (busy !== 'generate') return
    setElapsed(0)
    const timer = setInterval(() => setElapsed((value) => value + 200), 200)
    return () => clearInterval(timer)
  }, [busy])

  const update = (patch: Partial<DraftV2>) => setDraft((current) => (current ? { ...current, ...patch } : current))
  const setStep = (next: WizardStep) => update({ step: next })

  const summary = useMemo(() => previewSummary(detail), [detail])
  const rows = useMemo(() => previewRows(detail), [detail])
  const tabs = useMemo(() => essayTabs(detail), [detail])
  const activeTab: EssayTab | null = tabs[tab] ?? tabs[0] ?? null
  /** 界面上正在显示的这道题的文字（本地编辑优先，其次服务端值）。 */
  const activeRubric = activeTab === null ? '' : (rubricEdits[activeTab.number] ?? activeTab.rubricMarkdown)
  /**
   * 第 3 步每道写作题的分值体检（2026-10-25 用户拍板）：
   * 完全没填 → 拦住下一步；合计 ≠ 本题满分 → 只提醒；没写任何分值 → 只提醒（后端按均分判）。
   * 用「界面里正在编辑的文本」而不是服务端值：老师刚敲完就点下一步，检查的必须是他看到的字。
   */
  const rubricChecks = useMemo(
    () =>
      tabs.map((entry) => {
        const text = rubricEdits[entry.number] ?? entry.rubricMarkdown
        return essayRubricCheck(entry, text, summarizeRubric(text))
      }),
    [tabs, rubricEdits],
  )
  /** 当前 tab 的体检结果：编辑器下方那句话要跟着「没填 / 解析不出 / 正常」变。 */
  const activeCheck = activeTab === null ? null : (rubricChecks.find((entry) => entry.number === activeTab.number) ?? null)
  const target = draft ? toNumberOrNull(draft.targetTotalPoints) : null
  const actual = detail ? Number(detail.paper.totalPoints) : 0
  const legacyCriteria = detail
    ? Math.max(0, Math.round((Number(detail.paper.totalPoints) - summary.itemsTotal) * 100) / 100)
    : 0
  const check = publishCheck({
    itemCount: summary.itemCount,
    actual,
    target,
    missingCount: summary.missingCount,
    locked,
  })
  const verdict = compareTarget(target, actual)

  /** 建卷 / 改 meta。返回最新的 paperId（新建时是刚建出来的）。 */
  const persistMeta = async (): Promise<string | null> => {
    if (!draft) return null
    const problem = findMetaProblem(draft)
    if (problem) {
      setFormError(t(`teacher.wizard.errors.${problem}`))
      return null
    }
    if (draft.paperId) {
      const payload = metaPayload(draft)
      const current = detail?.paper
      const unchanged = current !== undefined
        && (current.authorName ?? null) === payload.authorName
        && (current.reviewerName ?? null) === payload.reviewerName
        && (current.targetTotalPoints === null || current.targetTotalPoints === undefined
          ? null
          : Number(current.targetTotalPoints)) === payload.targetTotalPoints
      if (unchanged) return draft.paperId
      const view = await api.put<PaperView>(`/api/papers/${draft.paperId}/meta`, payload)
      setDetail((existing) => (existing ? { ...existing, paper: view } : existing))
      setNote({ tone: 'ok', text: t('teacher.wizard.metaSaved') })
      return draft.paperId
    }
    const created = await api.post<PaperView>('/api/papers', {
      title: draft.title.trim(),
      defaultDurationMinutes: toNumberOrNull(draft.durationMinutes),
      ...metaPayload(draft),
    })
    update({ paperId: created.id })
    setNote({ tone: 'ok', text: t('teacher.wizard.metaCreated') })
    return created.id
  }

  const goNext = async () => {
    if (!draft) return
    setFormError(null)
    if (draft.step === 1) {
      const problem = findMetaProblem(draft)
      if (problem) {
        setFormError(t(`teacher.wizard.errors.${problem}`))
        return
      }
      if (draft.paperId) {
        setBusy('meta')
        try {
          await persistMeta()
        } catch (err) {
          setFormError(describeError(err))
          return
        } finally {
          setBusy(null)
        }
      }
      setStep(2)
      return
    }
    if (draft.step === 2) {
      if (!draft.paperId || summary.itemCount === 0) {
        setFormError(t('teacher.wizard.errors.generateFirst'))
        return
      }
      setStep(3)
      return
    }
    if (draft.step === 3) {
      // 已发布卷在本步只读：改不了评分项，也就不拦（否则老师被锁死在只读页里）。
      if (!locked) {
        // 先冲刷待保存的输入：下面这道闸门看的必须是已落库的内容，不能拿「还在防抖里」的字放行。
        const saved = await flushRubric()
        if (!saved) {
          toast.error(t('teacher.wizard.rubricAutosaveError'))
          return
        }
        const gate = rubricGate(rubricChecks)
        if (gate.blocked) {
          // 切到第一条拦人的题：空的和「一行都没解析出来」都算没填，但提示要分开说。
          const firstBad = tabs.findIndex(
            (entry) => gate.empty.includes(entry.number) || gate.unparsed.includes(entry.number),
          )
          if (firstBad >= 0) setTab(firstBad)
          const messages: string[] = []
          if (gate.empty.length > 0) {
            messages.push(t('teacher.wizard.errors.rubricRequired', { list: gate.empty.join('、') }))
          }
          if (gate.unparsed.length > 0) {
            messages.push(t('teacher.wizard.errors.rubricUnparsed', { list: gate.unparsed.join('、') }))
          }
          const text = messages.join(' ')
          setFormError(text)
          setNote(null)
          toast.error(text)
          return
        }
        const parts: string[] = []
        if (gate.mismatch.length > 0) {
          const items = gate.mismatch
            .map((entry) =>
              t('teacher.wizard.rubricMismatchItem', {
                number: entry.number,
                total: fmtNum(entry.total),
                points: fmtNum(entry.points),
                diff: fmtNum(Math.round(Math.abs(entry.points - entry.total) * 100) / 100),
              }),
            )
            .join('；')
          parts.push(t('teacher.wizard.rubricMismatch', { items }))
        }
        if (gate.noPoints.length > 0) {
          parts.push(t('teacher.wizard.rubricNoPoints', { list: gate.noPoints.join('、') }))
        }
        if (parts.length > 0) {
          // 只提醒，不拦：老师可能先写提纲、分值后补，拦下来反而逼他编数字。
          const text = parts.join(' ')
          setFormError(null)
          setNote({ tone: 'warn', text })
          toast.warning(text)
        } else {
          setNote(null)
        }
      }
      setStep(4)
    }
  }

  const goPrev = async () => {
    if (!draft || draft.step <= 1) return
    setFormError(null)
    // 回上一步也把最后一段输入发出去：第 2 步的「替换重建」确认框要数已填的 rubric。
    await flushRubric()
    setStep((draft.step - 1) as WizardStep)
  }

  const readFileIntoEditor = async (file: File) => {
    const result = await readMarkdownFile(file)
    if (!result.ok) {
      setFormError(t(`teacher.wizard.errors.import_${result.reason}`))
      return
    }
    update({ contentMarkdown: result.text })
    setPendingFile(null)
    setNote({ tone: 'info', text: t('teacher.wizard.fileReadIntoEditor', { name: file.name }) })
  }

  /** 「生成考题」：唯一的内容写入口（AC-4.24②）。replace=true 时是替换重建。 */
  const generate = async () => {
    if (!draft || locked) return
    const hasText = draft.contentMarkdown.trim() !== ''
    if (!hasText && !pendingFile) {
      setFormError(t('teacher.wizard.errors.importText'))
      return
    }
    const summaryBefore = deleteSummary(detail)
    if (needsReplaceConfirm(summaryBefore)) {
      const ok = await confirmDialog({
        title: t('teacher.wizard.replaceTitle'),
        danger: true,
        okText: t('teacher.wizard.replaceOk'),
        cancelText: t('teacher.wizard.replaceCancel'),
        content: (
          <div>
            <p style={{ marginTop: 0 }}>{t('teacher.wizard.replaceIntro', {
              sections: summaryBefore.sections,
              items: summaryBefore.items,
            })}</p>
            <ul style={{ paddingLeft: 20, margin: '6px 0' }}>
              <li>{t('teacher.wizard.replaceRubrics', { n: summaryBefore.rubrics })}</li>
              <li>{t('teacher.wizard.replaceExplanations', { n: summaryBefore.explanations })}</li>
              <li>{t('teacher.wizard.replaceCriteria', {
                n: summaryBefore.criteriaNames,
                points: fmtNum(summaryBefore.legacyCriteriaPoints),
              })}</li>
            </ul>
            <p className="hint" style={{ marginBottom: 0 }}>{t('teacher.wizard.replaceHint')}</p>
          </div>
        ),
      })
      if (!ok) return
    }

    setBusy('generate')
    setFormError(null)
    setNote(null)
    const startedAt = Date.now()
    try {
      const paperId = draft.paperId ?? (await persistMeta())
      if (!paperId) return
      let imported: PaperImportResult
      if (pendingFile) {
        const form = new FormData()
        form.append('file', pendingFile)
        imported = await api.postForm<PaperImportResult>(`/api/papers/${paperId}/import?replace=true`, form)
      } else {
        imported = await api.post<PaperImportResult>(`/api/papers/${paperId}/import/text?replace=true`, {
          text: draft.contentMarkdown,
        })
      }
      await loadDetail(paperId, 2)
      setDraft((current) =>
        current
          ? applyMetaHints(
              {
                ...current,
                paperId,
                contentMarkdown: imported.contentMarkdown ?? current.contentMarkdown,
              },
              imported.metaHints,
            )
          : current,
      )
      setPendingFile(null)
      setRubricEdits({})
      setNote({
        tone: 'ok',
        text: t('teacher.wizard.generateDone', {
          n: imported.imported,
          missing: imported.missingAnswers.length,
        }),
      })
    } catch (err) {
      setFormError(describeError(err))
    } finally {
      // 不确定进度至少露一下脸：否则「生成考题」看起来像没反应。
      const spent = Date.now() - startedAt
      if (spent < MIN_PROGRESS_MS) await sleep(MIN_PROGRESS_MS - spent)
      setBusy(null)
    }
  }

  const publish = async () => {
    if (!draft?.paperId) return
    setBusy('publish')
    setFormError(null)
    try {
      await api.post(`/api/papers/${draft.paperId}/publish`)
      await loadDetail(draft.paperId, 4)
      try {
        localStorage.removeItem(storageKey)
      } catch {
        // 隐私模式禁用 localStorage 时清理失败，不影响已完成的发布。
      }
      setNote({ tone: 'ok', text: t('teacher.wizard.publishSuccess') })
    } catch (err) {
      setFormError(describeError(err))
    } finally {
      setBusy(null)
    }
  }

  if (!ready) {
    return (
      <TeacherPage>
        <Loading label={t('teacher.common.loading')} />
      </TeacherPage>
    )
  }

  if (loadError) {
    return (
      <TeacherPage>
        <PageHeader title={t('teacher.wizard.title')} sub={t('teacher.wizard.sub')} />
        <ErrorState error={loadError} onRetry={() => window.location.reload()} />
      </TeacherPage>
    )
  }

  if (!draft) {
    return (
      <TeacherPage>
        <PageHeader title={t('teacher.wizard.title')} sub={t('teacher.wizard.sub')} />
        <ErrorState error={new Error(t('teacher.wizard.errors.noDraft'))} onRetry={() => window.location.reload()} />
      </TeacherPage>
    )
  }

  const stepLabels: Record<WizardStep, string> = {
    1: t('teacher.wizard.steps.basic'),
    2: t('teacher.wizard.steps.content'),
    3: t('teacher.wizard.steps.criteria'),
    4: t('teacher.wizard.steps.preview'),
  }
  const metaLocked = Boolean(draft.paperId)
  const publishBlocked = !check.ok && check.reason !== null

  // 提示条颜色跟着语气走：ok 绿、err 红、warn 琥珀（只提醒不拦），info 用默认强调色。
  const noteClass =
    note === null
      ? 'inline-note'
      : note.tone === 'ok'
        ? 'inline-note ok'
        : note.tone === 'err'
          ? 'inline-note err'
          : note.tone === 'warn'
            ? 'inline-note warn'
            : 'inline-note'

  return (
    <TeacherPage>
      <div className="breadcrumb">
        <Link to="/papers">{t('teacher.papers.title')}</Link> / <b>{draft.title || t('teacher.wizard.title')}</b>
      </div>

      <PageHeader
        title={draft.title || t('teacher.wizard.title')}
        sub={t('teacher.wizard.sub')}
        actions={
          <Link className="btn sm" to="/papers">
            {t('teacher.wizard.exit')}
          </Link>
        }
      />

      <div className="stepper">
        {WIZARD_STEPS.map((id) => (
          <div key={id} className={`st${step === id ? ' on' : ''}${step > id ? ' done' : ''}`}>
            <b>{step > id ? <CheckOutlined /> : id}</b>
            {stepLabels[id]}
          </div>
        ))}
      </div>

      {locked && <div className="inline-note">{t('teacher.wizard.lockedNote')}</div>}

      {note && (
        <div className={noteClass}>{note.text}</div>
      )}
      {formError && <div className="form-error">{formError}</div>}

      {step === 1 && (
        <div className="card">
          <h2>{t('teacher.wizard.step1Title')}</h2>
          <div className="form-grid">
            <label className="f">
              <b>{t('teacher.wizard.fieldTitle')}</b>
              <input
                type="text"
                value={draft.title}
                disabled={metaLocked}
                placeholder={t('teacher.wizard.fieldTitlePlaceholder')}
                onChange={(e) => update({ title: e.target.value })}
              />
              {metaLocked && <div className="hint">{t('teacher.wizard.titleReadOnly')}</div>}
            </label>
            <label className="f">
              <b>{t('teacher.wizard.fieldDuration')}</b>
              <input
                type="number"
                min={1}
                value={draft.durationMinutes}
                disabled={metaLocked}
                placeholder={t('teacher.wizard.fieldDurationPlaceholder')}
                onChange={(e) => update({ durationMinutes: e.target.value })}
              />
              <div className="hint">{t('teacher.wizard.durationHint')}</div>
            </label>
            <label className="f">
              <b>{t('teacher.wizard.authorName')}</b>
              <input
                type="text"
                value={draft.authorName}
                placeholder={me?.displayName ?? ''}
                onChange={(e) => update({ authorName: e.target.value })}
              />
              <div className="hint">{t('teacher.wizard.authorHint')}</div>
            </label>
            <label className="f">
              <b>{t('teacher.wizard.reviewerName')}</b>
              <input
                type="text"
                value={draft.reviewerName}
                onChange={(e) => update({ reviewerName: e.target.value })}
              />
              <div className="hint">{t('teacher.wizard.reviewerHint')}</div>
            </label>
            <label className="f">
              <b>{t('teacher.wizard.targetTotalPoints')}</b>
              <input
                type="number"
                min={1}
                step="0.5"
                value={draft.targetTotalPoints}
                placeholder={t('teacher.wizard.targetTotalPointsPlaceholder')}
                onChange={(e) => update({ targetTotalPoints: e.target.value })}
              />
              <div className="hint">{t('teacher.wizard.targetTotalPointsHint')}</div>
            </label>
          </div>
          <div className="inline-note">{t('teacher.wizard.noClassNote')}</div>
        </div>
      )}

      {step === 2 && (
        <>
          <div className="card">
            <div className="row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
              <h2>{t('teacher.wizard.step2Title')}</h2>
              <span className="small muted">{t('teacher.wizard.editorSourceHint')}</span>
            </div>
            <div className="hint" style={{ marginBottom: 10 }}>{t('teacher.wizard.editorHint')}</div>
            <Suspense fallback={<div className="md-editor md-editor-loading">{t('editor.loading')}</div>}>
              <VditorMarkdown
                id="paper-content"
                rows={14}
                value={draft.contentMarkdown}
                onChange={(next) => update({ contentMarkdown: next })}
                placeholder={t('teacher.wizard.editorPlaceholder')}
                disabled={locked || busy === 'generate'}
                expandable
                heading={t('teacher.wizard.step2Title')}
              />
            </Suspense>

            <div className="inline-form" style={{ alignItems: 'center', marginTop: 12 }}>
              <label className="f" style={{ margin: 0, flex: '1 1 320px' }}>
                <b>{t('teacher.wizard.fileLabel')}</b>
                <input
                  type="file"
                  accept=".docx,.md,.markdown,.txt,.xlsx"
                  disabled={locked || busy === 'generate'}
                  onChange={(e) => {
                    const file = e.target.files?.[0]
                    e.target.value = ''
                    if (!file) return
                    setFormError(null)
                    setNote(null)
                    if (/\.(md|markdown|txt)$/i.test(file.name)) {
                      void readFileIntoEditor(file)
                    } else {
                      setPendingFile(file)
                      setNote({ tone: 'info', text: t('teacher.wizard.filePending', { name: file.name }) })
                    }
                  }}
                />
                <div className="hint">{t('teacher.wizard.fileHint')}</div>
              </label>
              <button
                type="button"
                className="btn pri"
                style={{ marginBottom: 0 }}
                disabled={locked || busy === 'generate'}
                onClick={() => void generate()}
              >
                {busy === 'generate' ? t('teacher.wizard.generating') : t('teacher.wizard.generate')}
              </button>
            </div>
            {pendingFile && (
              <div className="hint">
                {t('teacher.wizard.filePending', { name: pendingFile.name })}{' '}
                <button type="button" className="btn sm" onClick={() => setPendingFile(null)}>
                  {t('teacher.common.cancel')}
                </button>
              </div>
            )}
          </div>

          {busy === 'generate' && (
            <div className="card">
              <h2>{t('teacher.wizard.progressTitle')}</h2>
              <div className="hint">{t('teacher.wizard.progressHint')}</div>
              <div className="progress">
                <i />
              </div>
              <div className="progress-stages">
                {PROGRESS_STAGES.map((stage, index) => (
                  <span key={stage} className={index < stageIndex(elapsed) ? 'done' : index === stageIndex(elapsed) ? 'on' : ''}>
                    {index < stageIndex(elapsed) && <CheckOutlined />} {t(`teacher.wizard.stages.${stage}`)}
                  </span>
                ))}
                <span className="mono">{t('teacher.wizard.progressElapsed', { seconds: (elapsed / 1000).toFixed(1) })}</span>
              </div>
            </div>
          )}

          <div className="card">
            <div className="row" style={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
              <h2>{t('teacher.wizard.previewTitle')}</h2>
              {draft.paperId && (
                <span className="small muted">{t('teacher.wizard.previewReadonly')}</span>
              )}
            </div>
            {summary.itemCount === 0 ? (
              <p className="muted">{t('teacher.wizard.previewEmpty')}</p>
            ) : (
              <>
                <div className="row" style={{ marginBottom: 8 }}>
                  <Tag tone="green">{t('teacher.wizard.previewCount', { n: summary.itemCount })}</Tag>
                  <Tag tone={verdict === 'mismatch' ? 'amber' : 'green'}>
                    {t('teacher.wizard.previewTotal', { total: fmtNum(summary.itemsTotal) })}
                  </Tag>
                  <Tag tone={summary.missingCount > 0 ? 'amber' : 'green'}>
                    {t('teacher.wizard.previewMissing', { n: summary.missingCount })}
                  </Tag>
                </div>
                <div className="hint" style={{ marginBottom: 8 }}>
                  {summary.gaps.length === 0
                    ? t('teacher.wizard.previewNoGap', { max: rows[rows.length - 1]?.number ?? 0 })
                    : t('teacher.wizard.previewGap', { list: summary.gaps.join('、') })}
                </div>
                <div className="wizard-list" style={{ maxHeight: 420, overflow: 'auto' }}>
                  <table>
                    <thead>
                      <tr>
                        <th>{t('teacher.wizard.colSection')}</th>
                        <th>{t('teacher.wizard.colNumber')}</th>
                        <th>{t('teacher.wizard.colType')}</th>
                        <th>{t('teacher.wizard.colPoints')}</th>
                        <th>{t('teacher.wizard.colStem')}</th>
                        <th>{t('teacher.wizard.colAnswer')}</th>
                        <th>{t('teacher.wizard.colSyncState')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((row) => (
                        <tr key={row.number}>
                          <td className="small">{row.sectionTitle}</td>
                          <td className="mono">{row.number}</td>
                          <td>{t(`teacher.questionType.${row.type}`)}</td>
                          <td className="mono">{fmtNum(row.points)}</td>
                          <td className="small">{row.stem.slice(0, 160)}</td>
                          <td>
                            {row.essay ? (
                              <span className="muted">{t('teacher.wizard.essayNoKey')}</span>
                            ) : row.missing ? (
                              <Tag tone="amber">{t('teacher.wizard.importNoAnswer')}</Tag>
                            ) : (
                              <span className="mono">{row.answerKey}</span>
                            )}
                          </td>
                          <td>
                            {row.missing ? <Tag tone="amber">{t('teacher.wizard.statusMissing')}</Tag> : <Tag tone="green">{t('teacher.wizard.saved')}</Tag>}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div className="hint" style={{ marginTop: 8 }}>{t('teacher.wizard.previewHint')}</div>
              </>
            )}
          </div>
        </>
      )}

      {step === 3 && (
        <div className="card">
          <h2>{t('teacher.wizard.step4Title')}</h2>
          <div className="hint" style={{ marginBottom: 10 }}>{t('teacher.wizard.rubricHintShort')}</div>
          {tabs.length === 0 ? (
            <p className="muted">{t('teacher.wizard.noEssays')}</p>
          ) : (
            <>
              <div className="tabs">
                {tabs.map((entry, index) => (
                  <button
                    key={entry.number}
                    type="button"
                    className={index === tab ? 'on' : ''}
                    onClick={() => {
                      // 换 tab 前先冲刷：状态行要如实反映「这一题存住了没有」。
                      void flushRubric()
                      setTab(index)
                    }}
                  >
                    {t('teacher.wizard.rubricTab', { number: entry.number, points: fmtNum(entry.points) })}
                  </button>
                ))}
              </div>
              {activeTab && (
                <div className="tab-panel">
                  <div className="hint" style={{ marginBottom: 8 }}>
                    {t('teacher.wizard.essayItemHeading', { number: activeTab.number, points: fmtNum(activeTab.points) })}
                    {activeTab.sectionTitle ? ` · ${activeTab.sectionTitle}` : ''}
                  </div>
                  <Suspense fallback={<div className="md-editor md-editor-loading">{t('editor.loading')}</div>}>
                    <VditorMarkdown
                      key={activeTab.number}
                      id={`paper-rubric-${activeTab.number}`}
                      rows={8}
                      value={activeRubric}
                      onChange={(next) => {
                        const number = activeTab.number
                        setRubricEdits((current) => ({ ...current, [number]: next }))
                        queueRubricSave(number, next)
                      }}
                      placeholder={t('teacher.wizard.essayItemRubricPlaceholder')}
                      disabled={locked}
                      expandable
                      heading={t('teacher.wizard.essayItemRubric')}
                      footer={
                        <RubricPreview
                          rubric={activeRubric}
                          totalPoints={activeTab.points}
                          title={t('teacher.wizard.rubricPreview')}
                          scope="paper"
                        />
                      }
                    />
                  </Suspense>
                  <div className={activeCheck?.verdict === 'matched' || activeCheck?.verdict === 'mismatch' || activeCheck?.verdict === 'noPoints' ? 'hint' : 'hint rubric-hint-empty'}>
                    {activeCheck?.verdict === 'unparsed'
                      ? t('teacher.wizard.essayItemRubricUnparsed')
                      : activeCheck?.verdict === 'empty'
                        ? t('teacher.wizard.essayItemRubricDefault')
                        : t('teacher.wizard.essayItemRubricHint')}
                  </div>
                  {locked ? null : (
                    <div
                      className={`rubric-save-state${rubricSave === 'error' ? ' is-error' : ''}`}
                      role="status"
                      aria-live="polite"
                    >
                      {rubricSave === 'saving'
                        ? t('teacher.wizard.rubricAutosaveSaving')
                        : rubricSave === 'error'
                          ? `${t('teacher.wizard.rubricAutosaveError')}${rubricError === null ? '' : `（${rubricError}）`}`
                          : rubricSave === 'saved'
                            ? t('teacher.wizard.rubricAutosaveSaved', { time: rubricSavedAt })
                            : t('teacher.wizard.rubricAutosaveIdle')}
                    </div>
                  )}
                  <div className="inline-note" style={{ marginTop: 10 }}>{t('teacher.wizard.rubricRebuildNote')}</div>
                </div>
              )}
            </>
          )}
        </div>
      )}

      {step === 4 && (
        <div className="card">
          <h2>{t('teacher.wizard.step5Title')}</h2>
          <div className="stats" style={{ marginBottom: 12 }}>
            <div className="stat flat">
              <div className="n">{fmtNum(summary.itemCount)}</div>
              <div className="l">{t('teacher.wizard.previewCountLabel')}</div>
            </div>
            <div className="stat flat">
              <div className="n">{fmtNum(actual)}</div>
              <div className="l">{t('teacher.wizard.actualTotal')}</div>
            </div>
            <div className="stat flat">
              <div className="n">{target === null ? '—' : fmtNum(target)}</div>
              <div className="l">{t('teacher.wizard.targetTotalPoints')}</div>
            </div>
            <div className="stat flat">
              <div className="n">{summary.missingCount}</div>
              <div className="l">{t('teacher.wizard.missingAnswersLabel')}</div>
            </div>
          </div>
          {legacyCriteria > 0 && (
            <div className="hint">
              {t('teacher.wizard.legacyCriteriaNote', { total: fmtNum(legacyCriteria) })}
            </div>
          )}

          {verdict === 'mismatch' ? (
            <div className="inline-note" style={{ borderLeftColor: 'var(--red)' }}>
              {t('teacher.wizard.totalMismatch', { local: fmtNum(target ?? 0), server: fmtNum(actual) })}
            </div>
          ) : verdict === 'match' ? (
            <div className="inline-note ok">{t('teacher.wizard.totalMatch', { total: fmtNum(actual) })}</div>
          ) : (
            <div className="hint">{t('teacher.wizard.noTarget')}</div>
          )}
          {summary.missingCount > 0 && (
            <div className="inline-note" style={{ borderLeftColor: 'var(--red)' }}>
              {t('teacher.wizard.missingAnswersNote', { n: summary.missingCount })}
            </div>
          )}

          <div className="wizard-list" style={{ margin: '12px 0' }}>
            <table>
              <thead>
                <tr>
                  <th>{t('teacher.wizard.colSection')}</th>
                  <th>{t('teacher.wizard.colNumbers')}</th>
                  <th>{t('teacher.wizard.colSectionPoints')}</th>
                  <th>{t('teacher.wizard.colCriteria')}</th>
                </tr>
              </thead>
              <tbody>
                {(detail?.sections ?? []).length === 0 ? (
                  <tr>
                    <td colSpan={4} className="muted">
                      {t('teacher.wizard.previewEmpty')}
                    </td>
                  </tr>
                ) : (
                  (detail?.sections ?? []).map((section) => {
                    const sectionPoints = section.items.reduce((sum, item) => sum + Number(item.points), 0)
                    const numbers = section.items.map((item) => item.number)
                    const sectionTabs = tabs.filter((entry) => numbers.includes(entry.number))
                    const blocks = rubricBlocks(sectionTabs, t('teacher.wizard.criteriaNone'))
                    return (
                      <tr key={section.id}>
                        <td>
                          <b>{section.title}</b>
                        </td>
                        <td className="mono">
                          {numbers.length === 0
                            ? '—'
                            : numbers.length === 1
                              ? String(numbers[0])
                              : `${numbers[0]}–${numbers[numbers.length - 1]}`}
                        </td>
                        <td className="mono">{fmtNum(sectionPoints)}</td>
                        <td className="small">
                          {blocks.map((block) => (
                            <div key={block.number ?? 'none'} className="rubric-block">
                              {block.number !== null && <b className="mono">{block.number} </b>}
                              <span style={{ whiteSpace: 'pre-wrap' }}>{block.text}</span>
                            </div>
                          ))}
                        </td>
                      </tr>
                    )
                  })
                )}
              </tbody>
            </table>
          </div>
          <div className="hint">{t('teacher.wizard.previewRubricHint')}</div>

          <div className="row" style={{ marginTop: 10 }}>
            <button
              type="button"
              className="btn pri"
              disabled={publishBlocked || busy === 'publish'}
              onClick={() => void publish()}
            >
              {busy === 'publish' ? t('teacher.common.saving') : t('teacher.wizard.publish')}
            </button>
            {locked && <Tag tone="green">{t('teacher.wizard.published')}</Tag>}
            {check.reason === 'missingAnswers' && <span className="tag red">{t('teacher.wizard.publishBlockedAnswers')}</span>}
            {check.reason === 'mismatch' && <span className="tag red">{t('teacher.wizard.publishBlocked')}</span>}
            {check.reason === 'empty' && <span className="tag red">{t('teacher.wizard.errors.emptyPaper')}</span>}
          </div>
          {locked && (
            <div className="row" style={{ marginTop: 10 }}>
              <Link className="btn pri" to={`/offerings/new?paperId=${draft.paperId ?? ''}`}>
                {t('teacher.wizard.assignNow')}
              </Link>
              <Link className="btn" to="/papers">
                {t('teacher.wizard.backToPapers')}
              </Link>
            </div>
          )}
        </div>
      )}

      <div className="row" style={{ justifyContent: 'space-between', marginTop: 4 }}>
        <button type="button" className="btn" disabled={step <= 1 || busy === 'generate'} onClick={() => void goPrev()}>
          {t('teacher.common.prev')}
        </button>
        <span className="small muted">{t('teacher.wizard.draftSavedAt', { key: storageKey })}</span>
        <button type="button" className="btn pri" disabled={step >= 4 || busy === 'generate'} onClick={() => void goNext()}>
          {t('teacher.common.next')}
        </button>
      </div>
    </TeacherPage>
  )
}
