import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Markdown } from './Markdown'

export interface MarkdownEditorLabels {
  toolbar: string
  bold: string
  italic: string
  heading: string
  unorderedList: string
  orderedList: string
  edit: string
  preview: string
  empty: string
  placeholder: string
}

export interface MarkdownEditorProps {
  id?: string
  value: string
  onChange: (next: string) => void
  rows?: number
  placeholder?: string
  labels: MarkdownEditorLabels
  disabled?: boolean
  footer?: ReactNode
}

type EditorMode = 'edit' | 'preview'
type ToolbarAction = 'bold' | 'italic' | 'heading' | 'unorderedList' | 'orderedList'

/** 一次编辑的结果：新全文 + 编辑后应当恢复的选区（相对新全文的偏移）。 */
interface EditResult {
  text: string
  start: number
  end: number
}

/** 选区所在的行区间。选区停在行首时，不把下一行算进来。 */
function lineBounds(text: string, start: number, end: number): { from: number; to: number } {
  const from = text.lastIndexOf('\n', start - 1) + 1
  let to = text.indexOf('\n', end)
  if (to === -1) {
    to = text.length
  } else if (end > start && text[end - 1] === '\n') {
    to = end - 1
  }
  return { from, to }
}

/** 用标记包裹选区；没有选区时插入一对标记并把光标放在中间。 */
function wrapSelection(text: string, start: number, end: number, marker: string): EditResult {
  const selected = text.slice(start, end)
  const next = `${text.slice(0, start)}${marker}${selected}${marker}${text.slice(end)}`
  const innerStart = start + marker.length
  return { text: next, start: innerStart, end: innerStart + selected.length }
}

/** 给选中的每一行加列表前缀；有序列表按行号重新编号。 */
function prefixLines(text: string, start: number, end: number, numbered: boolean): EditResult {
  const { from, to } = lineBounds(text, start, end)
  const block = text.slice(from, to)
  let seq = 0
  const rewritten = block
    .split('\n')
    .map((row) => {
      if (!row.trim()) return row
      seq += 1
      return numbered ? `${seq}. ${row}` : `- ${row}`
    })
    .join('\n')
  const next = text.slice(0, from) + rewritten + text.slice(to)
  const added = rewritten.length - block.length
  if (start === end) {
    // 折叠选区只涉及一行，光标跟着这一行新增的前缀右移
    return { text: next, start: start + added, end: end + added }
  }
  return { text: next, start: from, end: from + rewritten.length }
}

/** 在光标所在行首插入二级标题标记；已经是标题的行不再叠加。 */
function prefixHeading(text: string, start: number, end: number): EditResult {
  const from = text.lastIndexOf('\n', start - 1) + 1
  const rest = text.slice(from)
  if (/^#{1,4}\s/.test(rest)) return { text, start, end }
  const marker = '## '
  const next = text.slice(0, from) + marker + rest
  return { text: next, start: start + marker.length, end: end + marker.length }
}

function transform(text: string, start: number, end: number, action: ToolbarAction): EditResult {
  switch (action) {
    case 'bold':
      return wrapSelection(text, start, end, '**')
    case 'italic':
      return wrapSelection(text, start, end, '*')
    case 'unorderedList':
      return prefixLines(text, start, end, false)
    case 'orderedList':
      return prefixLines(text, start, end, true)
    case 'heading':
      return prefixHeading(text, start, end)
  }
}

/**
 * 轻量 Markdown 编辑器：工具栏 + 编辑/预览两个 tab。
 *
 * 不引第三方编辑器（理由见 RichTextEditor.tsx 顶部注释），所有界面文字都由
 * 调用方通过 `labels` 传入 —— 本文件里没有任何中文字面量，i18n 由页面负责。
 * 工具栏动作直接读写 textarea 的 selectionStart/selectionEnd，不维护自己的
 * 文本副本：`value` 永远来自调用方，改完立刻 onChange，再用 effect 把焦点和
 * 选区放回去（受控组件重渲染后 DOM 选区会被重置，只能用 effect 补）。
 */
export function MarkdownEditor({
  id,
  value,
  onChange,
  rows = 8,
  placeholder,
  labels,
  disabled = false,
  footer,
}: MarkdownEditorProps) {
  const [mode, setMode] = useState<EditorMode>('edit')
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)
  const pendingSelection = useRef<{ start: number; end: number } | null>(null)

  // 受控 textarea 重新渲染后选区会丢，所以工具栏改完先记下目标选区，等新 value 到位再恢复
  useEffect(() => {
    if (mode !== 'edit') return
    const pending = pendingSelection.current
    const element = textareaRef.current
    if (!pending || !element) return
    pendingSelection.current = null
    element.focus()
    element.setSelectionRange(pending.start, pending.end)
  }, [value, mode])

  function applyToolbar(action: ToolbarAction) {
    const element = textareaRef.current
    if (!element || disabled) return
    const result = transform(element.value, element.selectionStart, element.selectionEnd, action)
    if (result.text === element.value) {
      element.focus()
      return
    }
    pendingSelection.current = { start: result.start, end: result.end }
    onChange(result.text)
  }

  const tools: { action: ToolbarAction; label: string; glyph: ReactNode }[] = [
    { action: 'bold', label: labels.bold, glyph: <strong>B</strong> },
    { action: 'italic', label: labels.italic, glyph: <span className="md-tool-italic">I</span> },
    { action: 'heading', label: labels.heading, glyph: 'H2' },
    { action: 'unorderedList', label: labels.unorderedList, glyph: '•' },
    { action: 'orderedList', label: labels.orderedList, glyph: '1.' },
  ]

  return (
    <div className="md-editor">
      <div className="md-toolbar">
        <div className="md-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            className="md-tab"
            aria-selected={mode === 'edit'}
            tabIndex={mode === 'edit' ? 0 : -1}
            onClick={() => setMode('edit')}
          >
            {labels.edit}
          </button>
          <button
            type="button"
            role="tab"
            className="md-tab"
            aria-selected={mode === 'preview'}
            tabIndex={mode === 'preview' ? 0 : -1}
            onClick={() => setMode('preview')}
          >
            {labels.preview}
          </button>
        </div>
        {mode === 'edit' ? (
          <div className="md-actions" role="toolbar" aria-label={labels.toolbar}>
            {tools.map((tool) => (
              <button
                key={tool.action}
                type="button"
                className="md-tool"
                title={tool.label}
                aria-label={tool.label}
                disabled={disabled}
                onClick={() => applyToolbar(tool.action)}
              >
                {tool.glyph}
              </button>
            ))}
          </div>
        ) : null}
      </div>
      {mode === 'edit' ? (
        <textarea
          ref={textareaRef}
          id={id}
          className="md-input"
          rows={rows}
          value={value}
          placeholder={placeholder ?? labels.placeholder}
          readOnly={disabled}
          aria-disabled={disabled || undefined}
          spellCheck={false}
          onChange={(event) => onChange(event.target.value)}
        />
      ) : (
        <div className="md-preview">
          {value.trim() ? <Markdown text={value} /> : <div className="md-empty">{labels.empty}</div>}
        </div>
      )}
      {footer ? <div className="md-footer">{footer}</div> : null}
    </div>
  )
}
