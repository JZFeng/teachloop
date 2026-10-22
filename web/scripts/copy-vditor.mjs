/*
 * 把 Vditor 的运行时资源复制到 `public/vditor/dist/`，供组件用 `cdn: '/vditor'` 指向本地。
 *
 * 为什么需要：Vditor 不是纯 JS 包——它运行时还要按需拉取 lute 引擎（Markdown 解析，3.7 MB）、
 * 图标与语言包；默认从 unpkg CDN 拉，内网 / 离线环境会白屏。复制到 public 后由本站服务。
 *
 * 只复制定点目录，不整包复制 23 MB 的 dist：
 *   js/lute    Markdown 引擎（必需）
 *   js/i18n    语言包（zh_CN / en_US）
 *   js/icons   工具条图标
 *   css         预览主题（SV 模式用）
 * hljs / katex / mathjax 已通过 options.preview 关闭，不复制（省 2 MB+）。
 *
 * 产物进 .gitignore，由 `pnpm dev` / `pnpm build` 每次重新生成。
 */
import { cp, mkdir, rm, stat } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const source = resolve(webRoot, 'node_modules/vditor/dist')
const target = resolve(webRoot, 'public/vditor/dist')

const ENTRIES = ['js/lute', 'js/i18n', 'js/icons', 'css']

async function exists(path) {
  try {
    await stat(path)
    return true
  } catch {
    return false
  }
}

if (!(await exists(source))) {
  console.error(`[copy-vditor] 找不到 ${source}，先运行 pnpm install`)
  process.exit(1)
}

await rm(target, { recursive: true, force: true })
await mkdir(target, { recursive: true })
for (const entry of ENTRIES) {
  const from = resolve(source, entry)
  if (!(await exists(from))) {
    console.error(`[copy-vditor] 缺少 ${entry}，Vditor 版本可能变了`)
    process.exit(1)
  }
  await cp(from, resolve(target, entry), { recursive: true })
}
console.log(`[copy-vditor] 已复制 ${ENTRIES.join(', ')} → public/vditor/dist`)
