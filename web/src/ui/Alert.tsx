import type { ComponentProps } from 'react'
import { Alert as AntAlert } from 'antd'

/** 提示条（错误 / 成功 / 警告）的唯一入口，页面不直接引 antd。 */
export const Alert = AntAlert
export type AlertProps = ComponentProps<typeof AntAlert>
