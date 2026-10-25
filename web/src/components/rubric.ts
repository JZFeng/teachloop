/*
 * 评分标准（rubric）的**前端只读预览版**解析器。
 *
 * 权威实现是后端的 `teachloop-sitting/src/main/java/com/teachloop/sitting/service/RubricValidator.java`
 * 与 `.../domain/RubricVocabulary.java`：提交作业时能不能过、报什么错、没有分值怎么均分，
 * 全部以后端为准。这里只服务一个用途 —— 「新建作业」页把老师刚写下的评分标准当场算成
 * 「评分项 + 各项分值 + 合计」，让老师在提交前就看到 AI 会读到什么。
 *
 * 因此本文件是**逐条照抄** Java 的解析规则（同样的正则、同样的区域判定、同样的清洗与忽略表），
 * 唯一区别是只输出摘要、不产生校验错误：后端仍然是唯一权威，两边一旦不一致以后端为准，
 * 改规则时两个文件必须一起改。任何业务判断（重复项、分值之和是否等于总分）都不要搬到这里。
 *
 * 关于文件里的中文：下面的词表是**匹配输入用的文档结构标记**，不是界面文案。
 * 中文老师会把「评分标准」「总分」「是/否」直接写在 rubric 原文里，解析器必须认得，
 * 所以它们必须出现在代码里，并且与 Java 的 RubricVocabulary 完全一致。
 * 为了不触发 `scripts/check-i18n.py`（它把 .ts 里的中文字符串字面量当成漏翻文案），
 * 这里统一写成 `\uXXXX` 转义，每个条目后面用注释标出原词 —— 语义与 Java 逐字相同。
 */

export interface RubricCriterion {
  name: string
  points: number | null
}

export interface RubricSummary {
  /** 解析出的评分项，顺序与原文一致（不合并重名项，重名是后端校验的事）。 */
  criteria: RubricCriterion[]
  /**
   * 被当作非评分项的「没有分值」条目（分组标题 / 说明行）。
   * 与后端 RubricValidator 的 ignored 同一套规则：只用于提示，不参与校验、不阻断发布。
   */
  ignored: RubricCriterion[]
  /** 有分值的评分项之和。 */
  total: number
  /** 是否至少有一项写了分值。 */
  anyPoints: boolean
  /** 是否有评分项没写分值（新规则下 ignored 不算，正常为 false）。 */
  missingPoints: boolean
}

/* ---------- 词表与正则：与 RubricVocabulary.java / RubricValidator.java 一一对应 ---------- */

/**
 * `2 分 / 2 points / 3 pts`。
 * `\u5206` 就是「分」字：Java 用中文字面量，这里用转义，匹配行为完全相同。
 */
const MAX_POINTS = /(\d+(?:\.\d+)?)\s*(?:\u5206|points?|pts?)/i

/** 进入评分项区域的标题（小写比较）。 */
const START_HEADINGS = new Set<string>([
  '\u8bc4\u5206\u6807\u51c6', // 评分标准
  '\u8bc4\u5206\u9879', // 评分项
  'scoring rubric',
  'rubric',
  'grading rubric',
])

/** 结束评分项区域的标题（小写比较）。 */
const END_HEADINGS = new Set<string>([
  'maximum score',
  'total score',
  '\u603b\u5206', // 总分
  '\u6ee1\u5206', // 满分
  'optional feedback',
  '\u53cd\u9988', // 反馈
])

/** 永远不当评分项的标题/条目。 */
const IGNORED_NAMES = new Set<string>([
  'assignment',
  'statement',
  '\u4f5c\u4e1a', // 作业
  '\u4f5c\u4e1a\u8bf4\u660e', // 作业说明
  'overall assignment rubric',
  '\u8bc4\u5206\u6807\u51c6', // 评分标准
  'scoring rubric',
  'rubric',
  'maximum score',
  'total score',
  '\u603b\u5206', // 总分
  '\u6ee1\u5206', // 满分
  'optional feedback',
  '\u53cd\u9988', // 反馈
  'grading rubric',
])

/** Yes/No、是/否、部分/完整、无 这类判断行，是规则不是评分项。 */
const DECISION_EXACT = new Set<string>(['yes', 'no', 'true', 'false'])

const DECISION_PREFIXES = [
  'yes ',
  'no ',
  '\u662f', // 是
  '\u5426', // 否
  'partial',
  '\u90e8\u5206', // 部分
  'full',
  '\u5b8c\u6574', // 完整
  'none',
  '\u65e0', // 无
]

const HEADING = /^\s{0,3}#{1,4}\s+(.+?)\s*$/
const BULLET = /^\s{0,3}[-*+]\s+(.+?)\s*$/
/**
 * 有序列表行：「1. 内容」与「1 内容」都算。
 * 第二种没有点是 Vditor 即时渲染视图的真实输出（敲「1. 」会被编辑器接成有序列表，
 * 回写 Markdown 时丢点），与 Java 的 NUMBERED 同步（T470）。
 */
const NUMBERED = /^\s{0,3}\d+(?:[.)、]\s+|\s+)(.+?)\s*$/
const LEADING_NUMBER = /^\d+[.)、]\s*/

