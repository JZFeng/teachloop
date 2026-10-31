import { useTranslation } from 'react-i18next'

/**
 * 复习会话的快捷键表（T427 起在今日复习页的 ⓘ 里；2026-10-30 / T595 随卡片搬进会话页）。
 *
 * 只列**这一屏真的支持**的键：
 * - `N` / `→`、空格、`1`、`2`、`F` 由会话页自己处理（`FreeReviewPage` 的 keydown）；
 * - `U` / `A` 由卡片里的喇叭按钮绑（`SpeakerButton` 的 `shortcut`）；
 * - `S` 是会话里的「暂停这个词」。
 *
 * 今日复习页已经没有卡片，所以「删除当前单词」不再有快捷键——删除入口回到词库页行内
 * （AC-59.11 的另一半）。这条差异记在 T595 的报告里，不在界面里假装还有。
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
