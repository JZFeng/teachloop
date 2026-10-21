import type { ReactNode } from 'react'
import { Tag as AntTag } from 'antd'

export type Tone = 'blue' | 'green' | 'amber' | 'red' | 'gray'

const COLOR: Record<Tone, string> = {
  blue: 'processing',
  green: 'success',
  amber: 'warning',
  red: 'error',
  gray: 'default',
}

/** 状态标签：颜色只表达语义，不做装饰。 */
export function Tag({ tone = 'gray', children }: { tone?: Tone; children: ReactNode }) {
  return (
    <AntTag color={COLOR[tone]} bordered={false}>
      {children}
    </AntTag>
  )
}
