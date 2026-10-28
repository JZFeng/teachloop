/**
 * 进度条：用一条定宽的条表达「已经准备好多少个 / 一共要准备多少个」。
 *
 * 为什么进组件层而不是在页面里写一条 div：`check-ui` 第 1、2 条禁止页面写颜色，
 * 条形颜色只能来自 `styles/tokens.css`，所以横条本体必须住在 ui/ 里。
 *
 * 2026-10-26 用户要求：`today-review` 与 `free-review` 的「数据准备中… 已准备 5/10」
 * 只有文字没有进度感，要加进度条。两个页面用同一个组件，避免各写一套。
 */
export function Progress({
  value,
  max,
  label,
  showText = false,
}: {
  /** 已完成的数量。 */
  value: number
  /** 总量；≤0 时按 1 处理，避免除零。 */
  max: number
  /** 无障碍名称；不显示文字时读屏靠它。 */
  label?: string
  /** 是否在条的右侧再显示一遍分数文字（默认不显示，调用方通常已有文字）。 */
  showText?: boolean
}) {
  const total = max > 0 ? max : 1
  const clamped = Math.max(0, Math.min(total, value))
  const percent = Math.round((clamped / total) * 100)

  return (
    <div className="tl-meter">
      <div
        className="tl-meter-track"
        role="progressbar"
        aria-valuenow={clamped}
        aria-valuemin={0}
        aria-valuemax={total}
        aria-label={label}
      >
        <div className="tl-meter-fill" style={{ width: `${percent}%` }} />
      </div>
      {showText && <span className="tl-meter-text">{`${clamped}/${total}`}</span>}
    </div>
  )
}
