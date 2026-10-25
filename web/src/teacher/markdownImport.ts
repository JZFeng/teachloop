/*
 * 「导入 .md / .txt」的共用读取逻辑。
 *
 * 为什么单独成文件：新建作业页现在有两个字段（题目要求、评分标准）都要导入，
 * 各自复制一份 `file.text()` 会出现「一边有大小上限、一边没有」这种漂移。
 *
 * 这里只做三件事，且不解析内容 —— 解析是 rubric 预览/后端校验的事：
 *   1. 大小上限：Vditor 的 setValue 是同步的，几 MB 的文本会顶住主线程；
 *   2. 换行归一：CRLF / 单独 CR 统一成 LF，避免 rubric 逐行解析多出空行；
 *   3. 失败不抛异常：返回 reason，调用方决定提示文案（页面拿 i18n 文案）。
 */

/** 1 MB：课程 rubric 与作业说明都在几十 KB 以内，超过这个量级多半是选错文件。 */
export const MARKDOWN_IMPORT_MAX_BYTES = 1024 * 1024

export type MarkdownImportFailure = 'tooLarge' | 'empty' | 'unreadable'

export type MarkdownImportResult =
  | { ok: true; text: string }
  | { ok: false; reason: MarkdownImportFailure }

/**
 * 读一个本地 Markdown / 纯文本文件。
 *
 * @param file     浏览器选中的文件（只读取，不上传）
 * @param maxBytes 上限，测试时传小值就不用造 MB 级夹具
 */
export async function readMarkdownFile(
  file: File,
  maxBytes: number = MARKDOWN_IMPORT_MAX_BYTES,
): Promise<MarkdownImportResult> {
  if (file.size > maxBytes) return { ok: false, reason: 'tooLarge' }
  try {
    const text = (await file.text()).replace(/\r\n?/g, '\n')
    // 只有空白的文件不该替换掉用户已经写好的内容，按空文件处理。
    if (text.trim() === '') return { ok: false, reason: 'empty' }
    return { ok: true, text }
  } catch {
    return { ok: false, reason: 'unreadable' }
  }
}
