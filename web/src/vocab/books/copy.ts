/**
 * 系统词书（013）的文案表（S2 阶段）。
 *
 * 为什么不直接写进 `web/src/locales/*.json`：这两个文件归 S1 独占（见
 * `docs/process/impl-runbook-011-013.md` 的「并行规则」），S2/S3 不许动。
 * 所以这里先放一份**键名就是最终 `vocab.books.*` 命名**的临时表：
 *   - `copy.json`：`{ "vocab.books.xxx": { "zh-CN": "...", "en": "..." } }`；
 *   - `copy.ts`：本文件，按 `i18n.language` 取值 + `{name}` 占位替换。
 *
 * S1 合并时把 `copy.json` 的条目原样搬进 `locales/zh-CN.json` 与 `en.json`，
 * 然后把页面里的 `bc(...)` 换成 `t(...)` —— 键名不变，所以这只是一次机械搬移，
 * 页面不用重排。注意在**注释里**也不要写出「t 加带引号的键」这种字面量：
 * `check-i18n` 的第 2 条会把注释里的这种调用也当成组件引用，键不存在就直接红。
 */
import { useTranslation } from 'react-i18next'
import i18n from '../../i18n'
import copy from './copy.json'

type BooksLang = 'zh-CN' | 'en'

const TABLE = copy as unknown as Record<string, Partial<Record<BooksLang, string>>>

/** 界面语言 → 本表的两种语言：只有明确的 en* 走英文，其余一律中文（与 i18n 的 fallbackLng 同口径）。 */
function currentLang(): BooksLang {
  return (i18n.language || '').toLowerCase().startsWith('en') ? 'en' : 'zh-CN'
}

/**
 * 取一句 `vocab.books.*` 文案，`{name}` 形式的占位用 `vars` 替换。
 *
 * 缺键时返回键名本身（与 i18next 的默认行为一致），这样漏一条文案会在界面上
 * 直接看到 `vocab.books.xxx`，而不是悄悄显示空白。
 */
export function bc(key: string, vars?: Record<string, string | number>): string {
  const entry = TABLE[key]
  const raw = entry?.[currentLang()] ?? entry?.['zh-CN'] ?? key
  if (!vars) return raw
  return raw.replace(/\{(\w+)\}/g, (match, name: string) =>
    (vars[name] === undefined ? match : String(vars[name])))
}

/**
 * 组件里用这个拿 `bc`：它内部 `useTranslation()` 订阅了语言切换，
 * 切语言时组件会重渲染，`bc()` 才会读到新的 `i18n.language`。
 * 只调用 `bc` 而不 `useTranslation` 的组件在切语言后不会重渲染。
 */
export function useBooksCopy(): typeof bc {
  useTranslation()
  return bc
}
