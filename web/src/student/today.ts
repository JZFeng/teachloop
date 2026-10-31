/**
 * 今日页的纯函数与接口类型（无 React、无 i18n、无 DOM）。
 *
 * 为什么单独一个文件：排序、截断、「N 天后」、日期标题、空词库判定这五件事
 * 是 AC-90.2 / 90.4 / 90.5 的可验收部分，但它们在浏览器里只能靠肉眼看。
 * 放在这里之后 `web/tests/today-page.test.mjs` 用 `node --experimental-strip-types --test`
 * 直接 import，不需要 React、不需要 api。
 *
 * 契约来源：`specs/011-self-study-mode/spec.md` §7.1（`GET /api/student/today`）。
 * 字段名照抄后端，前端不改名——改名会让「契约在哪」变成两个答案。
 */

/** 复习切片：字段来自 `GET /api/vocab/review/queue` 的 `total / todayReviewed / newToday`。 */
export interface ReviewView {
  due: number
  reviewedToday: number
  newToday: number
  /** 每日新卡上限（`newLimit`）；今日页只展示，不用它算截断。 */
  newLimit: number
}

/** 待交作业：`status` 只有两态 `todo` / `submitted`（spec §7.1 的 2026-10-30 修订）。 */
export interface AssignmentView {
  id: string
  title: string
  className: string
  /** 可能为 null：sitting 的 `due_at` 可空，后端排序时按 nullsLast 放最后。 */
  dueAt: string | null
  status: string
}

/** 考试：后端只返回未来 7 天的。 */
export interface ExamView {
  id: string
  title: string
  className: string
  startsAt: string | null
}

/** 某一段取数是否成功：整体永远 200，失败的段落靠这个标记（AC-94.2）。 */
export type SectionStatus = 'ok' | 'error'

export interface StudentTodayView {
  /** 这一段取数失败时后端把它置空，配合 `partial.review === 'error'` 显示错误卡。 */
  review: ReviewView | null
  assignments: AssignmentView[]
  exams: ExamView[]
  partial: {
    review: SectionStatus
    assignments: SectionStatus
    exams: SectionStatus
  }
  /** 有没有进行中的班级：决定作业/考试卡与词单/邀请码卡哪一组出现（AC-90.5 / AC-92.4）。 */
  inClass: boolean
  /** 服务端按 FR-226 解析出的学生时区；与 `defaultZone` 不同才显示说明（AC-99.5）。 */
  zone: string
  defaultZone: string
}

/** 作业卡最多显示 3 条（FR-200 / AC-90.2）。 */
export const ASSIGNMENT_LIMIT = 3
/** 考试卡最多显示 2 条（FR-201 / AC-90.4）。 */
export const EXAM_LIMIT = 2

const DAY_MS = 86_400_000

type TimeInput = string | number | Date | null | undefined

/** 宽松解析成毫秒时间戳；解析不出来返回 null（不抛异常，调用方决定沉底还是忽略）。 */
function toTime(value: TimeInput): number | null {
  if (value === null || value === undefined) return null
  if (value instanceof Date) return Number.isFinite(value.getTime()) ? value.getTime() : null
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : null
}

/**
 * 把时间戳换算成「当地日历日的序号」。
 *
 * 用 `Date.UTC(年,月,日)` 而不是「毫秒差 / 86400000」：后者在夏令时切换日会算出 23 或 25 小时，
 * 「N 天后」就会在 2027-03-14 与 2027-11-07 各错一天（AC-99.4 的同一条边界）。
 */
function calendarDay(time: number): number {
  const date = new Date(time)
  return Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / DAY_MS
}

function formatter(locale: string, options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  try {
    return new Intl.DateTimeFormat(locale || undefined, options)
  } catch {
    // 非法 locale（空串、Mars/Base）不能让页头崩掉：回落到运行时的默认语言。
    return new Intl.DateTimeFormat(undefined, options)
  }
}

/**
 * 截止时间是否已过。
 *
 * `dueAt` 解析不出来时返回 false：一条脏数据不该把整行染成「逾期」。
 */
export function isOverdue(dueAt: string | null, now: TimeInput = new Date()): boolean {
  const due = toTime(dueAt)
  const base = toTime(now)
  if (due === null || base === null) return false
  return due < base
}

/**
 * 从 `now` 到 `startsAt` 的日历天差，向上取整：今天 = 0、明天 = 1、三天后 = 3。
 *
 * 已过去的返回负数（「逾期 N 天」用它取反）。同一天内的时刻差不算一天——
 * 学生看到的「明天」必须跟他日历上的明天一致，而不是 24 小时后。
 */
