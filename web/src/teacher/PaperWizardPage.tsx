import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type {
  PaperDetail,
  PaperImportResult,
  PaperItem,
  PaperSection,
  PaperView,
} from '../api/types'
import { ErrorState, Loading, PageHeader } from '../components/States'
import {
  errorMessage,
  fmtNum,
  Tag,
  TeacherPage,
} from './common'

type QType = 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'FILL_BLANK' | 'ESSAY'
type ScoringStrategy = 'ALL_OR_NOTHING' | 'PARTIAL'

const QUESTION_TYPES: QType[] = ['SINGLE_CHOICE', 'MULTIPLE_CHOICE', 'FILL_BLANK', 'ESSAY']

interface DraftItem {
  key: string
  serverId?: string
  number: number
  questionType: QType
  stem: string
  options: string
  answerKey: string
  points: number
  scoringStrategy: ScoringStrategy
}

interface DraftCriterion {
  key: string
  serverId?: string
  name: string
  maxPoints: number
  description: string
}

interface DraftSection {
  key: string
  serverId?: string
  title: string
  pointsPerItem: number
  items: DraftItem[]
  criteria: DraftCriterion[]
}

interface Draft {
  mode: 'create' | 'edit'
  paperId: string | null
  step: number
  title: string
  defaultDurationMinutes: string
  sections: DraftSection[]
  /** 编辑模式：详情接口只返回评分项名称，逐项分值用「总分 − 题项分」推导。 */
  legacyCriteriaNames: string[]
  existingCriteriaTotal: number
  rubricMarkdown: string
  gradingPromptTemplate: string
  rubricTouched: boolean
  serverTotalPoints: number | null
  locked: boolean
  updatedAt: string
}

const STORAGE_KEY = 'teachloop.paperDraft'
const STEP_IDS = [1, 2, 3, 4, 5] as const
type Step = (typeof STEP_IDS)[number]

function uid(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

function blankDraft(): Draft {
  return {
    mode: 'create',
    paperId: null,
    step: 1,
    title: '',
    defaultDurationMinutes: '',
    sections: [],
    legacyCriteriaNames: [],
    existingCriteriaTotal: 0,
    rubricMarkdown: '',
    gradingPromptTemplate: '',
    rubricTouched: false,
    serverTotalPoints: null,
    locked: false,
    updatedAt: '',
  }
}

function fromDetail(detail: PaperDetail): Draft {
  const itemsTotal = detail.sections.reduce(
    (acc, section) => acc + section.items.reduce((sum, item) => sum + Number(item.points), 0),
    0,
  )
  const criteriaTotal = Math.max(0, Number(detail.paper.totalPoints) - itemsTotal)
  return {
    ...blankDraft(),
    mode: 'edit',
    paperId: detail.paper.id,
    title: detail.paper.title,
    defaultDurationMinutes:
      detail.paper.defaultDurationMinutes === null
        ? ''
        : String(detail.paper.defaultDurationMinutes),
    sections: detail.sections.map((section) => ({
      key: section.id,
      serverId: section.id,
      title: section.title,
      pointsPerItem: Number(section.pointsPerItem),
      items: section.items.map((item) => ({
        key: item.id,
        serverId: item.id,
        number: item.number,
        questionType: item.questionType,
        stem: item.stem ?? '',
        options: item.options ?? '',
        answerKey: item.answerKey ?? '',
        points: Number(item.points),
        scoringStrategy: (item.scoringStrategy ?? 'ALL_OR_NOTHING') as ScoringStrategy,
      })),
      criteria: [],
    })),
    legacyCriteriaNames: detail.criteriaNames,
    existingCriteriaTotal: criteriaTotal,
    serverTotalPoints: Number(detail.paper.totalPoints),
    locked: detail.paper.status === 'PUBLISHED',
  }
}

function loadStoredDraft(): Draft | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<Draft>
    if (!parsed || !Array.isArray(parsed.sections)) return null
    return {
      ...blankDraft(),
      ...parsed,
      mode: parsed.mode === 'edit' ? 'edit' : 'create',
      step: Math.min(5, Math.max(1, Number(parsed.step ?? 1) || 1)),
      sections: parsed.sections.map((section) => ({
        key: section.key ?? uid(),
        serverId: section.serverId,
        title: section.title ?? '',
        pointsPerItem: Number(section.pointsPerItem ?? 0),
        items: (section.items ?? []).map((item) => ({
          key: item.key ?? uid(),
          serverId: item.serverId,
          number: Number(item.number ?? 0),
          questionType: (item.questionType ?? 'SINGLE_CHOICE') as QType,
          scoringStrategy: (item.scoringStrategy ?? 'ALL_OR_NOTHING') as ScoringStrategy,
          stem: item.stem ?? '',
          options: item.options ?? '',
          answerKey: item.answerKey ?? '',
          points: Number(item.points ?? 0),
        })),
        criteria: (section.criteria ?? []).map((criterion) => ({
          key: criterion.key ?? uid(),
          serverId: criterion.serverId,
          name: criterion.name ?? '',
          maxPoints: Number(criterion.maxPoints ?? 0),
          description: criterion.description ?? '',
        })),
      })),
    }
  } catch {
    return null
  }
}

/** 服务端题项 → 本地草稿题项；同一条记录的 key 保持不变，避免输入框被重挂。 */
function itemFromServer(item: PaperItem, previous?: DraftItem): DraftItem {
  return {
    key: previous?.key ?? item.id,
    serverId: item.id,
    number: item.number,
    questionType: item.questionType,
    stem: item.stem ?? '',
    options: item.options ?? '',
    answerKey: item.answerKey ?? '',
    points: Number(item.points),
    scoringStrategy: (item.scoringStrategy ?? 'ALL_OR_NOTHING') as ScoringStrategy,
  }
}

function sectionFromServer(section: PaperSection): DraftSection {
  return {
    key: section.id,
    serverId: section.id,
    title: section.title,
    pointsPerItem: Number(section.pointsPerItem),
    items: section.items.map((item) => itemFromServer(item)),
    criteria: [],
  }
}

/**
 * 把 GET /api/papers/{id} 的结果合并回本地草稿。
 * 已同步部分以服务端为准（题项、标题、每题分值），本地还没同步的部分与新增题项原样保留。
 */
function mergeDetailIntoDraft(draft: Draft, detail: PaperDetail): Draft {
  const serverById = new Map(detail.sections.map((section) => [section.id, section]))
  const used = new Set<string>()
  const sections: DraftSection[] = []
  for (const section of draft.sections) {
    // 本地新增（还没有 serverId）一律保留。
    if (!section.serverId) {
      sections.push(section)
      continue
    }
    const server = serverById.get(section.serverId)
    // 已同步的部分在服务端不存在了 = 被删掉，本地也要移除。
    if (!server) continue
    used.add(server.id)
    const previousByServerId = new Map(
      section.items
        .filter((item) => item.serverId)
        .map((item) => [item.serverId as string, item] as const),
    )
    const serverItems = server.items.map((item) =>
      itemFromServer(item, previousByServerId.get(item.id)),
    )
    const localOnly = section.items.filter((item) => !item.serverId)
    sections.push({
      ...section,
      title: server.title,
      pointsPerItem: Number(server.pointsPerItem),
      items: [...serverItems, ...localOnly],
    })
  }
  for (const server of detail.sections) {
    if (!used.has(server.id)) sections.push(sectionFromServer(server))
  }
  return {
    ...draft,
    sections,
    serverTotalPoints: Number(detail.paper.totalPoints),
    locked: detail.paper.status === 'PUBLISHED',
  }
}

function cloneSections(sections: DraftSection[]): DraftSection[] {
  return sections.map((section) => ({
    ...section,
    items: section.items.map((item) => ({ ...item })),
    criteria: section.criteria.map((criterion) => ({ ...criterion })),
  }))
}

function itemsTotalOf(draft: Draft): number {
  return draft.sections.reduce(
    (acc, section) => acc + section.items.reduce((sum, item) => sum + item.points, 0),
    0,
  )
}

function criteriaTotalOf(draft: Draft): number {
  return (
    draft.existingCriteriaTotal +
    draft.sections.reduce(
      (acc, section) => acc + section.criteria.reduce((sum, criterion) => sum + criterion.maxPoints, 0),
      0,
    )
  )
}

function localTotalOf(draft: Draft): number {
  return itemsTotalOf(draft) + criteriaTotalOf(draft)
}

function missingAnswerCount(draft: Draft): number {
  return draft.sections.reduce(
    (acc, section) =>
      acc +
      section.items.filter((item) => item.questionType !== 'ESSAY' && !item.answerKey.trim()).length,
    0,
  )
}

type Problem =
  | 'title'
  | 'duration'
  | 'sectionTitle'
  | 'sectionPoints'
  | 'itemNumber'
  | 'itemDuplicate'
  | 'itemPoints'
  | 'criteriaName'
  | 'criteriaPoints'
  | null

