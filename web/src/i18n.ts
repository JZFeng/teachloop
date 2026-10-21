import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import LanguageDetector from 'i18next-browser-languagedetector'

// 文案集中在 locales/*.json：改一句界面文案只改那一个文件的一行。
// key 路径（app / nav / common / audit / exam / student ...）是稳定的，
// 组件里一律用 t('teacher.xxx') 这类路径访问，改文案不需要碰代码。
//
// 新增一门语言：在 locales/ 下放一份 <code>.json（键与 zh-CN.json 完全一致，
// 并在 language.<短码> 里写这门语言的自称，例如 "fr": "Français"），
// 语言下拉框会自动多出一项，不需要改任何组件代码。

type LocaleTranslation = Record<string, unknown>

const localeModules = import.meta.glob<{ default: LocaleTranslation }>('./locales/*.json', { eager: true })

const resources: Record<string, { translation: LocaleTranslation }> = {}
const labels: Record<string, string> = {}

for (const [path, module] of Object.entries(localeModules)) {
  const code = path.split('/').pop()?.replace(/\.json$/, '') ?? ''
  if (!code) continue
  const translation = module.default
  resources[code] = { translation }
  const short = code.split('-')[0]
  const languageNames = translation.language as Record<string, string> | undefined
  labels[code] = languageNames?.[short] ?? code
}

/** 语言下拉框的选项：默认语言（zh-CN）在最前，其余按代码排序。 */
export const LANGUAGES: { code: string; label: string }[] = Object.keys(resources)
  .sort((a, b) => (a === 'zh-CN' ? -1 : b === 'zh-CN' ? 1 : a.localeCompare(b)))
  .map((code) => ({ code, label: labels[code] }))

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources,
    // 缺省中文：只有用户显式切过语言（记在 localStorage）才沿用，否则一律 zh-CN。
    // 不再读 navigator：英文系统浏览器第一次打开也应该是中文界面。
    fallbackLng: 'zh-CN',
    supportedLngs: Object.keys(resources),
    nonExplicitSupportedLngs: false,
    load: 'currentOnly',
    detection: { order: ['localStorage'], lookupLocalStorage: 'teachloop.lang', caches: ['localStorage'] },
    interpolation: { escapeValue: false },
  })

/** <html lang> 跟着界面语言走：缺省 zh-CN，切换后同步。 */
export function applyDocumentLang(lang: string) {
  document.documentElement.lang = lang.startsWith('zh') ? 'zh-CN' : lang
}

/** 切换界面语言：生效到本机（localStorage 缓存由 detector 写入）并同步 <html lang>。 */
export async function changeLanguage(code: string): Promise<void> {
  await i18n.changeLanguage(code)
  applyDocumentLang(code)
}

applyDocumentLang(i18n.language || 'zh-CN')
i18n.on('languageChanged', applyDocumentLang)

export default i18n
