import type { ComponentProps } from 'react'
import { Segmented as AntSegmented } from 'antd'

/**
 * 分段控件（筛选器）。页面不直接引 antd，统一从这里取。
 * 泛型参数保留：Segmented<ThemeMode> 这种写法仍然可用。
 */
export const Segmented = AntSegmented
export type SegmentedProps<T extends string | number = string> = ComponentProps<typeof AntSegmented<T>>