function findProblem(draft: Draft): Problem {
  if (!draft.title.trim()) return 'title'
  if (draft.defaultDurationMinutes.trim() !== '') {
    const minutes = Number(draft.defaultDurationMinutes)
    if (!Number.isInteger(minutes) || minutes <= 0) return 'duration'
  }
  for (const section of draft.sections) {
    if (!section.title.trim()) return 'sectionTitle'
    if (!Number.isFinite(section.pointsPerItem) || section.pointsPerItem < 0) return 'sectionPoints'
    const seen = new Set<number>()
    for (const item of section.items) {
      if (!Number.isInteger(item.number) || item.number <= 0) return 'itemNumber'
      if (seen.has(item.number)) return 'itemDuplicate'
      seen.add(item.number)
      if (!Number.isFinite(item.points) || item.points <= 0) return 'itemPoints'
    }
    for (const criterion of section.criteria) {
      if (!criterion.name.trim()) return 'criteriaName'
      if (!Number.isFinite(criterion.maxPoints) || criterion.maxPoints <= 0) return 'criteriaPoints'
    }
  }
  return null
}

/**
 * T185 新建 / 编辑试卷：5 步向导 + localStorage 草稿。
 * 整条流程不出现班级选择；班级只在「布置」页选择。
 */
export default function PaperWizardPage() {
  const { t } = useTranslation('translation')
  const { paperId: paperIdParam } = useParams()
  const editMode = Boolean(paperIdParam)

  const [draft, setDraft] = useState<Draft | null>(null)
  const [ready, setReady] = useState(false)
  const [loadError, setLoadError] = useState<Error | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [importFile, setImportFile] = useState<File | null>(null)
  const [importing, setImporting] = useState(false)
  const [importResult, setImportResult] = useState<PaperImportResult | null>(null)
  const [importError, setImportError] = useState<string | null>(null)
  const [importMode, setImportMode] = useState<'file' | 'text'>('file')
  const [importText, setImportText] = useState('')
  const [importSectionKey, setImportSectionKey] = useState('')
  const [batchForm, setBatchForm] = useState({ from: '', to: '', type: 'SINGLE_CHOICE' as QType })
  const [batchMessage, setBatchMessage] = useState<string | null>(null)
  const [batchError, setBatchError] = useState<string | null>(null)
  const [resumed, setResumed] = useState(false)

  // 第 2 / 3 步的行内编辑与删除（locked 时这些按钮不渲染）。
  const [editingSectionKey, setEditingSectionKey] = useState<string | null>(null)
  const [sectionEdit, setSectionEdit] = useState({ title: '', pointsPerItem: '' })
  const [deletingSectionKey, setDeletingSectionKey] = useState<string | null>(null)
  const [editingItemKey, setEditingItemKey] = useState<string | null>(null)
  const [deletingItemKey, setDeletingItemKey] = useState<string | null>(null)
  const [rowBusy, setRowBusy] = useState<string | null>(null)

  const [sectionForm, setSectionForm] = useState({ title: '', pointsPerItem: '' })
  const [itemSectionKey, setItemSectionKey] = useState('')
  const [itemForm, setItemForm] = useState({
    number: '',
    questionType: 'SINGLE_CHOICE' as QType,
    stem: '',
    options: '',
    answerKey: '',
    points: '',
    scoringStrategy: 'ALL_OR_NOTHING' as ScoringStrategy,
  })
  const [criteriaSectionKey, setCriteriaSectionKey] = useState('')
  const [criteriaForm, setCriteriaForm] = useState({ name: '', maxPoints: '', description: '' })
  const [formError, setFormError] = useState<string | null>(null)

  const [syncing, setSyncing] = useState(false)
  const [syncError, setSyncError] = useState<string | null>(null)
  const [syncMessage, setSyncMessage] = useState<string | null>(null)
  const [publishing, setPublishing] = useState(false)
  const [publishedNow, setPublishedNow] = useState(false)

  // 载入：编辑模式先 GET 详情回填；新建模式优先恢复 localStorage 草稿。
  useEffect(() => {
    let alive = true
    setReady(false)
    setLoadError(null)
    setFormError(null)
    setSyncError(null)
    setSyncMessage(null)

    if (paperIdParam) {
      const stored = loadStoredDraft()
      if (stored && stored.mode === 'edit' && stored.paperId === paperIdParam) {
        setDraft(stored)
        setResumed(true)
        setReady(true)
        return () => {
          alive = false
        }
      }
      api
        .get<PaperDetail>(`/api/papers/${paperIdParam}`)
        .then((detail) => {
          if (!alive) return
          setDraft(fromDetail(detail))
          setResumed(false)
          setReady(true)
        })
        .catch((err: unknown) => {
          if (!alive) return
          setLoadError(err instanceof Error ? err : new Error(String(err)))
          setReady(true)
        })
      return () => {
        alive = false
      }
    }

    const stored = loadStoredDraft()
    const hasContent =
      stored !== null &&
      (stored.title.trim() !== '' || stored.sections.length > 0 || stored.paperId !== null)
    if (stored && stored.mode === 'create' && hasContent) {
      setDraft(stored)
      setResumed(true)
      // 已经落过服务端草稿：后台对一次详情，剪掉在别处删掉的部分、刷新总分。
      if (stored.paperId) {
        api
          .get<PaperDetail>(`/api/papers/${stored.paperId}`)
          .then((detail) => {
            if (!alive) return
            setDraft((current) => (current ? mergeDetailIntoDraft(current, detail) : current))
          })
          .catch(() => {
            // 详情取不到（离线/被删）时保留本地草稿，不阻塞继续编辑。
          })
      }
    } else {
      setDraft(blankDraft())
      setResumed(false)
    }
    setReady(true)
    return () => {
      alive = false
    }
  }, [paperIdParam, reloadKey])

  // 自动保存草稿：编辑模式同样保存，切换论文时按 paperId 判断归属。
  useEffect(() => {
    if (!ready || !draft || publishedNow) return
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(draft))
    } catch {
      // 浏览器禁用 localStorage 时向导仍可在当前页面完成。
    }
  }, [ready, draft, publishedNow])

  useEffect(() => {
    if (!draft) return
    if (!itemSectionKey && draft.sections.length > 0) {
      setItemSectionKey(draft.sections[0].key)
    }
    if (!criteriaSectionKey && draft.sections.length > 0) {
      setCriteriaSectionKey(draft.sections[0].key)
    }
  }, [draft, itemSectionKey, criteriaSectionKey])

  const update = (fn: (current: Draft) => Draft) => {
    setDraft((current) => (current ? fn(current) : current))
  }

  const setStep = (step: Step) => update((current) => ({ ...current, step }))

  /** 用服务端详情刷新本地草稿（总分、锁定态、已同步题项）。 */
  const mergeFromServer = (detail: PaperDetail) => {
    setDraft((current) => (current ? mergeDetailIntoDraft(current, detail) : current))
  }

  const refreshFromServer = async (paperId: string) => {
    const detail = await api.get<PaperDetail>(`/api/papers/${paperId}`)
    mergeFromServer(detail)
    return detail
  }

  /**
   * 导入前置：确保试卷与至少一个部分已在服务端存在，并回填 local → serverId。
   * 与 syncDraft 的区别：不校验答案是否齐全、不创建题项，只保证「有卷、有部分」。
   */
  const ensurePaper = async (): Promise<Draft> => {
    if (!draft) throw new Error(t('teacher.wizard.errors.noDraft'))
    let current: Draft = { ...draft, sections: cloneSections(draft.sections) }
    const persist = (next: Draft) => {
      current = next
      setDraft(next)
      return next
    }
    if (!current.title.trim()) throw new Error(t('teacher.wizard.errors.title'))
    if (!current.paperId) {
      const durationText = current.defaultDurationMinutes.trim()
      const duration = durationText === '' ? null : Number(durationText)
      if (duration !== null && (!Number.isInteger(duration) || duration <= 0)) {
        throw new Error(t('teacher.wizard.errors.duration'))
      }
      const created = await api.post<PaperView>('/api/papers', {
        title: current.title.trim(),
        defaultDurationMinutes: duration,
      })
      persist({ ...current, paperId: created.id })
    }
    const pid = current.paperId as string
    if (current.sections.length === 0) {
      persist({
        ...current,
        sections: [
          {
            key: uid(),
            title: t('teacher.wizard.defaultSection'),
            pointsPerItem: 1,
            items: [],
            criteria: [],
          },
        ],
      })
    }
    for (let index = 0; index < current.sections.length; index += 1) {
      if (current.sections[index].serverId) continue
      const created = await api.post<{ id: string }>(`/api/papers/${pid}/sections`, {
        title: current.sections[index].title.trim() || t('teacher.wizard.defaultSection'),
        pointsPerItem: current.sections[index].pointsPerItem,
      })
      persist({
        ...current,
        sections: current.sections.map((section, i) =>
          i === index ? { ...section, serverId: created.id } : section,
        ),
      })
    }
    if (!itemSectionKey && current.sections.length > 0) {
      setItemSectionKey(current.sections[0].key)
    }
    if (!importSectionKey && current.sections.length > 0) {
      setImportSectionKey(current.sections[0].key)
    }
    return current
  }

  const goNext = () => {
    if (!draft) return
    if (draft.step === 1) {
      const problem = findProblem({ ...draft, sections: [] })
      if (problem) {
        setFormError(t(`teacher.wizard.errors.${problem}`))
        return
      }
    }
    if (draft.step === 2 && !itemForm.number) {
      const section =
        draft.sections.find((s) => s.key === itemSectionKey) ?? draft.sections[0] ?? null
      if (section) {
        const nextNumber = Math.max(0, ...section.items.map((item) => item.number)) + 1
        setItemForm((current) => ({ ...current, number: String(nextNumber) }))
      }
    }
    setFormError(null)
    setStep(Math.min(5, draft.step + 1) as Step)
  }

  const goPrev = () => {
    if (!draft) return
    setFormError(null)
    setStep(Math.max(1, draft.step - 1) as Step)
  }

  const addSection = () => {
    if (!draft || draft.locked) return
    const title = sectionForm.title.trim()
    const pointsPerItem = Number(sectionForm.pointsPerItem)
    if (!title) {
      setFormError(t('teacher.wizard.errors.sectionTitle'))
      return
    }
    if (!Number.isFinite(pointsPerItem) || pointsPerItem < 0) {
      setFormError(t('teacher.wizard.errors.sectionPoints'))
      return
    }
    const section: DraftSection = {
      key: uid(),
      title,
      pointsPerItem,
      items: [],
      criteria: [],
    }
    update((current) => ({ ...current, sections: [...current.sections, section] }))
    if (!itemSectionKey) setItemSectionKey(section.key)
    if (!criteriaSectionKey) setCriteriaSectionKey(section.key)
    setSectionForm({ title: '', pointsPerItem: '' })
    setFormError(null)
  }

  const startEditSection = (section: DraftSection) => {
    setEditingSectionKey(section.key)
    setDeletingSectionKey(null)
    setSectionEdit({ title: section.title, pointsPerItem: fmtNum(section.pointsPerItem) })
    setFormError(null)
  }

  const cancelEditSection = () => {
    setEditingSectionKey(null)
    setSectionEdit({ title: '', pointsPerItem: '' })
    setFormError(null)
  }

  const saveSectionEdit = async (section: DraftSection) => {
    if (!draft || draft.locked) return
    const title = sectionEdit.title.trim()
    const pointsPerItem = Number(sectionEdit.pointsPerItem)
    if (!title) {
      setFormError(t('teacher.wizard.errors.sectionTitle'))
      return
    }
    if (!Number.isFinite(pointsPerItem) || pointsPerItem < 0) {
      setFormError(t('teacher.wizard.errors.sectionPoints'))
      return
    }
    const pid = draft.paperId
    setRowBusy(`section:${section.key}`)
    try {
      if (section.serverId && pid) {
        const saved = await api.put<{ title: string; pointsPerItem: number }>(
          `/api/papers/${pid}/sections/${section.serverId}${draft.locked ? '?confirm=true' : ''}`,
          { title, pointsPerItem },
        )
        // 服务端已返回小数分值（SectionView.pointsPerItem 是 BigDecimal），以服务端结果为准。
        update((current) => ({
          ...current,
          sections: current.sections.map((s) =>
            s.key === section.key
              ? { ...s, title: saved.title ?? title, pointsPerItem: Number(saved.pointsPerItem ?? pointsPerItem) }
              : s,
          ),
        }))
      } else {
        update((current) => ({
          ...current,
          sections: current.sections.map((s) =>
            s.key === section.key ? { ...s, title, pointsPerItem } : s,
          ),
        }))
      }
      setEditingSectionKey(null)
      setSectionEdit({ title: '', pointsPerItem: '' })
      setFormError(null)
    } catch (err) {
      setFormError(errorMessage(err))
    } finally {
      setRowBusy(null)
    }
  }

  const removeSection = async (section: DraftSection) => {
    if (!draft || draft.locked) return
    const pid = draft.paperId
    setRowBusy(`section:${section.key}`)
    setFormError(null)
    try {
      if (section.serverId && pid) {
        await api.del(
          `/api/papers/${pid}/sections/${section.serverId}${draft.locked ? '?confirm=true' : ''}`,
        )
        await refreshFromServer(pid)
      } else {
        update((current) => ({
          ...current,
          sections: current.sections.filter((s) => s.key !== section.key),
        }))
      }
      const remaining = draft.sections.filter((s) => s.key !== section.key)
      const nextKey = remaining[0]?.key ?? ''
      if (itemSectionKey === section.key) setItemSectionKey(nextKey)
      if (criteriaSectionKey === section.key) setCriteriaSectionKey(nextKey)
      if (importSectionKey === section.key) setImportSectionKey(nextKey)
      if (editingSectionKey === section.key) setEditingSectionKey(null)
      if (editingItemKey && section.items.some((item) => item.key === editingItemKey)) {
        setEditingItemKey(null)
      }
      setDeletingSectionKey(null)
    } catch (err) {
      setFormError(errorMessage(err))
    } finally {
      setRowBusy(null)
    }
  }

  /** 校验题型表单；ignoreKey 让编辑态把自己排除在重号检查之外。返回 null 表示已写入 formError。 */
  const readItemForm = (
    section: DraftSection,
    ignoreKey: string | null,
  ): { number: number; points: number; answerKey: string; scoringStrategy: ScoringStrategy } | null => {
    const number = Number(itemForm.number)
    const points = Number(itemForm.points)
    if (!Number.isInteger(number) || number <= 0) {
      setFormError(t('teacher.wizard.errors.itemNumber'))
      return null
    }
    if (section.items.some((item) => item.number === number && item.key !== ignoreKey)) {
      setFormError(t('teacher.wizard.errors.itemDuplicate'))
      return null
    }
    if (!Number.isFinite(points) || points <= 0) {
      setFormError(t('teacher.wizard.errors.itemPoints'))
      return null
    }
    return {
      number,
      points,
      answerKey: itemForm.questionType === 'ESSAY' ? '' : itemForm.answerKey,
      scoringStrategy:
        itemForm.questionType === 'MULTIPLE_CHOICE' ? itemForm.scoringStrategy : 'ALL_OR_NOTHING',
    }
  }

  const addItem = () => {
    if (!draft || draft.locked) return
    const section = draft.sections.find((s) => s.key === itemSectionKey)
    if (!section) {
      setFormError(t('teacher.wizard.errors.needSection'))
      return
    }
    const parsed = readItemForm(section, null)
    if (!parsed) return
    const item: DraftItem = {
      key: uid(),
      number: parsed.number,
      questionType: itemForm.questionType,
      stem: itemForm.stem,
      options: itemForm.options,
      answerKey: parsed.answerKey,
      points: parsed.points,
      scoringStrategy: parsed.scoringStrategy,
    }
    update((current) => ({
      ...current,
      sections: current.sections.map((s) =>
        s.key === section.key ? { ...s, items: [...s.items, item] } : s,
      ),
    }))
    const nextNumber = Math.max(0, ...section.items.map((i) => i.number), parsed.number) + 1
    setItemForm({
      number: String(nextNumber),
      questionType: itemForm.questionType,
      stem: '',
      options: '',
      answerKey: '',
      points: '',
      scoringStrategy: itemForm.scoringStrategy,
    })
    setFormError(null)
  }

  const startEditItem = (section: DraftSection, item: DraftItem) => {
    setEditingItemKey(item.key)
    setDeletingItemKey(null)
    setItemSectionKey(section.key)
    setItemForm({
      number: String(item.number),
      questionType: item.questionType,
      stem: item.stem,
      options: item.options,
      answerKey: item.answerKey,
      points: fmtNum(item.points),
      scoringStrategy: item.scoringStrategy,
    })
    setFormError(null)
  }

  const cancelEditItem = () => {
    setEditingItemKey(null)
    setFormError(null)
    const section = draft?.sections.find((s) => s.key === itemSectionKey) ?? null
    const nextNumber = section ? Math.max(0, ...section.items.map((i) => i.number)) + 1 : 1
    setItemForm({
      number: String(nextNumber),
      questionType: 'SINGLE_CHOICE',
      stem: '',
      options: '',
      answerKey: '',
      points: '',
      scoringStrategy: 'ALL_OR_NOTHING',
    })
  }

  const saveItemEdit = async () => {
    if (!draft || draft.locked) return
    const section = draft.sections.find((s) => s.key === itemSectionKey)
    if (!section) {
      setFormError(t('teacher.wizard.errors.needSection'))
      return
    }
    const editing = section.items.find((item) => item.key === editingItemKey)
    if (!editing) {
      setEditingItemKey(null)
      return
    }
    const parsed = readItemForm(section, editing.key)
    if (!parsed) return
    if (editing.serverId && editing.number !== parsed.number) {
      setFormError(t('teacher.wizard.errors.itemNumberLocked'))
      return
    }
    const pid = draft.paperId
    const confirmQuery = draft.locked ? '?confirm=true' : ''
    setRowBusy(`item:${editing.key}`)
    try {
      if (editing.serverId && pid) {
        // 题型先改，再改内容：后端会在题型变更时规范标准答案。
        if (editing.questionType !== itemForm.questionType) {
          await api.put(`/api/papers/${pid}/items/${parsed.number}/question-type${confirmQuery}`, {
            questionType: itemForm.questionType,
          })
        }
        await api.put(`/api/papers/${pid}/items/${parsed.number}${confirmQuery}`, {
          stem: itemForm.stem.trim() || null,
          options: itemForm.options.trim() || null,
          answerKey: parsed.answerKey.trim() || null,
          points: parsed.points,
        })
        if (
          itemForm.questionType === 'MULTIPLE_CHOICE' &&
          editing.scoringStrategy !== parsed.scoringStrategy
        ) {
          await api.put(
            `/api/papers/${pid}/items/${parsed.number}/scoring-strategy${confirmQuery}`,
            { scoringStrategy: parsed.scoringStrategy },
          )
        }
        await refreshFromServer(pid)
      } else {
        update((current) => ({
          ...current,
          sections: current.sections.map((s) =>
            s.key !== section.key
              ? s
              : {
                  ...s,
                  items: s.items.map((item) =>
                    item.key === editing.key
                      ? {
                          ...item,
                          number: parsed.number,
                          questionType: itemForm.questionType,
                          stem: itemForm.stem,
                          options: itemForm.options,
                          answerKey: parsed.answerKey,
                          points: parsed.points,
                          scoringStrategy: parsed.scoringStrategy,
                        }
                      : item,
                  ),
                },
          ),
        }))
      }
      const nextNumber = Math.max(0, ...section.items.map((i) => i.number), parsed.number) + 1
      setItemForm({
        number: String(nextNumber),
        questionType: itemForm.questionType,
        stem: '',
        options: '',
        answerKey: '',
        points: '',
        scoringStrategy: itemForm.scoringStrategy,
      })
      setEditingItemKey(null)
      setFormError(null)
    } catch (err) {
      setFormError(errorMessage(err))
    } finally {
      setRowBusy(null)
    }
  }

  const removeItem = async (section: DraftSection, item: DraftItem) => {
    if (!draft || draft.locked) return
    const pid = draft.paperId
    const confirmQuery = draft.locked ? '?confirm=true' : ''
    setRowBusy(`item:${item.key}`)
    setFormError(null)
    try {
      if (item.serverId && pid) {
        await api.del(`/api/papers/${pid}/items/${item.number}${confirmQuery}`)
        await refreshFromServer(pid)
      } else {
        update((current) => ({
          ...current,
          sections: current.sections.map((s) =>
            s.key === section.key
              ? { ...s, items: s.items.filter((i) => i.key !== item.key) }
              : s,
          ),
        }))
      }
      setDeletingItemKey(null)
      if (editingItemKey === item.key) setEditingItemKey(null)
    } catch (err) {
      setFormError(errorMessage(err))
    } finally {
      setRowBusy(null)
    }
  }

  const addCriteria = () => {
    if (!draft || draft.locked) return
    const section = draft.sections.find((s) => s.key === criteriaSectionKey)
    if (!section) {
      setFormError(t('teacher.wizard.errors.needSection'))
      return
    }
    const name = criteriaForm.name.trim()
    const maxPoints = Number(criteriaForm.maxPoints)
    if (!name) {
      setFormError(t('teacher.wizard.errors.criteriaName'))
      return
    }
    if (!Number.isFinite(maxPoints) || maxPoints <= 0) {
      setFormError(t('teacher.wizard.errors.criteriaPoints'))
      return
    }
    const criterion: DraftCriterion = {
      key: uid(),
      name,
      maxPoints,
      description: criteriaForm.description.trim(),
    }
    update((current) => ({
      ...current,
      sections: current.sections.map((s) =>
        s.key === section.key ? { ...s, criteria: [...s.criteria, criterion] } : s,
      ),
    }))
    setCriteriaForm({ name: '', maxPoints: '', description: '' })
    setFormError(null)
  }

  /**
   * 把本地草稿同步到服务端：只补建还缺的试卷/部分/题项/评分项。
   * 编辑与删除在各自的行内操作里即时落库，不走这里，所以重复同步不会覆盖已保存内容。
   * 每成功创建一条就落盘，失败重试时不会重复创建。
   */
  const syncDraft = async (base: Draft): Promise<Draft> => {
    if (base.locked) return base
    const problem = findProblem(base)
    if (problem) throw new Error(t(`teacher.wizard.errors.${problem}`))
    const missing = missingAnswerCount(base)
    if (missing > 0) {
      throw new Error(t('teacher.wizard.errors.missingAnswers', { n: missing }))
    }

    let current: Draft = {
      ...base,
      sections: cloneSections(base.sections),
    }
    const persist = (next: Draft) => {
      current = next
      setDraft(next)
      return next
    }

    let paperId = current.paperId
    if (!paperId) {
      const created = await api.post<PaperView>('/api/papers', {
        title: current.title.trim(),
        defaultDurationMinutes:
          current.defaultDurationMinutes.trim() === ''
            ? null
            : Number(current.defaultDurationMinutes),
      })
      paperId = created.id
      persist({ ...current, paperId })
    }

    const pid = paperId
    for (let index = 0; index < current.sections.length; index += 1) {
      if (!current.sections[index].serverId) {
        const created = await api.post<{ id: string }>(`/api/papers/${pid}/sections`, {
          title: current.sections[index].title.trim(),
          pointsPerItem: current.sections[index].pointsPerItem,
        })
        const sections = current.sections.map((section, i) =>
          i === index ? { ...section, serverId: created.id } : section,
        )
        persist({ ...current, sections })
      }
      const sectionId = current.sections[index].serverId as string

      let items = current.sections[index].items.map((item) => ({ ...item }))
      for (let itemIndex = 0; itemIndex < items.length; itemIndex += 1) {
        if (items[itemIndex].serverId) continue
        const created = await api.post<PaperItem>(`/api/papers/${pid}/items`, {
          number: items[itemIndex].number,
          questionType: items[itemIndex].questionType,
          stem: items[itemIndex].stem.trim() || null,
          options: items[itemIndex].options.trim() || null,
          answerKey: items[itemIndex].answerKey.trim() || null,
          points: items[itemIndex].points,
          sectionId,
          scoringStrategy: items[itemIndex].scoringStrategy,
        })
        items = items.map((item, i) =>
          i === itemIndex ? { ...item, serverId: created.id } : item,
        )
        const sections = current.sections.map((section, i) =>
          i === index ? { ...section, items: [...items] } : section,
        )
        persist({ ...current, sections })
      }

      let criteria = current.sections[index].criteria.map((criterion) => ({ ...criterion }))
      for (let criteriaIndex = 0; criteriaIndex < criteria.length; criteriaIndex += 1) {
        if (criteria[criteriaIndex].serverId) continue
        await api.post(`/api/papers/${pid}/criteria`, {
          name: criteria[criteriaIndex].name.trim(),
          maxPoints: criteria[criteriaIndex].maxPoints,
          description: criteria[criteriaIndex].description.trim() || null,
          sectionId,
        })
        criteria = criteria.map((criterion, i) =>
          i === criteriaIndex ? { ...criterion, serverId: 'saved' } : criterion,
        )
        const sections = current.sections.map((section, i) =>
          i === index ? { ...section, criteria: [...criteria] } : section,
        )
        persist({ ...current, sections })
      }
    }

    if (current.rubricTouched) {
      await api.put(`/api/papers/${pid}/rubric`, {
        rubricMarkdown: current.rubricMarkdown,
        gradingPromptTemplate: current.gradingPromptTemplate.trim() || null,
      })
    }

    const detail = await api.get<PaperDetail>(`/api/papers/${pid}`)
    const synced: Draft = {
      ...current,
      serverTotalPoints: Number(detail.paper.totalPoints),
      locked: detail.paper.status === 'PUBLISHED',
    }
    persist(synced)
    return synced
  }

  const runSync = async (): Promise<Draft | null> => {
    if (!draft) return null
    setSyncing(true)
    setSyncError(null)
    setSyncMessage(null)
    try {
      const synced = await syncDraft(draft)
      const local = localTotalOf(synced)
      const server = synced.serverTotalPoints ?? 0
      if (Math.abs(local - server) > 0.001) {
        setSyncError(
          t('teacher.wizard.totalMismatch', { local: fmtNum(local), server: fmtNum(server) }),
        )
      } else {
        setSyncMessage(t('teacher.wizard.synced', { total: fmtNum(server) }))
      }
      return synced
    } catch (err) {
      setSyncError(errorMessage(err))
      return null
    } finally {
      setSyncing(false)
    }
  }

  const publish = async () => {
    if (!draft || draft.locked) return
    setPublishing(true)
    setSyncError(null)
    setSyncMessage(null)
    try {
      const synced = await syncDraft(draft)
      const local = localTotalOf(synced)
      const server = synced.serverTotalPoints ?? 0
      if (Math.abs(local - server) > 0.001) {
        setSyncError(
          t('teacher.wizard.totalMismatch', { local: fmtNum(local), server: fmtNum(server) }),
        )
        return
      }
      if (synced.sections.length === 0 || server <= 0) {
        setSyncError(t('teacher.wizard.errors.emptyPaper'))
        return
      }
      await api.post(`/api/papers/${synced.paperId}/publish`)
      setDraft({ ...synced, locked: true })
      setPublishedNow(true)
      try {
        localStorage.removeItem(STORAGE_KEY)
      } catch {
        // 隐私模式禁用 localStorage 时草稿清理失败，不影响已完成的发布。
      }
    } catch (err) {
      setSyncError(errorMessage(err))
    } finally {
      setPublishing(false)
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
        <ErrorState error={loadError} onRetry={() => setReloadKey((n) => n + 1)} />
      </TeacherPage>
    )
  }

  const applyBatchType = async () => {
    // 已发布试卷内容锁定：连本地草稿也不改，避免界面看起来改成功但服务端没变。
    if (!draft || draft.locked) return
    const from = Number(batchForm.from)
    const to = Number(batchForm.to)
    setBatchMessage(null)
    setBatchError(null)
    if (!Number.isInteger(from) || !Number.isInteger(to) || from <= 0 || to < from) {
      setBatchError(t('teacher.wizard.batchInvalid'))
      return
    }
    const targets = draft?.sections.flatMap((s) => s.items).filter((i) => i.number >= from && i.number <= to) ?? []
    if (targets.length === 0) {
      setBatchError(t('teacher.wizard.batchEmpty'))
      return
    }
    update((current) => ({
      ...current,
      sections: current.sections.map((section) => ({
        ...section,
        items: section.items.map((item) =>
          item.number >= from && item.number <= to ? { ...item, questionType: batchForm.type } : item),
      })),
    }))
    setBatchMessage(t('teacher.wizard.batchLocal', { n: targets.length }))
    if (draft?.paperId && !draft.locked && targets.some((i) => i.serverId)) {
      try {
        const result = await api.post<{ updated: number }>(`/api/papers/${draft.paperId}/items/question-type/batch`, {
          fromNumber: from, toNumber: to, questionType: batchForm.type,
        })
        setBatchMessage(t('teacher.wizard.batchApplied', { n: result.updated }))
        // 用服务端结果刷新（题型变更会规范标准答案），不再整页重载，避免丢掉本地未同步草稿。
        await refreshFromServer(draft.paperId)
      } catch (err) {
        setBatchError(errorMessage(err))
      }
    }
  }

  /**
   * 第 2 步导入：新建态先 ensurePaper（建卷 + 建部分），再按目标部分导入；
   * 导入成功后把服务端题项合并回本地草稿，第 3 步立刻可见。
   */
  const runImport = async () => {
    if (!draft || draft.locked) return
    setImporting(true)
    setImportError(null)
    setImportResult(null)
    try {
      // 文档里带「第X部分」时后端会自己建部分，先记下这次是不是临时补的默认部分
      const hadNoSections = draft.sections.length === 0
      const ensured = await ensurePaper()
      const pid = ensured.paperId
      if (!pid) throw new Error(t('teacher.wizard.errors.noDraft'))
      const preferredKey = importSectionKey || itemSectionKey
      const target =
        ensured.sections.find((section) => section.key === preferredKey) ?? ensured.sections[0]
      if (!target || !target.serverId) throw new Error(t('teacher.wizard.errors.needSection'))

      let imported: PaperImportResult
      if (importMode === 'file') {
        if (!importFile) throw new Error(t('teacher.wizard.errors.importFile'))
        const body = new FormData()
        body.append('file', importFile)
        const res = await fetch(`/api/papers/${pid}/import?sectionId=${target.serverId}`, {
          method: 'POST',
          credentials: 'include',
          body,
        })
        if (!res.ok) {
          const payload = await res.json().catch(() => null)
          throw new Error(payload?.detail || payload?.message || `HTTP ${res.status}`)
        }
        imported = (await res.json()) as PaperImportResult
      } else {
        if (!importText.trim()) throw new Error(t('teacher.wizard.errors.importText'))
        imported = await api.post<PaperImportResult>(`/api/papers/${pid}/import/text`, {
          text: importText,
          sectionId: target.serverId,
        })
      }

      // 多部分文档：导入时按文档结构建了部分，临时补的默认部分若还空着就删掉，别留个空壳
      if (hadNoSections && (imported.sections?.length ?? 0) > 1 && target.serverId) {
        const detail = await api.get<PaperDetail>(`/api/papers/${pid}`)
        const stillEmpty = detail.sections.find(
          (section) => section.id === target.serverId && section.items.length === 0,
        )
        if (stillEmpty) {
          await api.del(`/api/papers/${pid}/sections/${target.serverId}`).catch(() => undefined)
        }
      }
      setImportResult(imported)
      setImportFile(null)
      setImportText('')
      const detail = await api.get<PaperDetail>(`/api/papers/${pid}`)
      mergeFromServer(detail)
      setImportSectionKey(target.key)
      setItemSectionKey(target.key)
    } catch (err) {
      setImportError(errorMessage(err))
    } finally {
      setImporting(false)
    }
  }

  if (!draft) {
    return (
      <TeacherPage>
        <PageHeader title={t('teacher.wizard.title')} sub={t('teacher.wizard.sub')} />
        <ErrorState
          error={new Error(t('teacher.wizard.errors.noDraft'))}
          onRetry={() => setReloadKey((n) => n + 1)}
        />
      </TeacherPage>
    )
  }

  const localTotal = localTotalOf(draft)
  const serverTotal = draft.serverTotalPoints
  const mismatch = serverTotal !== null && Math.abs(localTotal - serverTotal) > 0.001
  const missing = missingAnswerCount(draft)
  const locked = draft.locked
  const step = draft.step

  const editingItem = editingItemKey
    ? draft.sections.flatMap((section) => section.items).find((item) => item.key === editingItemKey) ??
      null
    : null
  const selectedImportSectionKey =
    importSectionKey && draft.sections.some((section) => section.key === importSectionKey)
      ? importSectionKey
      : itemSectionKey && draft.sections.some((section) => section.key === itemSectionKey)
        ? itemSectionKey
        : (draft.sections[0]?.key ?? '')

  const stepLabels: Record<Step, string> = {
    1: t('teacher.wizard.steps.basic'),
    2: t('teacher.wizard.steps.content'),
    3: t('teacher.wizard.steps.answers'),
    4: t('teacher.wizard.steps.criteria'),
    5: t('teacher.wizard.steps.preview'),
  }

  return (
    <TeacherPage>
      <div className="breadcrumb">
        <Link to="/papers">{t('teacher.papers.title')}</Link> /{' '}
        <b>{editMode ? draft.title || t('teacher.wizard.title') : t('teacher.wizard.title')}</b>
      </div>

      <PageHeader
        title={editMode ? draft.title : t('teacher.wizard.title')}
        sub={t('teacher.wizard.sub')}
        actions={
          <Link className="btn sm" to="/papers">
            {t('teacher.wizard.exit')}
          </Link>
        }
      />

      {resumed && (
        <div className="inline-note">
          {editMode ? t('teacher.wizard.resumedEdit') : t('teacher.wizard.resumed')}
        </div>
      )}

      <div className="stepper">
        {STEP_IDS.map((id) => (
          <div
            key={id}
            className={`st${step === id ? ' on' : ''}${step > id ? ' done' : ''}`}
          >
            <b>{step > id ? '✓' : id}</b>
            {stepLabels[id]}
          </div>
        ))}
      </div>

      {draft.locked && (
        <div className="inline-note">{t('teacher.wizard.lockedNote')}</div>
      )}

      {step === 1 && (
        <div className="card">
          <h2>{t('teacher.wizard.step1Title')}</h2>
          <label className="f">
            <b>
              {t('teacher.wizard.fieldTitle')} <i>*</i>
            </b>
            <input
              type="text"
              value={draft.title}
              disabled={editMode}
              placeholder={t('teacher.wizard.fieldTitlePlaceholder')}
              onChange={(e) => update((current) => ({ ...current, title: e.target.value }))}
            />
            {editMode && <div className="hint">{t('teacher.wizard.titleReadOnly')}</div>}
          </label>
          <label className="f">
            <b>{t('teacher.wizard.fieldDuration')}</b>
            <input
              type="number"
              min={1}
              value={draft.defaultDurationMinutes}
              disabled={editMode}
              placeholder={t('teacher.wizard.fieldDurationPlaceholder')}
              onChange={(e) =>
                update((current) => ({ ...current, defaultDurationMinutes: e.target.value }))
              }
            />
            <div className="hint">{t('teacher.wizard.durationHint')}</div>
            {editMode && <div className="hint">{t('teacher.wizard.titleReadOnly')}</div>}
          </label>
          <div className="inline-note">{t('teacher.wizard.noClassNote')}</div>
          {formError && (
            <div className="hint" style={{ color: 'var(--red)' }}>
              {formError}
            </div>
          )}
        </div>
      )}

      {step === 2 && (
        <>
          <div className="card">
            <h2>{t('teacher.wizard.step2Title')}</h2>
            <div className="hint" style={{ marginBottom: 12 }}>
              {t('teacher.wizard.step2Hint')}
            </div>
            {!locked && (
              <div className="inline-form">
                <label className="f" style={{ flex: '1 1 240px' }}>
                  <b>{t('teacher.wizard.sectionTitle')}</b>
                  <input
                    value={sectionForm.title}
                    onChange={(e) => setSectionForm({ ...sectionForm, title: e.target.value })}
                    placeholder={t('teacher.wizard.sectionTitlePlaceholder')}
                  />
                </label>
                <label className="f" style={{ width: 150 }}>
                  <b>{t('teacher.wizard.pointsPerItem')}</b>
                  <input
                    type="number"
                    min={0}
                    step="0.5"
                    value={sectionForm.pointsPerItem}
                    onChange={(e) =>
                      setSectionForm({ ...sectionForm, pointsPerItem: e.target.value })
                    }
                  />
                </label>
                <button type="button" className="btn pri" onClick={addSection}>
                  {t('teacher.wizard.addSection')}
                </button>
              </div>
            )}
            {formError && (
              <div className="hint" style={{ color: 'var(--red)', marginBottom: 10 }}>
                {formError}
              </div>
            )}
            {draft.sections.length === 0 ? (
              <p className="muted">{t('teacher.wizard.noSections')}</p>
            ) : (
              <div className="wizard-list">
                <table>
                  <thead>
                    <tr>
                      <th>{t('teacher.wizard.colSection')}</th>
                      <th>{t('teacher.wizard.colPointsPerItem')}</th>
                      <th>{t('teacher.wizard.colItemCount')}</th>
                      <th>{t('teacher.wizard.colCriteriaCount')}</th>
                      <th>{t('teacher.wizard.colSyncState')}</th>
                      <th>{t('teacher.common.actions')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {draft.sections.map((section) => (
                      <tr key={section.key}>
                        <td>
                          {editingSectionKey === section.key ? (
                            <input
                              value={sectionEdit.title}
                              onChange={(e) =>
                                setSectionEdit({ ...sectionEdit, title: e.target.value })
                              }
                            />
                          ) : (
                            <b>{section.title}</b>
                          )}
                        </td>
                        <td className="mono">
                          {editingSectionKey === section.key ? (
                            <input
                              type="number"
                              min={0}
                              step="0.5"
                              style={{ width: 92 }}
                              value={sectionEdit.pointsPerItem}
                              onChange={(e) =>
                                setSectionEdit({ ...sectionEdit, pointsPerItem: e.target.value })
                              }
                            />
                          ) : (
                            fmtNum(section.pointsPerItem)
                          )}
                        </td>
                        <td className="mono">{section.items.length}</td>
                        <td className="mono">{section.criteria.length}</td>
                        <td>
                          {section.serverId ? (
                            <Tag tone="green">{t('teacher.wizard.saved')}</Tag>
                          ) : (
                            <Tag tone="gray">{t('teacher.wizard.unsaved')}</Tag>
                          )}
                        </td>
                        <td>
                          {locked ? (
                            <span className="muted">—</span>
                          ) : editingSectionKey === section.key ? (
                            <div className="row" style={{ gap: 6 }}>
                              <button
                                type="button"
                                className="btn sm"
                                disabled={rowBusy === `section:${section.key}`}
                                onClick={() => void saveSectionEdit(section)}
                              >
                                {t('teacher.wizard.save')}
                              </button>
                              <button type="button" className="btn sm" onClick={cancelEditSection}>
                                {t('teacher.common.cancel')}
                              </button>
                            </div>
                          ) : deletingSectionKey === section.key ? (
                            <div className="row" style={{ gap: 6 }}>
                              <span className="small" style={{ color: 'var(--red)' }}>
                                {t('teacher.wizard.deleteSectionConfirm', {
                                  n: section.items.length,
                                })}
                              </span>
                              <button
                                type="button"
                                className="btn sm"
                                disabled={rowBusy === `section:${section.key}`}
                                onClick={() => void removeSection(section)}
                              >
                                {t('teacher.wizard.deleteConfirm')}
                              </button>
                              <button
                                type="button"
                                className="btn sm"
                                onClick={() => setDeletingSectionKey(null)}
                              >
                                {t('teacher.common.cancel')}
                              </button>
                            </div>
                          ) : (
                            <div className="row" style={{ gap: 6 }}>
                              <button
                                type="button"
                                className="btn sm"
                                onClick={() => startEditSection(section)}
                              >
                                {t('teacher.common.edit')}
                              </button>
                              <button
                                type="button"
                                className="btn sm"
                                onClick={() => {
                                  setDeletingSectionKey(section.key)
                                  setEditingSectionKey(null)
                                }}
                              >
                                {t('teacher.wizard.delete')}
                              </button>
                            </div>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <div className="hint">{t('teacher.wizard.editLimitation')}</div>
          </div>

          <div className="card">
            <h2>{t('teacher.wizard.importTitle')}</h2>
            <div className="hint" style={{ marginBottom: 12 }}>{t('teacher.wizard.importHint')}</div>
            {locked ? (
              <p className="muted">{t('teacher.wizard.importLocked')}</p>
            ) : (
              <>
                <div className="row" style={{ marginBottom: 10, alignItems: 'flex-end' }}>
                  <button
                    type="button"
                    className={`chip${importMode === 'file' ? ' on' : ''}`}
                    onClick={() => setImportMode('file')}
                  >
                    {t('teacher.wizard.importModeFile')}
                  </button>
                  <button
                    type="button"
                    className={`chip${importMode === 'text' ? ' on' : ''}`}
                    onClick={() => setImportMode('text')}
                  >
                    {t('teacher.wizard.importModeText')}
                  </button>
                  {draft.sections.length > 0 && (
                    <label className="f" style={{ margin: 0, minWidth: 180, flex: '0 1 240px' }}>
                      <b>{t('teacher.wizard.importTarget')}</b>
                      <select
                        value={selectedImportSectionKey}
                        onChange={(e) => setImportSectionKey(e.target.value)}
                      >
                        {draft.sections.map((section) => (
                          <option key={section.key} value={section.key}>
                            {section.title}
                          </option>
                        ))}
                      </select>
                    </label>
                  )}
                </div>
                <div className="hint" style={{ marginBottom: 8 }}>{t('teacher.wizard.importTargetOnlyFlat')}</div>
                {importMode === 'file' ? (
                  <div className="row">
                    <input
                      type="file"
                      accept=".docx,.md,.txt,.xlsx"
                      onChange={(e) => {
                        setImportFile(e.target.files?.[0] ?? null)
                        setImportResult(null)
                        setImportError(null)
                      }}
                    />
                    <button
                      type="button"
                      className="btn pri"
                      disabled={!importFile || importing}
                      onClick={() => void runImport()}
                    >
                      {importing ? t('teacher.common.loading') : t('teacher.wizard.importRun')}
                    </button>
                  </div>
                ) : (
                  <div>
                    <textarea
                      rows={6}
                      style={{ width: '100%', fontFamily: 'monospace' }}
                      placeholder={t('teacher.wizard.importPastePlaceholder')}
                      value={importText}
                      onChange={(e) => setImportText(e.target.value)}
                    />
                    <div className="row" style={{ marginTop: 8 }}>
                      <button
                        type="button"
                        className="btn pri"
                        disabled={!importText.trim() || importing}
                        onClick={() => void runImport()}
                      >
                        {importing ? t('teacher.common.loading') : t('teacher.wizard.importRun')}
                      </button>
                    </div>
                  </div>
                )}
                {!draft.paperId && (
                  <div className="hint">{t('teacher.wizard.importNeedPaper')}</div>
                )}
                <div className="hint">{t('teacher.wizard.importRequirements')}</div>
                {importError && <div className="hint" style={{ color: 'var(--red)' }}>{importError}</div>}
                {importResult && (
                  <div style={{ marginTop: 12 }}>
                    <div className="row" style={{ gap: 8 }}>
                      <Tag tone="green">{t('teacher.wizard.importImported', { n: importResult.imported })}</Tag>
                      {importResult.missingAnswers.length > 0 && (
                        <Tag tone="amber">{t('teacher.wizard.importMissing', { list: importResult.missingAnswers.join(', ') })}</Tag>
                      )}
                    </div>
                    {(importResult.sections?.length ?? 0) > 1 && (
                      <>
                        <div className="hint" style={{ marginTop: 8 }}>{t('teacher.wizard.importSectionsNote')}</div>
                        <div className="row" style={{ gap: 8, marginTop: 6, flexWrap: 'wrap' }}>
                          {importResult.sections?.map((section) => (
                            <Tag key={section.title} tone="blue">
                              {t('teacher.wizard.importSectionTag', {
                                title: section.title,
                                n: section.itemCount,
                                points: section.pointsPerItem ?? '—',
                              })}
                            </Tag>
                          ))}
                        </div>
                      </>
                    )}
                    {importResult.warnings.length > 0 && (
                      <ul className="small muted" style={{ marginTop: 8, paddingLeft: 20 }}>
                        {importResult.warnings.map((w, i) => <li key={i}>{w}</li>)}
                      </ul>
                    )}
                    {importResult.items.length > 0 && (
                      <table style={{ marginTop: 10 }}>
                        <thead>
                          <tr>
                            <th>{t('teacher.wizard.colNumber')}</th>
                            <th>{t('teacher.wizard.colStem')}</th>
                            <th>{t('teacher.wizard.colAnswer')}</th>
                          </tr>
                        </thead>
                        <tbody>
                          {importResult.items.map((item) => (
                            <tr key={item.number}>
                              <td className="mono">{item.number}</td>
                              <td className="small">{item.stem}</td>
                              <td className={item.answerKey ? 'mono' : ''}>
                                {item.answerKey ?? <Tag tone="amber">{t('teacher.wizard.importNoAnswer')}</Tag>}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </div>
                )}
              </>
            )}
          </div>

          {!locked && (
          <div className="card">
            <h2>{t('teacher.wizard.batchTitle')}</h2>
            <div className="hint" style={{ marginBottom: 10 }}>{t('teacher.wizard.batchHint')}</div>
            <div
              className="row"
              style={{ alignItems: 'flex-end', flexWrap: 'wrap', rowGap: 10, columnGap: 10 }}
            >
              <label className="f" style={{ margin: 0, minWidth: 112, flex: '0 1 140px' }}>
                <b>{t('teacher.wizard.batchFrom')}</b>
                <input
                  type="number"
                  min={1}
                  value={batchForm.from}
                  onChange={(e) => setBatchForm({ ...batchForm, from: e.target.value })}
                />
              </label>
              <label className="f" style={{ margin: 0, minWidth: 112, flex: '0 1 140px' }}>
                <b>{t('teacher.wizard.batchTo')}</b>
                <input
                  type="number"
                  min={1}
                  value={batchForm.to}
                  onChange={(e) => setBatchForm({ ...batchForm, to: e.target.value })}
                />
              </label>
              <label className="f" style={{ margin: 0, minWidth: 170, flex: '1 1 190px' }}>
                <b>{t('teacher.wizard.questionType')}</b>
                <select
                  value={batchForm.type}
                  onChange={(e) => setBatchForm({ ...batchForm, type: e.target.value as QType })}
                >
                  {QUESTION_TYPES.map((type) => (
                    <option key={type} value={type}>
                      {t(`teacher.questionType.${type}`)}
                    </option>
                  ))}
                </select>
              </label>
              <button
                type="button"
                className="btn"
                style={{ marginBottom: 0 }}
                onClick={() => void applyBatchType()}
              >
                {t('teacher.wizard.batchApply')}
              </button>
            </div>
            {batchMessage && <div className="hint">{batchMessage}</div>}
            {batchError && <div className="hint" style={{ color: 'var(--red)' }}>{batchError}</div>}
          </div>
          )}
        </>
      )}

      {step === 3 && (
        <>
          <div className="card">
            <h2>{t('teacher.wizard.step3Title')}</h2>
            <div className="hint" style={{ marginBottom: 12 }}>
              {t('teacher.wizard.step3Hint')}
            </div>
            {editingItem && !locked && (
              <div className="inline-note">
                {t('teacher.wizard.itemEditing', { number: editingItem.number })}
              </div>
            )}
            {draft.sections.length === 0 ? (
              <p className="muted">{t('teacher.wizard.needSectionFirst')}</p>
            ) : (
              !locked && (
                <div className="inline-form">
                  <label className="f" style={{ minWidth: 160 }}>
                    <b>{t('teacher.wizard.section')}</b>
                    <select
                      value={itemSectionKey}
                      onChange={(e) => {
                        setItemSectionKey(e.target.value)
                        const section = draft.sections.find((s) => s.key === e.target.value)
                        const nextNumber =
                          Math.max(0, ...(section?.items ?? []).map((item) => item.number)) + 1
                        setItemForm({ ...itemForm, number: String(nextNumber) })
                      }}
                    >
                      {draft.sections.map((section) => (
                        <option key={section.key} value={section.key}>
                          {section.title}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="f" style={{ width: 110 }}>
                    <b>{t('teacher.wizard.itemNumber')}</b>
                    <input
                      type="number"
                      min={1}
                      disabled={Boolean(editingItem?.serverId)}
                      value={itemForm.number}
                      onChange={(e) => setItemForm({ ...itemForm, number: e.target.value })}
                    />
                  </label>
                  <label className="f" style={{ minWidth: 160 }}>
                    <b>{t('teacher.wizard.questionType')}</b>
                    <select
                      value={itemForm.questionType}
                      onChange={(e) =>
                        setItemForm({ ...itemForm, questionType: e.target.value as QType })
                      }
                    >
                      {QUESTION_TYPES.map((type) => (
                        <option key={type} value={type}>
                          {t(`teacher.questionType.${type}`)}
                        </option>
                      ))}
                    </select>
                  </label>
                  {itemForm.questionType === 'MULTIPLE_CHOICE' && (
                    <label className="f" style={{ minWidth: 170 }}>
                      <b>{t('teacher.wizard.scoringStrategy')}</b>
                      <select
                        value={itemForm.scoringStrategy}
                        onChange={(e) => setItemForm({ ...itemForm, scoringStrategy: e.target.value as ScoringStrategy })}
                      >
                        <option value="ALL_OR_NOTHING">{t('teacher.wizard.strategyAllOrNothing')}</option>
                        <option value="PARTIAL">{t('teacher.wizard.strategyPartial')}</option>
                      </select>
                    </label>
                  )}
                  <label className="f" style={{ width: 96 }}>
                    <b>{t('teacher.wizard.itemPoints')}</b>
                    <input
                      type="number"
                      min={0.5}
                      step="0.5"
                      value={itemForm.points}
                      onChange={(e) => setItemForm({ ...itemForm, points: e.target.value })}
                    />
                  </label>
                  <label className="f" style={{ flex: '1 1 180px' }}>
                    <b>{t('teacher.wizard.answerKey')}</b>
                    <input
                      value={itemForm.answerKey}
                      disabled={itemForm.questionType === 'ESSAY'}
                      placeholder={t('teacher.wizard.answerKeyPlaceholder')}
                      onChange={(e) => setItemForm({ ...itemForm, answerKey: e.target.value })}
                    />
                  </label>
                  <label className="f" style={{ flex: '1 1 220px' }}>
                    <b>{t('teacher.wizard.stem')}</b>
                    <input
                      value={itemForm.stem}
                      placeholder={t('teacher.wizard.optional')}
                      onChange={(e) => setItemForm({ ...itemForm, stem: e.target.value })}
                    />
                  </label>
                  <label className="f" style={{ flex: '1 1 180px' }}>
                    <b>{t('teacher.wizard.options')}</b>
                    <input
                      value={itemForm.options}
                      placeholder={t('teacher.wizard.optionsPlaceholder')}
                      onChange={(e) => setItemForm({ ...itemForm, options: e.target.value })}
                    />
                  </label>
                  {editingItemKey ? (
                    <>
                      <button
                        type="button"
                        className="btn pri"
                        disabled={rowBusy === `item:${editingItemKey}`}
                        onClick={() => void saveItemEdit()}
                      >
                        {t('teacher.wizard.save')}
                      </button>
                      <button type="button" className="btn" onClick={cancelEditItem}>
                        {t('teacher.common.cancel')}
                      </button>
                    </>
                  ) : (
                    <button type="button" className="btn pri" onClick={addItem}>
                      {t('teacher.wizard.addItem')}
                    </button>
                  )}
                </div>
              )
            )}
            {formError && (
              <div className="hint" style={{ color: 'var(--red)', marginBottom: 10 }}>
                {formError}
              </div>
            )}
            {draft.sections.length > 0 && (
              <div className="wizard-list">
                <table>
                  <thead>
                    <tr>
                      <th>{t('teacher.wizard.colSection')}</th>
                      <th>{t('teacher.wizard.colNumber')}</th>
                      <th>{t('teacher.wizard.colType')}</th>
                      <th>{t('teacher.wizard.colPoints')}</th>
                      <th>{t('teacher.wizard.colAnswer')}</th>
                      <th>{t('teacher.wizard.colSyncState')}</th>
                      <th>{t('teacher.common.actions')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {draft.sections.flatMap((section) =>
                      section.items.map((item) => (
                        <tr key={item.key}>
                          <td>{section.title}</td>
                          <td className="mono">{item.number}</td>
                          <td>{t(`teacher.questionType.${item.questionType}`)}</td>
                          <td className="mono">{fmtNum(item.points)}</td>
                          <td>
                            {item.questionType === 'ESSAY' ? (
                              <span className="muted">{t('teacher.wizard.essayNoKey')}</span>
                            ) : item.answerKey.trim() ? (
                              <span className="mono">{item.answerKey}</span>
                            ) : (
                              <Tag tone="red">{t('teacher.wizard.missingAnswer')}</Tag>
                            )}
                          </td>
                          <td>
                            {item.serverId ? (
                              <Tag tone="green">{t('teacher.wizard.saved')}</Tag>
                            ) : (
                              <Tag tone="gray">{t('teacher.wizard.unsaved')}</Tag>
                            )}
                          </td>
                          <td>
                            {locked ? (
                              <span className="muted">—</span>
                            ) : deletingItemKey === item.key ? (
                              <div className="row" style={{ gap: 6 }}>
                                <span className="small" style={{ color: 'var(--red)' }}>
                                  {t('teacher.wizard.deleteItemConfirm', { number: item.number })}
                                </span>
                                <button
                                  type="button"
                                  className="btn sm"
                                  disabled={rowBusy === `item:${item.key}`}
                                  onClick={() => void removeItem(section, item)}
                                >
                                  {t('teacher.wizard.deleteConfirm')}
                                </button>
                                <button
                                  type="button"
                                  className="btn sm"
                                  onClick={() => setDeletingItemKey(null)}
                                >
                                  {t('teacher.common.cancel')}
                                </button>
                              </div>
                            ) : (
                              <div className="row" style={{ gap: 6 }}>
                                <button
                                  type="button"
                                  className="btn sm"
                                  disabled={rowBusy === `item:${item.key}`}
                                  onClick={() => startEditItem(section, item)}
                                >
                                  {t('teacher.common.edit')}
                                </button>
                                <button
                                  type="button"
                                  className="btn sm"
                                  onClick={() => {
                                    setDeletingItemKey(item.key)
                                    setEditingItemKey(null)
                                  }}
                                >
                                  {t('teacher.wizard.delete')}
                                </button>
                              </div>
                            )}
                          </td>
                        </tr>
                      )),
                    )}
                  </tbody>
                </table>
              </div>
            )}
            <div className="hint">{t('teacher.wizard.editLimitation')}</div>
          </div>
        </>
      )}

      {step === 4 && (
        <div className="card">
          <h2>{t('teacher.wizard.step4Title')}</h2>
          <div className="hint" style={{ marginBottom: 12 }}>
            {t('teacher.wizard.step4Hint')}
          </div>
          {draft.sections.length === 0 ? (
            <p className="muted">{t('teacher.wizard.needSectionFirst')}</p>
          ) : (
            !locked && (
              <div className="inline-form">
                <label className="f" style={{ minWidth: 160 }}>
                  <b>{t('teacher.wizard.section')}</b>
                  <select
                    value={criteriaSectionKey}
                    onChange={(e) => setCriteriaSectionKey(e.target.value)}
                  >
                    {draft.sections.map((section) => (
                      <option key={section.key} value={section.key}>
                        {section.title}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="f" style={{ flex: '1 1 180px' }}>
                  <b>{t('teacher.wizard.criteriaName')}</b>
                  <input
                    value={criteriaForm.name}
                    onChange={(e) => setCriteriaForm({ ...criteriaForm, name: e.target.value })}
                    placeholder={t('teacher.wizard.criteriaNamePlaceholder')}
                  />
                </label>
                <label className="f" style={{ width: 110 }}>
                  <b>{t('teacher.wizard.maxPoints')}</b>
                  <input
                    type="number"
                    min={0.5}
                    step="0.5"
                    value={criteriaForm.maxPoints}
                    onChange={(e) =>
                      setCriteriaForm({ ...criteriaForm, maxPoints: e.target.value })
                    }
                  />
                </label>
                <label className="f" style={{ flex: '1 1 220px' }}>
                  <b>{t('teacher.wizard.criteriaDescription')}</b>
                  <input
                    value={criteriaForm.description}
                    placeholder={t('teacher.wizard.optional')}
                    onChange={(e) =>
                      setCriteriaForm({ ...criteriaForm, description: e.target.value })
                    }
                  />
                </label>
                <button type="button" className="btn pri" onClick={addCriteria}>
                  {t('teacher.wizard.addCriteria')}
                </button>
              </div>
            )
          )}
          {formError && (
            <div className="hint" style={{ color: 'var(--red)', marginBottom: 10 }}>
              {formError}
            </div>
          )}

          {draft.sections.some((s) => s.criteria.length > 0) && (
            <div className="wizard-list" style={{ marginBottom: 12 }}>
              <table>
                <thead>
                  <tr>
                    <th>{t('teacher.wizard.colSection')}</th>
                    <th>{t('teacher.wizard.colCriteriaName')}</th>
                    <th>{t('teacher.wizard.colMaxPoints')}</th>
                    <th>{t('teacher.wizard.colDescription')}</th>
                    <th>{t('teacher.wizard.colSyncState')}</th>
                  </tr>
                </thead>
                <tbody>
                  {draft.sections.flatMap((section) =>
                    section.criteria.map((criterion) => (
                      <tr key={criterion.key}>
                        <td>{section.title}</td>
                        <td>
                          <b>{criterion.name}</b>
                        </td>
                        <td className="mono">{fmtNum(criterion.maxPoints)}</td>
                        <td className="small muted">{criterion.description || '—'}</td>
                        <td>
                          {criterion.serverId ? (
                            <Tag tone="green">{t('teacher.wizard.saved')}</Tag>
                          ) : (
                            <Tag tone="gray">{t('teacher.wizard.unsaved')}</Tag>
                          )}
                        </td>
                      </tr>
                    )),
                  )}
                </tbody>
              </table>
            </div>
          )}

          {draft.legacyCriteriaNames.length > 0 && (
            <div className="card" style={{ background: 'var(--panel-2)', boxShadow: 'none' }}>
              <h2>
                {t('teacher.wizard.legacyCriteria')}{' '}
                <small>{t('teacher.wizard.legacyCriteriaHint')}</small>
              </h2>
              <div className="chips" style={{ marginBottom: 0 }}>
                {draft.legacyCriteriaNames.map((name, index) => (
                  <span key={`${name}-${index}`} className="tag gray">
                    {name}
                  </span>
                ))}
              </div>
              <div className="hint">
                {t('teacher.wizard.legacyCriteriaTotal', {
                  total: fmtNum(draft.existingCriteriaTotal),
                })}
              </div>
            </div>
          )}
          <div className="hint">{t('teacher.wizard.editLimitation')}</div>
        </div>
      )}

      {step === 5 && (
        <>
          {publishedNow && (
            <div className="inline-note ok">
              <b>{t('teacher.wizard.publishSuccess')}</b>
              <div className="row" style={{ marginTop: 8 }}>
                <Link className="btn pri" to={`/offerings/new?paperId=${draft.paperId ?? ''}`}>
                  {t('teacher.wizard.assignNow')}
                </Link>
                <Link className="btn" to="/papers">
                  {t('teacher.wizard.backToPapers')}
                </Link>
              </div>
            </div>
          )}

          <div className="card">
            <h2>{t('teacher.wizard.step5Title')}</h2>
            <div className="stats" style={{ marginBottom: 12 }}>
              <div className="stat flat">
                <div className="n">{fmtNum(itemsTotalOf(draft))}</div>
                <div className="l">{t('teacher.wizard.itemsTotal')}</div>
              </div>
              <div className="stat flat">
                <div className="n">{fmtNum(criteriaTotalOf(draft))}</div>
                <div className="l">{t('teacher.wizard.criteriaTotal')}</div>
              </div>
              <div className="stat flat">
                <div className="n">{fmtNum(localTotal)}</div>
                <div className="l">{t('teacher.wizard.localTotal')}</div>
              </div>
              <div className="stat flat">
                <div className="n">{serverTotal === null ? '—' : fmtNum(serverTotal)}</div>
                <div className="l">{t('teacher.wizard.serverTotal')}</div>
              </div>
            </div>

            {serverTotal === null ? (
              <div className="hint">{t('teacher.wizard.notSynced')}</div>
            ) : mismatch ? (
              <div className="inline-note" style={{ borderLeftColor: 'var(--red)' }}>
                {t('teacher.wizard.totalMismatch', {
                  local: fmtNum(localTotal),
                  server: fmtNum(serverTotal),
                })}
              </div>
            ) : (
              <div className="inline-note ok">
                {t('teacher.wizard.totalMatch', { total: fmtNum(serverTotal) })}
              </div>
            )}

            {missing > 0 && (
              <div className="inline-note" style={{ borderLeftColor: 'var(--red)' }}>
                {t('teacher.wizard.missingAnswersNote', { n: missing })}
              </div>
            )}
            {syncError && (
              <div className="inline-note" style={{ borderLeftColor: 'var(--red)' }}>
                {syncError}
              </div>
            )}
            {syncMessage && <div className="inline-note ok">{syncMessage}</div>}

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
                  {draft.sections.length === 0 ? (
                    <tr>
                      <td colSpan={4} className="muted">
                        {t('teacher.wizard.noSections')}
                      </td>
                    </tr>
                  ) : (
                    draft.sections.map((section) => {
                      const sectionPoints = section.items.reduce((sum, item) => sum + item.points, 0)
                      return (
                        <tr key={section.key}>
                          <td>
                            <b>{section.title}</b>
                          </td>
                          <td className="mono">
                            {section.items.length === 0
                              ? '—'
                              : section.items.map((item) => item.number).join(', ')}
                          </td>
                          <td className="mono">{fmtNum(sectionPoints)}</td>
                          <td className="small">
                            {section.criteria.length === 0
                              ? '—'
                              : section.criteria
                                  .map((c) => `${c.name} (${fmtNum(c.maxPoints)})`)
                                  .join('、')}
                          </td>
                        </tr>
                      )
                    })
                  )}
                </tbody>
              </table>
            </div>

            <h3 style={{ fontSize: 14, margin: '16px 0 8px' }}>
              {t('teacher.wizard.rubricTitle')}
            </h3>
            <label className="f">
              <b>{t('teacher.wizard.rubricMarkdown')}</b>
              <textarea
                rows={5}
                value={draft.rubricMarkdown}
                disabled={locked}
                placeholder={t('teacher.wizard.rubricPlaceholder')}
                onChange={(e) =>
                  update((current) => ({
                    ...current,
                    rubricMarkdown: e.target.value,
                    rubricTouched: true,
                  }))
                }
              />
            </label>
            <label className="f">
              <b>{t('teacher.wizard.gradingPrompt')}</b>
              <textarea
                rows={4}
                value={draft.gradingPromptTemplate}
                disabled={locked}
                placeholder={t('teacher.wizard.gradingPromptPlaceholder')}
                onChange={(e) =>
                  update((current) => ({
                    ...current,
                    gradingPromptTemplate: e.target.value,
                    rubricTouched: true,
                  }))
                }
              />
              <div className="hint">{t('teacher.wizard.rubricHint')}</div>
            </label>

            <div className="row" style={{ marginTop: 8 }}>
              <button
                type="button"
                className="btn"
                disabled={syncing || publishing || locked}
                onClick={() => void runSync()}
              >
                {syncing ? t('teacher.common.loading') : t('teacher.wizard.sync')}
              </button>
              <button
                type="button"
                className="btn pri"
                disabled={publishing || syncing || locked || mismatch || missing > 0}
                onClick={() => void publish()}
              >
                {publishing ? t('teacher.common.saving') : t('teacher.wizard.publish')}
              </button>
              {locked && <Tag tone="green">{t('teacher.wizard.published')}</Tag>}
              {mismatch && <span className="tag red">{t('teacher.wizard.publishBlocked')}</span>}
              {missing > 0 && <span className="tag red">{t('teacher.wizard.publishBlockedAnswers')}</span>}
            </div>
          </div>
        </>
      )}

      <div className="row" style={{ justifyContent: 'space-between', marginTop: 4 }}>
        <button type="button" className="btn" disabled={step <= 1} onClick={goPrev}>
          {t('teacher.common.prev')}
        </button>
        <span className="small muted">{t('teacher.wizard.draftSavedAt', { key: STORAGE_KEY })}</span>
        <button type="button" className="btn pri" disabled={step >= 5} onClick={goNext}>
          {t('teacher.common.next')}
        </button>
      </div>
    </TeacherPage>
  )
}
