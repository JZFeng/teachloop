import { useEffect, useRef, useState } from 'react'
import type { ChangeEvent, CompositionEvent, FormEvent, KeyboardEvent, ReactNode } from 'react'
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

/** 撤销/重做栈里的一步：这一步之前的全文与选区。 */
interface Snapshot {
  text: string
  start: number
  end: number
}

/** 同类输入在这么短时间内视为同一段，按一次撤销处理（接近浏览器自带的输入合并）。 */
const BURST_MS = 700
const MAX_HISTORY = 100

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

type EditKind = 'insert' | 'delete' | 'paste' | 'other'

/** 按 inputType 归类，决定相邻输入能否合并成一步撤销。 */
function editKindOf(nativeEvent: Event): EditKind {
  const inputType = (nativeEvent as InputEvent).inputType ?? ''
  if (inputType.startsWith('delete')) return 'delete'
  if (inputType.startsWith('insertFromPaste') || inputType === 'insertFromDrop') return 'paste'
  if (inputType.startsWith('insert')) return 'insert'
  return 'other'
}

/**
 * 轻量 Markdown 编辑器：工具栏 + 编辑/预览两个 tab。
 *
 * 不引第三方编辑器（理由见 RichTextEditor.tsx 顶部注释），所有界面文字都由
 * 调用方通过 `labels` 传入 —— 本文件里没有任何中文字面量，i18n 由页面负责。
 * 工具栏动作直接读写 textarea 的 selectionStart/selectionEnd，不维护自己的
 * 文本副本：`value` 永远来自调用方，改完立刻 onChange，再用 effect 把焦点和
 * 选区放回去（受控组件重渲染后 DOM 选区会被重置，只能用 effect 补）。
 *
 * <p>T335：撤销/重做自己维护。受控 textarea 每次按键都由 React 回写 value，
 * 浏览器原生撤销会被拆成「一次退一个字符」，工具栏插入的 `**` 更是退不掉；
 * 所以这里按「编辑前快照」记栈，同类输入在 {@link BURST_MS} 内合并成一步，
 * 再显式接管 Cmd/Ctrl+Z、Cmd/Ctrl+Shift+Z（Ctrl+Y）、加粗、斜体与列表快捷键。
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

  // ---- 撤销/重做历史 ----
  /** 栈里存「这一步之前」的全文与选区；撤销就是弹栈。 */
  const historyRef = useRef<Snapshot[]>([])
  const redoRef = useRef<Snapshot[]>([])
  /** 本次输入之前的状态（beforeinput 时抓，change 时消费）。 */
  const preEditRef = useRef<Snapshot | null>(null)
  /** IME 组合输入：组合期间不逐步记录，组合结束记一步。 */
  const composingRef = useRef(false)
  const compositionStartRef = useRef<Snapshot | null>(null)
  const lastEditRef = useRef<{ kind: EditKind; at: number } | null>(null)
  const lastSelectionRef = useRef<{ start: number; end: number } | null>(null)
  /** 本组件最后一次交给父组件的值：用来区分「自己改的」和「外部改的」。 */
  const emittedRef = useRef(value)
  const previousPropsRef = useRef(value)

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

  // 外部直接改值（导入 .md、清空表单）：也记一步，Cmd+Z 能撤回这次外部改动
  useEffect(() => {
    if (value === emittedRef.current) {
      previousPropsRef.current = value
      return
    }
    const previous = previousPropsRef.current
    historyRef.current.push({ text: previous, start: previous.length, end: previous.length })
    if (historyRef.current.length > MAX_HISTORY) historyRef.current.shift()
    redoRef.current = []
    lastEditRef.current = null
    lastSelectionRef.current = null
    preEditRef.current = null
    emittedRef.current = value
    previousPropsRef.current = value
  }, [value])

  function snapshotOf(element: HTMLTextAreaElement): Snapshot {
    return { text: element.value, start: element.selectionStart, end: element.selectionEnd }
  }

  function applySnapshot(snapshot: Snapshot) {
    pendingSelection.current = { start: snapshot.start, end: snapshot.end }
    emittedRef.current = snapshot.text
    previousPropsRef.current = snapshot.text
    onChange(snapshot.text)
  }

  function undo() {
    const element = textareaRef.current
    if (!element || disabled) return
    const previous = historyRef.current.pop()
    if (!previous) return
    redoRef.current.push(snapshotOf(element))
    lastEditRef.current = null
    lastSelectionRef.current = null
    preEditRef.current = null
    applySnapshot(previous)
  }

  function redo() {
    const element = textareaRef.current
    if (!element || disabled) return
    const next = redoRef.current.pop()
    if (!next) return
    historyRef.current.push(snapshotOf(element))
    if (historyRef.current.length > MAX_HISTORY) historyRef.current.shift()
    lastEditRef.current = null
    lastSelectionRef.current = null
    preEditRef.current = null
    applySnapshot(next)
  }

  function recordChange(pre: Snapshot, kind: EditKind) {
    const element = textareaRef.current
    if (!element) return
    const now = Date.now()
    const last = lastEditRef.current
    const lastSelection = lastSelectionRef.current
    const continues =
      last !== null &&
      last.kind === kind &&
      now - last.at <= BURST_MS &&
      redoRef.current.length === 0 &&
      lastSelection !== null &&
      lastSelection.start === pre.start &&
      lastSelection.end === pre.end
    if (!continues) {
      historyRef.current.push(pre)
      if (historyRef.current.length > MAX_HISTORY) historyRef.current.shift()
      redoRef.current = []
    }
    lastEditRef.current = { kind, at: now }
    lastSelectionRef.current = { start: element.selectionStart, end: element.selectionEnd }
    previousPropsRef.current = element.value
  }

  function handleChange(event: ChangeEvent<HTMLTextAreaElement>) {
    const element = event.currentTarget
    const next = element.value
    if (!composingRef.current) {
      const pre = preEditRef.current ?? {
        text: value,
        start: element.selectionStart,
        end: element.selectionEnd,
      }
      recordChange(pre, editKindOf(event.nativeEvent))
      preEditRef.current = null
    }
    emittedRef.current = next
    previousPropsRef.current = next
    onChange(next)
  }

  function handleBeforeInput(event: FormEvent<HTMLTextAreaElement>) {
    if (composingRef.current) return
    preEditRef.current = snapshotOf(event.currentTarget)
  }

  function handleCompositionStart(event: CompositionEvent<HTMLTextAreaElement>) {
    composingRef.current = true
    compositionStartRef.current = snapshotOf(event.currentTarget)
    preEditRef.current = null
  }

  function handleCompositionEnd(event: CompositionEvent<HTMLTextAreaElement>) {
    composingRef.current = false
    const start = compositionStartRef.current
    compositionStartRef.current = null
    const element = event.currentTarget
    if (start && start.text !== element.value) {
      recordChange(start, 'insert')
      onChange(element.value)
    }
    emittedRef.current = element.value
    previousPropsRef.current = element.value
  }

  function applyToolbar(action: ToolbarAction) {
    const element = textareaRef.current
    if (!element || disabled) return
    const result = transform(element.value, element.selectionStart, element.selectionEnd, action)
    if (result.text === element.value) {
      element.focus()
      return
    }
    historyRef.current.push(snapshotOf(element))
    if (historyRef.current.length > MAX_HISTORY) historyRef.current.shift()
    redoRef.current = []
    lastEditRef.current = null
    lastSelectionRef.current = null
    preEditRef.current = null
    pendingSelection.current = { start: result.start, end: result.end }
    emittedRef.current = result.text
    previousPropsRef.current = result.text
    onChange(result.text)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (disabled) return
    const mod = event.metaKey || event.ctrlKey
    if (!mod || event.altKey) return
    const key = event.key.toLowerCase()
    if (key === 'z') {
      event.preventDefault()
      if (event.shiftKey) redo()
      else undo()
      return
    }
    if (key === 'y' && !event.shiftKey) {
      event.preventDefault()
      redo()
      return
    }
    if (key === 'b') {
      event.preventDefault()
      applyToolbar('bold')
      return
    }
    if (key === 'i') {
      event.preventDefault()
      applyToolbar('italic')
      return
    }
    if (event.shiftKey && event.code === 'Digit8') {
      event.preventDefault()
      applyToolbar('unorderedList')
      return
    }
    if (event.shiftKey && event.code === 'Digit7') {
      event.preventDefault()
      applyToolbar('orderedList')
    }
  }

  const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad|iPod/i.test(navigator.platform || '')
  const modKey = isMac ? '⌘' : 'Ctrl'
  const shortcutOf = (action: ToolbarAction): string | null => {
    if (action === 'bold') return `${modKey}+B`
    if (action === 'italic') return `${modKey}+I`
    if (action === 'unorderedList') return `${modKey}+Shift+8`
    if (action === 'orderedList') return `${modKey}+Shift+7`
    return null
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
            {tools.map((tool) => {
              const shortcut = shortcutOf(tool.action)
              return (
                <button
                  key={tool.action}
                  type="button"
                  className="md-tool"
                  title={shortcut ? `${tool.label} (${shortcut})` : tool.label}
                  aria-label={tool.label}
                  disabled={disabled}
                  onClick={() => applyToolbar(tool.action)}
                >
                  {tool.glyph}
                </button>
              )
            })}
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
          onBeforeInput={handleBeforeInput}
          onChange={handleChange}
          onCompositionStart={handleCompositionStart}
          onCompositionEnd={handleCompositionEnd}
          onKeyDown={handleKeyDown}
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
