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
 *
 * 品牌层（2026-10-22）：
 *   - BRAND 是 logo 原始色，主题无关，只做图形与插画；
 *   - FEATURE_* 是四个功能位（Create / Assess / Practice / Insights）的深字与浅底；
 *   - STATUS_* 是判分三态（对 / 部分对 / 错），与功能色分开命名，页面不得混用。
 */

export type ThemeMode = 'light' | 'dark'

/** 中文优先的字体栈：第一项保持系统字体，避免 mac 上中文发糊。 */
export const FONT_FAMILY =
  '-apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", "Noto Sans SC", "Segoe UI", sans-serif'

/** 两套主题共用的尺度：密度、圆角、字号阶梯。圆角比旧版更圆一档，密度不动。 */
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
  borderRadius: 8,
  borderRadiusLG: 12,
  borderRadiusSM: 6,
  controlHeight: 32,
  controlHeightSM: 26,
  wireframe: false,
  motionDurationMid: '0.12s',
} satisfies ThemeConfig['token']

/** 亮色：品牌蓝压深一档 #1A63E0，白字 5.38:1（logo 亮蓝 #1F8DFD 只有 3.34，不能当按钮底）。 */
const lightToken = {
  ...shared,
  colorPrimary: '#1A63E0',
  colorPrimaryHover: '#1450BC',
  colorPrimaryActive: '#0F3F99',
  colorPrimaryBg: '#E8EFFD',
  colorPrimaryBorder: '#A9C1F5',
  colorSuccess: '#17703F',
  colorSuccessBg: '#E6F5EC',
  colorWarning: '#8A5A00',
  colorWarningBg: '#FFF3D6',
  colorError: '#B42338',
  colorErrorBg: '#FDE6E8',
  colorInfo: '#1A63E0',
  colorInfoBg: '#E8EFFD',
  colorLink: '#1A63E0',
  colorText: '#0E2340',
  colorTextSecondary: '#41536E',
  colorTextTertiary: '#55677F',
  colorTextQuaternary: '#7C8BA3',
  colorBorder: '#7C8BA3',
  colorBorderSecondary: '#DCE4F2',
  colorBgLayout: '#F6F8FC',
  colorBgContainer: '#FFFFFF',
  colorBgElevated: '#FFFFFF',
  colorFillQuaternary: '#EFF4FB',
  colorTextLightSolid: '#FFFFFF',
  boxShadow: '0 1px 2px rgba(14, 54, 118, 0.05), 0 1px 3px rgba(14, 54, 118, 0.07)',
  boxShadowSecondary: '0 18px 44px rgba(14, 54, 118, 0.12)',
} satisfies ThemeConfig['token']

/** 暗色：海军蓝偏色的暗底；主色 #2A6FD6 对白字 4.84:1，链接另给 #7DB1FF（对面板 7.96:1）。 */
const darkToken = {
  ...shared,
  colorPrimary: '#2A6FD6',
  colorPrimaryHover: '#2463C4',
  colorPrimaryActive: '#1B4E9E',
  colorPrimaryBg: '#14233B',
  colorPrimaryBorder: '#2A4A78',
  colorSuccess: '#6EE7A5',
  colorSuccessBg: '#0C2A1D',
  colorWarning: '#FFC85C',
  colorWarningBg: '#2A2110',
  colorError: '#FF7D8A',
  colorErrorBg: '#331218',
  colorInfo: '#2A6FD6',
  colorInfoBg: '#14233B',
  colorLink: '#7DB1FF',
  colorText: '#E8EEF9',
  colorTextSecondary: '#B3C0D4',
  colorTextTertiary: '#93A4BD',
  colorTextQuaternary: '#5E7391',
  colorBorder: '#5E7391',
  colorBorderSecondary: '#24334D',
  colorBgLayout: '#0A1120',
  colorBgContainer: '#101A2C',
  colorBgElevated: '#16223A',
  colorFillQuaternary: '#16223A',
  colorTextLightSolid: '#FFFFFF',
  boxShadow: '0 1px 2px rgba(0, 0, 0, 0.5)',
  boxShadowSecondary: '0 18px 44px rgba(0, 0, 0, 0.45)',
} satisfies ThemeConfig['token']

/**
 * logo 原始色：主题无关，只做图形、插画、图表填充。
 * 亮蓝 / 青绿 / 黄对白字分别只有 3.34 / 2.48 / 1.65，**不得**做文字或主按钮底。
 */
export const BRAND = {
  navy: '#0E3676',
  azure: '#1F8DFD',
  teal: '#20B998',
  yellow: '#FEBF41',
  rose: '#A63960',
} as const

/**
 * 三个已上线功能位：深字（ink）配自己的粉彩底（tint）。Words 已从新 logo 去掉，黄色只做装饰。
 * 2026-10-23：Insights 从玫红改靛蓝（玫红在暗色下像「出错」），玫红让给未上线的 Practice。
 */
export const FEATURE_LIGHT = {
  create: '#1957E7',
  createWeak: '#E8EFFD',
  assess: '#0E7066',
  assessWeak: '#DBF4EE',
  practice: '#A63960',
  practiceWeak: '#FDE2E7',
  insights: '#3F51B5',
  insightsWeak: '#EAECFB',
} as const

export const FEATURE_DARK = {
  create: '#6FB4FF',
  createWeak: '#14233B',
  assess: '#3FD6B4',
  assessWeak: '#0F2C28',
  practice: '#E58BB0',
  practiceWeak: '#33141E',
  insights: '#A5B4FC',
  insightsWeak: '#1E2545',
} as const

/**
 * 两个空间位（2026-10-23 新增）：首页与班级不是功能位，是空间。
 * 它们有自己的命名空间，不与 FEATURE 混用；颜色只进导航。
 */
export const SPACE_LIGHT = {
  home: '#0E3676',
  homeWeak: '#E7EDF7',
  classes: '#5B3AC4',
  classesWeak: '#EAE0FD',
} as const

export const SPACE_DARK = {
  home: '#8FB4FF',
  homeWeak: '#152640',
  classes: '#A78BFA',
  classesWeak: '#1E1A3A',
} as const

/** 判分三态：与功能色分开命名，标签里必须有文字，不能只靠颜色。 */
export const STATUS_LIGHT = {
  pass: '#17703F',
  passWeak: '#E6F5EC',
  partial: '#8A5A00',
  partialWeak: '#FFF3D6',
  fail: '#B42338',
  failWeak: '#FDE6E8',
} as const

export const STATUS_DARK = {
  pass: '#6EE7A5',
  passWeak: '#0C2A1D',
  partial: '#FFC85C',
  partialWeak: '#2A2110',
  fail: '#FF7D8A',
  failWeak: '#331218',
} as const

/** 字标文字：亮色用压深一档的蓝 / 青，暗色用更亮一档；位图只用于大字位。 */
export const LOGO_LIGHT = {
  navy: '#0E3676',
  blue: '#1957E7',
  teal: '#0E7066',
} as const

export const LOGO_DARK = {
  navy: '#E8EEF9',
  blue: '#6FB4FF',
  teal: '#3FD6B4',
} as const

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
  // Table 的组件覆盖随 T441 退场：列表表格不再由 antd 渲染，密度与表头样式在 ui.css 的 tl-table* 里。
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
  light: { colorPrimary: 5.38, colorTextTertiary: 5.78, colorBorder: 3.45, colorLink: 5.38 },
  dark: { colorPrimary: 4.84, colorTextTertiary: 6.87, colorBorder: 3.6, colorLink: 7.96 },
} as const
