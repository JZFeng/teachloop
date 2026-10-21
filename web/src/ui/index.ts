/**
 * 组件层唯一出口：页面只能从这里 import，不允许直接 `import { Button } from 'antd'`。
 * 这条约束由 `scripts/check-ui.py` 的第 3 条规则守着。
 */
export { Button } from './Button'
export type { ButtonProps, ButtonVariant } from './Button'
export { Input, TextArea, Password, Search } from './Input'
export { Alert } from './Alert'
export { Segmented } from './Segmented'
export { Field } from './Field'
export type { FieldProps } from './Field'
export { PageHeader } from './PageHeader'
export { StatCard } from './StatCard'
export { Tag } from './Tag'
export type { Tone } from './Tag'
export { DataTable } from './DataTable'
export type { Column, DataTableProps, SortDir, SortState } from './DataTable'
export { Loading, ErrorState, Empty, useConfirm, useToast } from './States'
export { ThemeProvider, useThemeMode } from './ThemeProvider'
export { ThemeToggle } from './ThemeToggle'
export { buildTheme, FONT_FAMILY, CONTRAST_NOTES } from './tokens'
export type { ThemeMode } from './tokens'
