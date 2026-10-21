import { Input as AntInput } from 'antd'

/**
 * 输入控件的唯一入口。
 *
 * 页面不允许 `import { Input } from 'antd'`（check-ui 第 3 条拦），
 * 因为直接引库之后想换实现就得改遍全站。这里重新导出，类型仍来自 antd。
 */
export const Input = AntInput
export const TextArea = AntInput.TextArea
export const Password = AntInput.Password
export const Search = AntInput.Search
export type InputProps = React.ComponentProps<typeof AntInput>
