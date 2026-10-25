import { useMemo } from 'react'
import type { ReactNode } from 'react'
import { parseMarkdown } from './markdown/parser'
import type { MdBlock, MdInline, MdListItem } from './markdown/parser'
import './markdown.css'

export interface MarkdownProps {
  /** Markdown 源文本。按 `markdown/parser.ts` 的语法子集渲染，其余按纯文本。 */
  text: string
  className?: string
}

/*
 * 只读渲染层：把 `parseMarkdown` 产出的 Block/Inline 树画成 React 元素。
 *
 * 这里刻意**不产生任何 HTML 字符串**，也不使用 dangerouslySetInnerHTML：
 * 链接、图片、原生 HTML 标签在解析阶段就已经是文字，没有转义漏网的可能。
 * 解析规则（真实作业里的 `***粗斜***`、空行分隔的列表、字母子列表）由
 * `web/tests/markdown-parser.test.mjs` 守住；本文件只负责排版。
 */

function renderInline(nodes: MdInline[], keyPrefix: string): ReactNode[] {
  return nodes.map((node, index) => {
    const key = `${keyPrefix}-i${index}`
    switch (node.type) {
      case 'text':
        // 文本片段是裸字符串：加 key 会变成 [object Object]，React 不需要文本 key
        return node.value
      case 'code':
        return <code key={key}>{node.value}</code>
      case 'br':
        return <br key={key} />
      case 'strong':
        return <strong key={key}>{renderInline(node.children, key)}</strong>
      case 'em':
        return <em key={key}>{renderInline(node.children, key)}</em>
    }
  })
}

function renderListItem(item: MdListItem, key: string): ReactNode {
  const [head, ...rest] = item.blocks
  // 首段直接铺在 <li> 里，避免列表项里出现带外边距的 <p>（行距会突然变松）
  if (head && head.type === 'paragraph') {
    return (
      <li key={key}>
        {renderInline(head.children, `${key}-p`)}
        {renderBlocks(rest, `${key}-rest`)}
      </li>
    )
  }
  return <li key={key}>{renderBlocks(item.blocks, `${key}-all`)}</li>
}

function renderBlock(block: MdBlock, key: string): ReactNode {
  switch (block.type) {
    case 'heading': {
      const children = renderInline(block.children, key)
      if (block.level <= 2) return <h2 key={key}>{children}</h2>
      if (block.level === 3) return <h3 key={key}>{children}</h3>
      return <h4 key={key}>{children}</h4>
    }
    case 'paragraph':
      return <p key={key}>{renderInline(block.children, key)}</p>
    case 'list': {
      const items = block.items.map((item, index) => renderListItem(item, `${key}-li${index}`))
      if (!block.ordered) return <ul key={key}>{items}</ul>
      return (
        <ol key={key} type={block.alpha} start={block.start > 1 ? block.start : undefined}>
          {items}
        </ol>
      )
    }
    case 'quote':
      return (
        <blockquote key={key}>
          <p>{renderInline(block.children, key)}</p>
        </blockquote>
      )
    case 'code':
      return (
        <pre key={key}>
          <code>{block.value}</code>
        </pre>
      )
    case 'hr':
      return <hr key={key} />
    case 'table':
      return (
        <table key={key}>
          <thead>
            <tr>
              {block.header.map((cell, column) => (
                <th key={`${key}-h${column}`}>{renderInline(cell, `${key}-h${column}`)}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {block.rows.map((cells, row) => (
              <tr key={`${key}-r${row}`}>
                {block.header.map((_, column) => (
                  <td key={`${key}-r${row}c${column}`}>
                    {renderInline(cells[column] ?? [], `${key}-r${row}c${column}`)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      )
  }
}

function renderBlocks(blocks: MdBlock[], keyPrefix: string): ReactNode[] {
  return blocks.map((block, index) => renderBlock(block, `${keyPrefix}-b${index}`))
}

export function Markdown({ text, className }: MarkdownProps) {
  const blocks = useMemo(() => parseMarkdown(text), [text])
  // 空文本渲染 null：空状态（提示文案、占位图）由调用方决定，不要这里塞一个空 div
  if (!text.trim() || blocks.length === 0) return null
  // 调用方可能把 md-body 再传一次（学生端就是这么用的），去重后再挂到 DOM 上
  const classes = Array.from(new Set(['md-body', ...(className ?? '').split(/\s+/).filter(Boolean)]))
  return <div className={classes.join(' ')}>{renderBlocks(blocks, 'md')}</div>
}
