/*
 * 2026-10-30（Question → Bug 定案，AC-100.8）：管理员右侧详情面板「永远关不掉」。
 *
 * 用户原话：「点击保存或者发布它都不 dismiss。当一个操作完成之后，它应该 dismiss 掉，不然觉得很奇怪。」
 * 定案是 **A 方案**：保存 / 发布**不**自动关闭（它们是原地改同一本，保存后还要看导入统计与
 * 刷新后的覆盖率），缺的是**出口** —— 补右上角 ✕ 与 Esc；窄屏（1100 断点折成一栏）点行后
 * 把面板滚进视野，否则看起来像「点了没反应」。
 *
 * 这里用源码扫描把五件事锁住（成本很低、不依赖浏览器）：
 *   1. 面板有 ✕ 出口，文案键 `vocab.books.admin.close` 两种语言都在；
 *   2. Esc 走已有的 `useEscapeClose` 栈，且焦点在输入框里时不关；
 *   3. 关闭时**不清**还没提交的粘贴内容（误触不该把刚贴的词条弄丢），换目标才清；
 *   4. 窄屏打开面板后滚动到它，且只滚动「关 → 开」那一下；
 *   5. 保存 / 发布**不**顺带关面板（防止以后有人「顺手」加个自动关闭，把设计决定改掉）。
 *
 * 说明：这是**结构校验**；真实交互（点 ✕ / 按 Esc 收起、iPad 上点行后滚到面板）由
 * 浏览器验收覆盖，见 `specs/013-word-books/spec.md` AC-100.8。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8')
const PANEL = read('../src/vocab/books/AdminBooksPage.tsx')
const COPY = JSON.parse(read('../src/vocab/books/copy.json'))
const CSS = read('../src/vocab/books/books.css')

/** 取 `from` 到 `to` 之间的源码；找不到标记直接失败，避免断言悄悄落空。 */
function slice(source, from, to) {
  const start = source.indexOf(from)
  assert.ok(start >= 0, `找不到起点标记：${from}`)
  const end = source.indexOf(to, start + from.length)
  assert.ok(end > start, `找不到终点标记：${to}`)
  return source.slice(start, end)
}

test('面板有 ✕ 出口，文案两种语言都在', () => {
  assert.match(PANEL, /CloseOutlined/, '关掉面板要有可见图标（不许只有改 URL 一条路）')
  const head = slice(PANEL, '<div className="books-admin-panel-head">', '</div>\n\n')
  assert.match(head, /onClick=\{\(\) => selectBook\(null\)\}/, '✕ 必须调用 selectBook(null) 收起面板')
  assert.match(head, /aria-label=\{bc\('vocab\.books\.admin\.close'\)\}/, '✕ 要有可读名称')
  assert.ok(COPY['vocab.books.admin.close'], '缺少 vocab.books.admin.close 文案')
  assert.equal(typeof COPY['vocab.books.admin.close']['zh-CN'], 'string')
  assert.equal(typeof COPY['vocab.books.admin.close'].en, 'string')
})

test('Esc 收起面板，但焦点在输入框里时不关', () => {
  assert.match(PANEL, /useEscapeClose\(editKey !== null, \(\) => selectBook\(null\)/,
    'Esc 应该复用已有的弹层栈收起面板')
  assert.match(PANEL, /canClose: \(\) => !busy && !isEditingField\(\)/,
    '正在保存 / 焦点在输入框时不关（否则误按 Esc 会丢没保存的元信息）')
  assert.match(PANEL, /function isEditingField\(\)/, '缺少「焦点是否在可编辑控件里」的判定')
  assert.match(PANEL,
    /active\.tagName === 'INPUT' \|\| active\.tagName === 'TEXTAREA' \|\| active\.tagName === 'SELECT'/)
})

test('关闭保留没提交的粘贴内容，换目标才清', () => {
  const body = slice(PANEL, 'const selectBook = (key: string | null) => {', 'useEffect(() => {')
  const guard = 'if (key !== null && key !== lastPanelKey.current) {'
  const guardStart = body.indexOf(guard)
  assert.ok(guardStart >= 0, '清空粘贴内容必须在「换到另一本」的分支里，关闭（key === null）不许清')
  const guardEnd = body.indexOf('\n    }', guardStart)
  assert.ok(guardEnd > guardStart, '找不到 if 块的结尾')
  const guarded = body.slice(guardStart, guardEnd)
  assert.match(guarded, /setEntryText\(''\)/, '换目标时要清掉上一本的粘贴内容')
  const outside = body.slice(0, guardStart) + body.slice(guardEnd)
  assert.doesNotMatch(outside, /setEntryText\(''\)/, '条件外面不许再无条件清空粘贴内容')
})

test('窄屏打开面板后滚进视野，且只在「关 → 开」时滚', () => {
  assert.match(PANEL, /const panelRef = useRef<HTMLDivElement>\(null\)/, '面板要有 ref 才能滚动到它')
  assert.match(PANEL, /max-width: 1100px/, '滚动判据必须复用 books.css 的 1100 断点')
  assert.match(PANEL, /panelRef\.current\?\.scrollIntoView\(\{ block: 'start'/, '缺少滚动到面板的动作')
  assert.match(PANEL, /if \(panelWasOpen\.current\) return/, '只在关 → 开时滚；保存 / 发布不重滚')
  assert.match(CSS, /\.books-admin-panel-head > button \{\s*margin-left: auto;/, '✕ 要靠右')
})

test('保存 / 发布不自动收起面板（定案 A：原地改，不 dismiss）', () => {
  const save = slice(PANEL, 'const save = async () => {', '/** 发布 / 下架')
  assert.doesNotMatch(save, /setEditKey\(null\)|selectBook\(null\)/,
    '保存后不许自动关：导入统计与刷新后的覆盖率就在面板里，新建导入失败还要留在面板重试')
  const toggle = slice(PANEL, 'const togglePublished = async () => {', 'const columns: Column<AdminBook>[]')
  assert.doesNotMatch(toggle, /setEditKey\(null\)|selectBook\(null\)/,
    '发布 / 下架后不许自动关：面板是这本书的详情，不是一次性表单')
})