export function daysUntil(startsAt: string | null, now: TimeInput = new Date()): number {
  const start = toTime(startsAt)
  const base = toTime(now)
  if (start === null || base === null) return 0
  return Math.ceil(calendarDay(start) - calendarDay(base))
}

/**
 * 逾期天数，最小 1：当天早些时候到期、现在才看，也该写「逾期 1 天」而不是「逾期 0 天」。
 */
export function daysOverdue(dueAt: string | null, now: TimeInput = new Date()): number {
  // 解析不出截止时间就不算逾期：返回 0 比返回「1 天」诚实，卡片也不会因此染红。
  if (toTime(dueAt) === null) return 0
  return Math.max(1, -daysUntil(dueAt, now))
}

/**
 * 作业卡要显示的条目：逾期在前、按 `dueAt` 升序，截断到 `limit` 条。
 *
 * 「逾期在前」不单独判断：逾期就是 `dueAt < now`，按 `dueAt` 升序天然排在未到期之前，
 * 且逾期的更久者（更早）排最前——这与 plan §4 第 4 步「逾期的按逾期更久排在前」同义。
 * 后端已排过一次，这里再排一次是防御：接口换实现、前端拿缓存回放都不会让顺序失真。
 * 解析不出的 `dueAt` 沉到最后，不占「最急」的位置。
 */
export function visibleAssignments<T extends { dueAt: string | null }>(
  list: readonly T[],
  limit: number = ASSIGNMENT_LIMIT,
): T[] {
  const sorted = [...list].sort((left, right) => {
    const a = toTime(left.dueAt)
    const b = toTime(right.dueAt)
    if (a === null && b === null) return 0
    if (a === null) return 1
    if (b === null) return -1
    return a - b
  })
  return limit >= 0 ? sorted.slice(0, limit) : sorted
}

/** 考试卡要显示的条目：按 `startsAt` 升序，截断到 `limit` 条（AC-90.4 的最多 2 条）。 */
export function visibleExams<T extends { startsAt: string | null }>(
  list: readonly T[],
  limit: number = EXAM_LIMIT,
): T[] {
  const sorted = [...list].sort((left, right) => {
    const a = toTime(left.startsAt)
    const b = toTime(right.startsAt)
    if (a === null && b === null) return 0
    if (a === null) return 1
    if (b === null) return -1
    return a - b
  })
  return limit >= 0 ? sorted.slice(0, limit) : sorted
}

/**
 * 页头的当天日期：`10月30日 周五` / `Oct 30 Fri`。
 *
 * 月份日期与星期分开取再拼空格，而不是一次格式化整个日期：一次格式化出来的是
 * `10月30日周五`（中文没有分隔）与 `Fri, Oct 30`（英文换了顺序），都不是需求里的形状。
 * 星期不做 i18n key——它来自 `Intl`，加 key 等于把日历抄一遍。
 */
export function formatTodayHeading(date: Date | string | number, locale: string): string {
  const value = date instanceof Date ? date : new Date(date)
  if (Number.isNaN(value.getTime())) return ''
  const day = formatter(locale, { month: 'short', day: 'numeric' }).format(value)
  const weekday = formatter(locale, { weekday: 'short' }).format(value)
  return `${day} ${weekday}`
}

/**
 * 卡片上的一行具体时刻：`10月30日 18:00` / `Oct 30, 06:00 PM`。
 *
 * 解析不出来时原样返回输入串：显示 `2026-10-30T18:00:00Z` 比显示 `Invalid Date` 诚实，
 * 而且能让人看出后端给的是什么。
 */
export function formatMoment(iso: string | null | undefined, locale: string): string {
  if (!iso) return ''
  const time = toTime(iso)
  if (time === null) return iso
  return formatter(locale, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(time))
}

/**
 * 词库是否为空（AC-90.5 / FR-212）：待复习、今天已复习、今天新词三个数都是 0。
 *
 * 只看这三个数，不看总词数：总词数要先请求词库（违反 AC-90.7 的「今日页只打两次请求」）。
 * `view` 为空（还在加载、或这一段取数失败）时返回 false——空态是一个事实，不是缺省值。
 */
export function emptyReview(view: ReviewView | null | undefined): boolean {
  if (!view) return false
  return view.due <= 0 && view.reviewedToday <= 0 && view.newToday <= 0
}

/**
 * 副标题里的班名：第一个作业的班名，没有作业就取第一个考试的班名。
 *
 * 页头只放一个班名而不是「班级列表」：今日页不承担班级概览（AC-90.8 / FR-213）。
 * 没有作业也没有考试时返回 null，由页面回落到 `today.subtitlePersonal`。
 */
