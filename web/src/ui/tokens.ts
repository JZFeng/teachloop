import { theme as antdTheme } from 'antd'
import type { ThemeConfig } from 'antd'

/**
 * 设计令牌唯一真源。
 *
 * 规则（由 `scripts/check-ui.py` 强制的部分）：
 *   - 颜色、字号、圆角、阴影只能出现在这个文件里，页面与其它 CSS 一律用变量。
 *   - 同一个值两套主题各给一次，键集合必须完全一致（check-ui 第 6 条）。
 *
 * 对比度不是拍脑袋：括号里的数字是 WCAG 对比度实测值，用
 * `python3 scripts/ui/contrast.py` 可复算。
 */

export type ThemeMode = 'light' | 'dark'

/** 中文优先的字体栈：第一项保持系统字体，避免 mac 上中文发糊。 */
export const FONT_FAMILY =
  '-apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", "Noto Sans SC", "Segoe UI", sans-serif'

/** 两套主题共用的尺度：密度、圆角、字号阶梯。 */
const shared = {
  fontFamily: FONT_FAMILY,
  fontFamilyCode: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
  fontSize: 14,
  // 中文行高比 antd 默认 1.5715 松一档，密集表格里仍然可读。
  lineHeight: 1.65,
  fontSizeHeading1: 24,
  fontSizeHeading2: 20,
  fontSizeHeading3: 16,
  fontSizeHeading4: 14,
  fontSizeHeading5: 13,
  fontSizeSM: 13,
  fontSizeLG: 16,
  borderRadius: 6,
  borderRadiusLG: 8,
  borderRadiusSM: 4,
  controlHeight: 32,
  controlHeightSM: 26,
  wireframe: false,
  motionDurationMid: '0.12s',
} satisfies ThemeConfig['token']

/** 亮色：主色 #1D5FBF 对白字 6.10:1（antd 默认 #1677FF 只有 4.10，不达 AA）。 */
const lightToken = {
  ...shared,
  colorPrimary: '#1D5FBF',
  colorPrimaryHover: '#1554AD',
  colorPrimaryActive: '#0F4C9C',
  colorPrimaryBg: '#EAF1FB',
  colorPrimaryBorder: '#A8C4E8',
  colorSuccess: '#237804',
  colorSuccessBg: '#EDF7EA',
  colorWarning: '#A35C00',
  colorWarningBg: '#FBF1E2',
  colorError: '#CF1322',
  colorErrorBg: '#FBEAEA',
  colorInfo: '#1D5FBF',
  colorInfoBg: '#EAF1FB',
  colorText: '#141414',
  colorTextSecondary: '#595959',
  colorTextTertiary: '#6B6B6B',
  colorTextQuaternary: '#8C8C8C',
  colorBorder: '#8C8C8C',
  colorBorderSecondary: '#E4E7EC',
  colorBgLayout: '#F5F6F8',
  colorBgContainer: '#FFFFFF',
  colorBgElevated: '#FFFFFF',
  colorFillQuaternary: '#F5F7FA',
  boxShadow: '0 1px 2px rgba(16, 24, 40, 0.04), 0 1px 3px rgba(16, 24, 40, 0.06)',
  boxShadowSecondary: '0 4px 16px rgba(16, 24, 40, 0.08)',
} satisfies ThemeConfig['token']

/** 暗色：#2A6FD6 对白字 4.84:1、对 #141414 是 3.81:1，主按钮文字与轮廓都过关。 */
const darkToken = {
  ...shared,
  colorPrimary: '#2A6FD6',
  colorPrimaryHover: '#3A7BD5',
  colorPrimaryActive: '#1D5FBF',
  colorPrimaryBg: '#16233A',
  colorPrimaryBorder: '#2A4A78',
  colorSuccess: '#73D13D',
  colorSuccessBg: '#12261D',
  colorWarning: '#FFC53D',
  colorWarningBg: '#2A2114',
  colorError: '#FF7875',
  colorErrorBg: '#2B161A',
  colorInfo: '#2A6FD6',
  colorInfoBg: '#16233A',
  colorText: '#D9D9D9',
  colorTextSecondary: '#A6A6A6',
  colorTextTertiary: '#8C8C8C',
  colorTextQuaternary: '#737373',
  colorBorder: '#6B6B6B',
  colorBorderSecondary: '#303030',
  colorBgLayout: '#141414',
  colorBgContainer: '#1F1F1F',
  colorBgElevated: '#262626',
  colorFillQuaternary: '#1A1A1A',
  boxShadow: '0 1px 2px rgba(0, 0, 0, 0.5)',
  boxShadowSecondary: '0 6px 24px rgba(0, 0, 0, 0.5)',
} satisfies ThemeConfig['token']

/**
 * 组件级覆盖：只改影响「稳重」观感的那几处。
 * 关键是 `primaryShadow: 'none'` —— 旧版按钮的彩色投影正是 2021 模板味的主要来源。
 */
const components: ThemeConfig['components'] = {
  Button: {
    primaryShadow: 'none',
    defaultShadow: 'none',
    dangerShadow: 'none',
    fontWeight: 500,
    paddingInline: 14,
  },
  Table: {
    headerBg: 'var(--ant-color-fill-quaternary)',
    headerColor: 'var(--ant-color-text-secondary)',
    headerSplitColor: 'transparent',
    cellPaddingBlock: 10,
    cellPaddingInline: 14,
    rowHoverBg: 'var(--ant-color-fill-quaternary)',
  },
  Card: { paddingLG: 18 },
  Input: { activeShadow: '0 0 0 3px var(--ant-color-primary-bg)' },
  Select: { optionSelectedBg: 'var(--ant-color-primary-bg)' },
  Tag: { defaultBg: 'var(--ant-color-fill-quaternary)' },
  Tabs: { horizontalMargin: '0 0 16px 0' },
  Modal: { titleFontSize: 16 },
}

/** 一套主题 = 算法 + seed/map 令牌 + 组件覆盖；两套键集合一致由 check-ui 校验。 */
export function buildTheme(mode: ThemeMode): ThemeConfig {
  return {
    algorithm: mode === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: mode === 'dark' ? darkToken : lightToken,
    components,
    // 开 CSS 变量模式：旧页面的 `mockup.css` 变量才能指向 `--ant-*`，
    // 未迁移的 16 屏因此自动继承新视觉，而不是一半新一半旧。
    cssVar: { prefix: 'ant' },
    hashed: false,
  }
}

/** 供脚本与测试读的对比度记录（值必须与上面令牌一致）。 */
export const CONTRAST_NOTES = {
  light: { colorPrimary: 6.1, colorTextTertiary: 5.33, colorBorder: 3.36 },
  dark: { colorPrimary: 4.84, colorTextTertiary: 5.48, colorBorder: 3.09 },
} as const