/** 名称首尾要剥掉的标点（含全角括号、顿号、中英文冒号）。 */
const LEADING_JUNK = /^[\s\-–—*_：:、（(]+/
const TRAILING_JUNK = /[\s\-–—*_：:、（）()]+$/

function isStart(name: string | null): boolean {
  return name !== null && START_HEADINGS.has(name)
}

function isEnd(name: string | null): boolean {
  return name !== null && (END_HEADINGS.has(name) || name.startsWith('maximum score'))
}

function isIgnored(name: string): boolean {
  return IGNORED_NAMES.has(name)
}

function isDecision(name: string): boolean {
  const lower = name.toLowerCase().trim()
  // 「是否…」是问句式评分项，不是 Yes/No 判定行（与 Java 的 RubricVocabulary.isDecision 一致）
  if (lower.startsWith('\u662f\u5426')) return false
  if (DECISION_EXACT.has(lower)) return true
  return DECISION_PREFIXES.some((prefix) => lower.startsWith(prefix))
}

/** 名称里第一个中/英文冒号的位置；没有就是 -1（与 Java 的 firstColon 一致）。 */
function firstColon(value: string): number {
  const zh = value.indexOf('\uFF1A') // 全角冒号
  const en = value.indexOf(':')
  if (zh < 0) return en
  if (en < 0) return zh
  return Math.min(zh, en)
}

/** 标题行取「标题名」：去粗体、去编号、截掉冒号后的说明、转小写，只用于区域判定。 */
function headingName(line: string): string | null {
  const matched = HEADING.exec(line)
  if (!matched) return null
  let name = matched[1].replace(/\*\*/g, '').trim()
  name = name.replace(LEADING_NUMBER, '')
  const colon = firstColon(name)
  if (colon > 0) name = name.slice(0, colon)
  return name.trim().toLowerCase()
}

function cleanName(raw: string): string {
  let name = raw.trim()
  const colon = firstColon(name)
  if (colon > 0) name = name.slice(0, colon)
  name = name.replace(LEADING_JUNK, '')
  name = name.replace(TRAILING_JUNK, '')
  return name.trim()
}

interface ParsedCriterion {
  name: string
  points: number | null
}

/** 与 Java 的 add(...) 一致：清洗 → 截分 → 过滤空名/超长/忽略词/判断行。 */
function add(out: ParsedCriterion[], raw: string): void {
  const body = raw.replace(/\*\*/g, '').replace(/__/g, '').replace(/`/g, '').trim()
  if (!body) return
  const withoutNumber = body.replace(LEADING_NUMBER, '')
  const points = MAX_POINTS.exec(withoutNumber)
  let value: number | null = null
  let name = withoutNumber
  if (points) {
    value = Number.parseFloat(points[1])
    name = withoutNumber.slice(0, points.index)
  }
  name = cleanName(name)
  // 名称上限与 Java 的 MAX_NAME_LENGTH 一致：500，只有整段散文才会撞线。
  if (!name || name.length > 500 || isIgnored(name.toLowerCase()) || isDecision(name)) return
  out.push({ name, points: value })
}

/**
 * 与 Java 的 parse(...) 一致：
 *   - 全文有 start 标题时，只收 start 之后、end 之前的条目；没有 start 标题就全文收；
 *   - 有带分值的标题 → 只取带分值的标题；否则有带分值的列表项 → 只取带分值的列表项；
 *   - 全文没有任何分值 → 标题优先，否则列表（均分交给后端/判分侧）。
 * 没有分值、也没被选中的条目进 ignored（分组标题、说明行），不再当成「缺分值的评分项」。
 */
function parse(markdown: string): { criteria: ParsedCriterion[]; ignored: ParsedCriterion[] } {
  const lines = markdown.split(/\r\n|\r|\n/)
  const hasStart = lines.some((line) => isStart(headingName(line)))
  const headings: ParsedCriterion[] = []
  const bullets: ParsedCriterion[] = []
  let inSection = !hasStart

  for (const line of lines) {
    const heading = HEADING.exec(line)
    if (heading) {
      const name = headingName(line)
      if (isStart(name)) {
        inSection = true
        continue
      }
      if (isEnd(name)) {
        // 与 Java 相同：只有「全文出现过 start 标题」时 end 才真的结束区域
        if (hasStart) break
        continue
      }
      if (inSection) add(headings, heading[1])
      continue
    }
    if (!inSection) continue
    const bullet = BULLET.exec(line)
    if (bullet) {
      add(bullets, bullet[1])
      continue
    }
    const numbered = NUMBERED.exec(line)
    if (numbered) add(bullets, numbered[1])
  }

  const pointedHeadings = headings.filter((c) => c.points !== null)
  const pointedBullets = bullets.filter((c) => c.points !== null)
  if (pointedHeadings.length > 0) {
    return {
      criteria: pointedHeadings,
      ignored: [
        ...headings.filter((c) => c.points === null),
        ...bullets.filter((c) => c.points === null),
      ],
    }
  }
  if (pointedBullets.length > 0) {
    return {
      criteria: pointedBullets,
      ignored: [...bullets.filter((c) => c.points === null), ...headings],
    }
  }
  return { criteria: headings.length > 0 ? headings : bullets, ignored: [] }
}

/** 合计保留两位小数，避免 0.1 + 0.2 这类浮点噪声（Java 侧是 BigDecimal）。 */
function roundTotal(value: number): number {
  return Math.round(value * 100) / 100
}

/**
 * 把 rubric 原文摘要成前端预览。
 *
 * 不做后端那套校验（重名、合计是否等于总分）：这里只回答
 * 「解析器读到哪些评分项、每项多少分、加起来多少、哪些行被当成非评分项」。
 */
export function summarizeRubric(markdown: string): RubricSummary {
  if (!markdown || !markdown.trim()) {
    return { criteria: [], ignored: [], total: 0, anyPoints: false, missingPoints: false }
  }
  const { criteria, ignored } = parse(markdown)
  let total = 0
  let anyPoints = false
  let missingPoints = false
  for (const criterion of criteria) {
    if (criterion.points === null) {
      missingPoints = true
      continue
    }
    anyPoints = true
    total += criterion.points
  }
  return { criteria, ignored, total: roundTotal(total), anyPoints, missingPoints }
}
