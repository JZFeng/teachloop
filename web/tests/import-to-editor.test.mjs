/*
 * 上传文件 → 编辑框 HTML 的回归测试（2026-10-26 用户拍板：上传只填内容、不交卷）。
 *
 * 为什么要守住这里：学生交上来的 HTML 会过服务端 `RichTextSanitizer` 白名单
 * （只留 p br strong em u ol ul li blockquote h1-h3、不留属性）。如果这里产出白名单外的标签，
 * 学生就会看到「编辑框里有、提交后没了」——这是最难解释的一类 bug。
 *
 * 运行：cd web && pnpm test
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { escapeHtml, htmlFromImportedFile, markdownToHtml, textToHtml } from '../src/exam/importToEditor.ts'

/** 服务端白名单（与 RichTextSanitizer / RichTextEditor 的 ALLOWED_TAGS 一致）。 */
const ALLOWED = new Set([
  'p', 'br', 'strong', 'em', 'u', 'ol', 'ul', 'li', 'blockquote', 'h1', 'h2', 'h3',
])

function tagsOf(html) {
  return [...html.matchAll(/<\/?([a-zA-Z0-9]+)[^>]*>/g)].map((m) => m[1].toLowerCase())
}

test('纯文本：空行分段、单换行变 <br>、尖括号被转义', () => {
  assert.equal(textToHtml('第一段\n第二行\n\n第二段'), '<p>第一段<br>第二行</p><p>第二段</p>')
  assert.equal(textToHtml('<script>alert(1)</script>'), '<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>')
  assert.equal(textToHtml(''), '')
  assert.equal(escapeHtml('a & b "c"'), 'a &amp; b &quot;c&quot;')
})

test('txt 走纯文本路径：不解释 markdown 标记', () => {
  assert.equal(htmlFromImportedFile('# 不是标题', 'txt'), '<p># 不是标题</p>')
  assert.equal(htmlFromImportedFile('a **b**', 'TXT'), '<p>a **b**</p>')
})

test('md：标题、粗斜体、列表都变成白名单里的标签', () => {
  // 解析器把 `#` 当 h2、`##` 当 h3（页面已有 h1，标题整体下移），再深按 h3 封顶。
  assert.equal(markdownToHtml('# 标题'), '<h2>标题</h2>')
  assert.equal(markdownToHtml('**粗** *斜*'), '<p><strong>粗</strong> <em>斜</em></p>')
  assert.equal(markdownToHtml('- 一\n- 二'), '<ul><li><p>一</p></li><li><p>二</p></li></ul>')
  assert.equal(markdownToHtml('1. 一\n2. 二'), '<ol><li><p>一</p></li><li><p>二</p></li></ol>')
  assert.equal(markdownToHtml('> 引用'), '<blockquote><p>引用</p></blockquote>')
})

test('md：标题深度收到 h3（服务端白名单只到 h3）', () => {
  assert.equal(markdownToHtml('#### 四级'), '<h3>四级</h3>')
})

test('md：表格降级成「单元格 | 单元格」的行，不丢内容', () => {
  const html = markdownToHtml('| a | b |\n| --- | --- |\n| 1 | 2 |')
  assert.equal(html, '<p>a | b</p><p>1 | 2</p>')
})

test('产出永远不越出服务端白名单', () => {
  const samples = [
    '# 标题',
    '**粗** *斜* `code`',
    '- 列表\n  - 嵌套',
    '> 引用',
    '```\ncode block\n```',
    '| a | b |\n| --- | --- |\n| 1 | 2 |',
    '---',
    '[链接](https://example.com)',
    '![图片](x.png)',
    '<script>alert(1)</script>',
  ]
  for (const sample of samples) {
    const html = markdownToHtml(sample)
    const unexpected = tagsOf(html).filter((tag) => !ALLOWED.has(tag))
    assert.deepEqual(unexpected, [], `越界标签：${sample} -> ${html}`)
    assert.ok(!/on\w+=/i.test(html), `不允许事件属性：${html}`)
  }
})
