import { Segmented } from 'antd'
import { useTranslation } from 'react-i18next'
import { MoonOutlined, SunOutlined } from './icons'
import { useThemeMode } from './ThemeProvider'
import type { ThemeMode } from './tokens'

/**
 * 亮/暗切换：写在侧栏底部与资料页。默认亮色，选择记在 localStorage。
 * 不用系统偏好自动切（计划 A2）：教室投影时被切成暗色会很难解释。
 */
export function ThemeToggle({ size = 'small' }: { size?: 'small' | 'middle' }) {
  const { mode, setMode } = useThemeMode()
  const { t } = useTranslation('translation')

  return (
    <div className="tl-theme-toggle">
      <span className="tl-theme-toggle-label">{t('ui.theme.label')}</span>
      <Segmented<ThemeMode>
        size={size}
        value={mode}
        onChange={(value) => setMode(value)}
        options={[
          { value: 'light', label: t('ui.theme.light'), icon: <SunOutlined /> },
          { value: 'dark', label: t('ui.theme.dark'), icon: <MoonOutlined /> },
        ]}
      />
    </div>
  )
}
