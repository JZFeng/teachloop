import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { App as AntApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import enUS from 'antd/locale/en_US'
import { useTranslation } from 'react-i18next'
import { buildTheme } from './tokens'
import type { ThemeMode } from './tokens'

const STORAGE_KEY = 'teachloop.theme'

interface ThemeState {
  mode: ThemeMode
  setMode: (mode: ThemeMode) => void
  toggle: () => void
}

const ThemeContext = createContext<ThemeState>({ mode: 'light', setMode: () => {}, toggle: () => {} })

function initialMode(): ThemeMode {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'dark' || saved === 'light') return saved
  } catch {
    /* 隐私模式下 localStorage 可能不可用，回落到亮色 */
  }
  // 默认亮色：教室投影场景要可预测，不跟系统偏好走（见计划 A2）。
  return 'light'
}

/**
 * 全站唯一的主题入口：antd 令牌 + 旧 CSS 变量的 `html[data-theme]` + antd 语言包。
 *
 * 三个出口必须同步，否则会出现「按钮是暗的、表格还是亮的」：
 *   1. ConfigProvider.theme  —— antd 组件
 *   2. documentElement[data-theme] —— `styles/tokens.css` 里的旧变量映射
 *   3. ConfigProvider.locale —— antd 内置文案（分页、日期、空态）
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const { i18n } = useTranslation()
  const [mode, setModeState] = useState<ThemeMode>(initialMode)

  const setMode = useCallback((next: ThemeMode) => {
    setModeState(next)
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      /* 存不下就只在本次会话生效 */
    }
  }, [])

  const toggle = useCallback(() => {
    setModeState((prev) => {
      const next: ThemeMode = prev === 'dark' ? 'light' : 'dark'
      try {
        localStorage.setItem(STORAGE_KEY, next)
      } catch {
        /* 同上 */
      }
      return next
    })
  }, [])

  useEffect(() => {
    document.documentElement.dataset.theme = mode
    // body 上那份旧标记（index.html 里的 data-theme="saas"）要清掉，避免两处说法打架。
    delete document.body.dataset.theme
  }, [mode])

  const locale = i18n.language?.startsWith('zh') ? zhCN : enUS
  const themeConfig = useMemo(() => buildTheme(mode), [mode])

  return (
    <ThemeContext.Provider value={{ mode, setMode, toggle }}>
      <ConfigProvider theme={themeConfig} locale={locale}>
        <AntApp>{children}</AntApp>
      </ConfigProvider>
    </ThemeContext.Provider>
  )
}

export function useThemeMode(): ThemeState {
  return useContext(ThemeContext)
}
