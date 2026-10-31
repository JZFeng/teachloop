import { useTranslation } from 'react-i18next'

/**
 * 复习会话的快捷键表（T427 起在今日复习页的 ⓘ 里；2026-10-30 / T595 随卡片搬进会话页）。
 *
 * 只列**这一屏真的支持**的键：
 * - `N` / `→`、空格、`1`、`2`、`F` 由会话页自己处理（`FreeReviewPage` 的 keydown）；
 * - `U` / `A` 由卡片里的喇叭按钮绑（`SpeakerButton` 的 `shortcut`）；
 * - `S` 是会话里的「暂停这个词」。
 *
 * 「删除当前单词 / Delete」2026-10-31 已加回：会话里删的是软删除（与词库页行内同一个后端方法），
 * 只在卡片正面生效，走二次确认。
 *
 * 用列表不用 <table>：`check-ui` 的 T441 禁止页面新写表格（表格只走 `DataTable`）。
 */
const SHORTCUTS = [
  { label: 'vocab.review.shortcutNext', keys: 'N / →' },
  { label: 'vocab.review.shortcutDetail', keys: 'Space' },
  { label: 'vocab.review.shortcutUnknown', keys: '1' },
  { label: 'vocab.review.shortcutKnown', keys: '2' },
  { label: 'vocab.audio.playUk', keys: 'U' },
  { label: 'vocab.audio.playUs', keys: 'A' },
  { label: 'vocab.review.shortcutFavorite', keys: 'F' },
  { label: 'vocab.freeReview.pauseKey', keys: 'S' },
  { label: 'vocab.review.shortcutDelete', keys: 'Delete' },
] as const

export function ReviewShortcutTable() {
  const { t } = useTranslation()
  return (
    <ul className="review-shortcut-list">
      {SHORTCUTS.map((row) => (
        <li key={row.label}>
          <span>{t(row.label)}</span>
          <kbd>{row.keys === 'Space' ? t('vocab.review.keySpace') : row.keys}</kbd>
        </li>
      ))}
    </ul>
  )
}
