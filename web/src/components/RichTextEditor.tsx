import { useEffect, useRef, useState } from 'react'
import type { MouseEvent } from 'react'
import { useTranslation } from 'react-i18next'

interface RichTextEditorProps {
  /** 挂载时灌入的 HTML（切走再切回来时恢复草稿）；编辑过程中不再由 React 覆盖 DOM。 */
  initialHtml: string
  disabled?: boolean
  placeholder?: string
  /** 每次输入/格式变化回调：html 用于提交，text 用于字数统计。 */
  onChange: (html: string, text: string) => void
}

/**
 * T250：作业页的富文本输入。
 *
 * <p>之前只有一个空白 contenteditable，没有任何按钮、也没有占位提示，学生根本看不出能输入。
 * 现在按 mockup 给工具条（粗体 / 斜体 / 下划线 / 有序列表 / 无序列表 / 小标题 / 正文）：
 * 用 document.execCommand 作用于可编辑区，提交时仍由后端做白名单消毒。
 */
export function RichTextEditor({ initialHtml, disabled, placeholder, onChange }: RichTextEditorProps) {
  const { t } = useTranslation('translation')
  const bodyRef = useRef<HTMLDivElement>(null)
  const [empty, setEmpty] = useState(!initialHtml.trim())

  // 只在挂载时写一次 innerHTML：之后光标和选区完全交给浏览器，React 不参与。
  useEffect(() => {
    const el = bodyRef.current
    if (!el) return
    if (initialHtml && el.innerHTML !== initialHtml) el.innerHTML = initialHtml
    setEmpty(!el.innerText.trim())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const sync = () => {
    const el = bodyRef.current
    if (!el) return
    const text = el.innerText
    setEmpty(!text.trim())
    onChange(el.innerHTML, text)
  }

  const exec = (command: string, value?: string) => {
    if (disabled) return
    bodyRef.current?.focus()
    document.execCommand(command, false, value)
    sync()
  }

  // 点工具条不能把可编辑区的选区弄丢，所以 mousedown 一律阻止默认行为。
  const keepSelection = (event: MouseEvent) => event.preventDefault()

  return (
    <div className="rich-editor">
      <div className="rich-toolbar" role="toolbar" aria-label={t('editor.toolbar')}>
        <button type="button" title={t('editor.bold')} aria-label={t('editor.bold')}
          onMouseDown={keepSelection} onClick={() => exec('bold')}><b>B</b></button>
        <button type="button" title={t('editor.italic')} aria-label={t('editor.italic')}
          onMouseDown={keepSelection} onClick={() => exec('italic')}><i>I</i></button>
        <button type="button" title={t('editor.underline')} aria-label={t('editor.underline')}
          onMouseDown={keepSelection} onClick={() => exec('underline')}><u>U</u></button>
        <span className="sep" aria-hidden="true" />
        <button type="button" title={t('editor.unorderedList')} aria-label={t('editor.unorderedList')}
          onMouseDown={keepSelection} onClick={() => exec('insertUnorderedList')}>•</button>
        <button type="button" title={t('editor.orderedList')} aria-label={t('editor.orderedList')}
          onMouseDown={keepSelection} onClick={() => exec('insertOrderedList')}>1.</button>
        <button type="button" title={t('editor.heading')} aria-label={t('editor.heading')}
          onMouseDown={keepSelection} onClick={() => exec('formatBlock', 'h3')}>H</button>
        <button type="button" title={t('editor.paragraph')} aria-label={t('editor.paragraph')}
          onMouseDown={keepSelection} onClick={() => exec('formatBlock', 'p')}>¶</button>
      </div>
      <div className="rich-body-wrap">
        <div
          ref={bodyRef}
          className="rich-body"
          contentEditable={!disabled}
          suppressContentEditableWarning
          onInput={sync}
          onBlur={sync}
          role="textbox"
          aria-multiline="true"
          aria-label={placeholder ?? t('editor.placeholder')}
        />
        {empty && !disabled && <div className="rich-placeholder">{placeholder ?? t('editor.placeholder')}</div>}
      </div>
    </div>
  )
}