export function firstClassName(
  assignments: readonly { className?: string }[] | null | undefined,
  exams: readonly { className?: string }[] | null | undefined,
): string | null {
  const fromAssignments = assignments?.find((item) => Boolean(item.className))?.className
  if (fromAssignments) return fromAssignments
  const fromExams = exams?.find((item) => Boolean(item.className))?.className
  return fromExams ?? null
}

/**
 * 时区 → 城市名（键与 `ProfilePage.tsx` 的 `TIME_ZONES` 一一对应，2026-10-30 对齐过）。
 *
 * 为什么不用 IANA 名：`America/Los_Angeles` 对非技术用户就是一串带下划线的乱码，
 * 而 `today.zoneNote` 这行文案的全部价值就是让人看懂「我的日界落在哪」。
 * 未命中的时区不硬失败：回落 `Intl` 的长名称（如「北美太平洋夏令时间」），
 * 再不行回落原始 IANA 名——最坏情况等于 2026-10-30 之前的写法。
 */
const ZONE_CITY: Record<string, { zh: string; en: string }> = {
  'Asia/Shanghai': { zh: '上海', en: 'Shanghai' },
  'Asia/Tokyo': { zh: '东京', en: 'Tokyo' },
  'Asia/Singapore': { zh: '新加坡', en: 'Singapore' },
  'Asia/Kolkata': { zh: '印度', en: 'India' },
  UTC: { zh: 'UTC', en: 'UTC' },
  'Europe/London': { zh: '伦敦', en: 'London' },
  'Europe/Berlin': { zh: '柏林', en: 'Berlin' },
  'America/New_York': { zh: '纽约', en: 'New York' },
  'America/Chicago': { zh: '芝加哥', en: 'Chicago' },
  'America/Los_Angeles': { zh: '洛杉矶', en: 'Los Angeles' },
  'Australia/Sydney': { zh: '悉尼', en: 'Sydney' },
}

function isChinese(locale: string): boolean {
  return (locale || '').trim().toLowerCase().startsWith('zh')
}

/** `Intl` 给的长时区名；非法时区返回 null，由调用方决定回落成什么。 */
function zoneLongName(zone: string, locale: string, at: Date): string | null {
  try {
    const parts = new Intl.DateTimeFormat(locale || undefined, {
      timeZone: zone,
      timeZoneName: 'long',
    }).formatToParts(at)
    return parts.find((part) => part.type === 'timeZoneName')?.value ?? null
  } catch {
    // 非法时区（`Mars/Base`）不该让整页崩：名字这一环只负责好读，不负责正确性。
    return null
  }
}

/**
 * 时区的中性名：11 条城市映射优先，其次 `Intl` 长名称，最后原始 IANA 名。
 *
 * 城市名不随时令变化（「洛杉矶」永远是「洛杉矶」），会变的偏移单独交给
 * {@link zoneOffsetLabel}——两者分开，切换夏令时时只有括号里的数字动。
 */
export function zoneLabel(zone: string, locale: string, at: Date = new Date()): string {
  if (!zone) return ''
  const city = ZONE_CITY[zone]
  if (city) return isChinese(locale) ? city.zh : city.en
  return zoneLongName(zone, locale, at) ?? zone
}

/**
 * 时区在**这一刻**的 UTC 偏移：`UTC-7` / `UTC+8` / `UTC+5:30`；偏移为 0 时返回空串。
 *
 * 必须按「现在」算，不能写成常量：`America/Los_Angeles` 在 2026-10-30 是 UTC-7，
 * 夏令时结束（2026-11-01）之后就是 UTC-8。写死一个数字，一年里有四个多月是错的。
 *
 * 半小时候时区（`Asia/Kolkata` → `UTC+5:30`）保留分钟：取整会把印度说成 UTC+5。
 */
export function zoneOffsetLabel(zone: string, at: Date | string | number = new Date()): string {
  if (!zone) return ''
  const parsed = at instanceof Date ? at : new Date(at)
  const when = Number.isNaN(parsed.getTime()) ? new Date() : parsed
  let raw: string | undefined
  try {
    raw = new Intl.DateTimeFormat('en-US', { timeZone: zone, timeZoneName: 'longOffset' })
      .formatToParts(when)
      .find((part) => part.type === 'timeZoneName')?.value
  } catch {
    return ''
  }
  const matched = raw?.match(/^GMT([+-])(\d{1,2}):(\d{2})$/)
  if (!matched) return ''
  const [, sign, hours, minutes] = matched
  if (Number(hours) === 0 && minutes === '00') return ''
  return minutes === '00' ? `UTC${sign}${Number(hours)}` : `UTC${sign}${Number(hours)}:${minutes}`
}
