/*
 * 上传文件 → 编辑框 HTML（2026-10-26 用户拍板：上传只把正文填进编辑框，交卷必须另点「提交作业」）。
 *
 * 为什么需要这一层：后端 `POST /api/offerings/{id}/submissions/extract` 只回**正文原文**
 * （txt/md 是文件原文，pdf/docx 是抽取的纯文本），而编辑框只认 HTML。所以：
 *   - txt / pdf / docx → 纯文本按空行分段、单换行变 `<br>`（`textToHtml`，与旧版纯文本草稿同一套规则）；
 *   - md → 复用只读渲染器那套 `parseMarkdown` 语法子集，再序列化成**服务端白名单里有的标签**。
 *
 * 为什么必须贴着白名单序列化：服务端 `RichTextSanitizer` 只留
 * `p br strong em u ol ul li blockquote h1-h3`，且不留任何属性。填进去的 `<h4>` / `<table>` /
 * `<code>` / `<a>` 会在提交时被整段剥掉——学生看到的和最后存下来的就对不上了。
 *
 * 安全边界：所有文本一律先转义，本文件不产出 `<a>` / `<img>` / `<pre>` / 事件属性，
 * 因此不需要靠下游消毒兜底。
 */
// 显式带 `.ts`：web/tests 用 `node --experimental-strip-types` 直接跑源码，Node 不做扩展名补全
// （Vite 与 tsc 都接受显式扩展名，tsconfig 里已开 allowImportingTsExtensions）。
import { parseMarkdown } from '../components/markdown/parser.ts'
import type { MdBlock, MdInline } from '../components/markdown/parser.ts'

/** 服务端白名单只到 h3，再深的标题按 h3 收起（与只读渲染器的「标题整体下移」同一个理由）。 */
const MAX_HEADING = 3

const ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
}

export function escapeHtml(value: string): string {
  return value.replace(/[&<>"]/g, (ch) => ESCAPES[ch] ?? ch)
}

/**
 * 纯文本 → 段落 HTML：空行分段，段内单换行变 `<br>`。
 *
 * 从 `AssignmentPage` 抽出来共用：旧版纯文本草稿的恢复和上传 txt/pdf/docx 的填充走同一条规则，
 * 免得两处漂移（一边分段、一边不分段）。
 */
export function textToHtml(text: string): string {
  const normalized = text.replace(/\r\n?/g, '\n')
  return normalized
    .split(/\n{2,}/)
    .map((block) => block.trim())
    .filter((block) => block.length > 0)
    .map((block) => `<p>${escapeHtml(block).replace(/\n/g, '<br>')}</p>`)
    .join('')
}

/** 行内序列化：`code` 的白名单里没有 `<code>`，退化成纯文本（反引号不带进正文）。 */
function inlineHtml(nodes: MdInline[]): string {
  return nodes
    .map((node) => {
      switch (node.type) {
        case 'text':
          return escapeHtml(node.value)
        case 'code':
          return escapeHtml(node.value)
        case 'br':
          return '<br>'
        case 'strong':
          return `<strong>${inlineHtml(node.children)}</strong>`
        case 'em':
          return `<em>${inlineHtml(node.children)}</em>`
        default:
          return ''
      }
    })
    .join('')
}

/** 块级序列化：只产出白名单内的标签；表格/代码块按文本行降级，不丢内容。 */
function blocksHtml(blocks: MdBlock[]): string {
  return blocks.map(blockHtml).join('')
}

function blockHtml(block: MdBlock): string {
  switch (block.type) {
    case 'heading': {
      const level = Math.min(Math.max(block.level, 1), MAX_HEADING)
      return `<h${level}>${inlineHtml(block.children)}</h${level}>`
    }
    case 'paragraph':
      return `<p>${inlineHtml(block.children)}</p>`
    case 'list': {
      // 字母列表（a. / b.）在编辑器里没有对应标记，统一按有序列表填入；`type` 属性也会被服务端消毒掉。
      const tag = block.ordered ? 'ol' : 'ul'
      const items = block.items
        .map((item) => `<li>${blocksHtml(item.blocks)}</li>`)
        .join('')
      return `<${tag}>${items}</${tag}>`
    }
    case 'quote':
      return `<blockquote><p>${inlineHtml(block.children)}</p></blockquote>`
    case 'code':
      // 白名单没有 <pre>/<code>：按段落 + 换行保留内容，总比整段丢掉强。
      return textToHtml(block.value)
    case 'table': {
      const rows = [block.header, ...block.rows]
        .map((row) => `<p>${row.map((cell) => inlineHtml(cell)).join(' | ')}</p>`)
        .join('')
      return rows
    }
    case 'hr':
      // 分隔线没有文字内容，白名单也没有 <hr>，直接跳过。
      return ''
    default:
      return ''
  }
}

/** Markdown 原文 → 编辑器 HTML（白名单子集）。 */
export function markdownToHtml(markdown: string): string {
  return blocksHtml(parseMarkdown(markdown))
}

/**
 * 上传文件的正文 → 编辑框 HTML。
 *
 * @param text      后端 `extract` 回的正文原文
 * @param extension 文件扩展名（大小写不敏感）；md/markdown 走 Markdown 渲染，其余按纯文本分段
 */
export function htmlFromImportedFile(text: string, extension: string): string {
  const ext = (extension ?? '').toLowerCase()
  if (ext === 'md' || ext === 'markdown') return markdownToHtml(text)
  return textToHtml(text)
}
