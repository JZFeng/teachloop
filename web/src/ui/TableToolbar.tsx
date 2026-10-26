import { useCallback, useState } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Modal } from 'antd'
import { Button } from './Button'

/**
 * 列表的「筛选 / 列 / 清空」标准件（2026-10-26，用户拍板：table view 统一成词库那套）。
 *
 * 三处共用：词库（自带实现）、首页「最近作业」、批改详情「提交明细」。抽出来是为了不生出
 * 第三份手写弹层——每抄一次，筛选计数、清空语义、URL 写入迟早各走各的。
 *
 * 组件只负责「收集用户选了什么」，**不碰 URL**：页面拿到 `onChange` 自己写 `useSearchParams`，
 * 这样首页的 `classes`/`state`、批改详情的 `state` 都能沿用各自的参数名。
 */
export interface FilterOption {
  value: string
  label: ReactNode
}

export interface FilterGroup {
  key: string
  label: string
  options: FilterOption[]
}

export interface TableToolbarProps {
  groups: FilterGroup[]
  /** 已选值：`{ 组key: [值…] }`，空数组 = 该组不筛（等价于「全部」）。 */
  value: Record<string, string[]>
  onChange: (next: Record<string, string[]>) => void
  /** 「列」按钮：页面传一个 <ColumnPicker …/>，不传则不显示。 */
  columnPicker?: ReactNode
  /** 右侧附加信息，如「显示 3 / 10 条」。 */
  summary?: ReactNode
  /**
   * 「清空筛选」的完整语义（2026-10-26）：除筛选组之外，页面自己的关键字（URL `q`）也一并清掉。
   * 传了它，按钮就按词库页的做法常驻显示（空态也显示，点了就是回到全量）；
   * 不传时只清筛选组，供没有搜索框的页面（批改详情「提交明细」）沿用。
   */
  onReset?: () => void
}

export function TableToolbar({ groups, value, onChange, columnPicker, summary, onReset }: TableToolbarProps) {
  const { t } = useTranslation('translation')
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState<Record<string, string[]>>(value)

  const count = Object.values(value).reduce((acc, list) => acc + list.length, 0)

  const openFilters = () => {
    setDraft(value)
    setOpen(true)
  }

  const toggle = (group: string, option: string) => {
    setDraft((prev) => {
      const current = prev[group] ?? []
      return {
        ...prev,
        [group]: current.includes(option)
          ? current.filter((item) => item !== option)
          : [...current, option],
      }
    })
  }

  const apply = () => {
    onChange(draft)
    setOpen(false)
  }

  const clear = () => {
    if (onReset) {
      onReset()
      return
    }
    onChange(Object.fromEntries(groups.map((group) => [group.key, []])))
  }

  return (
    <>
      <Button onClick={openFilters}>
        {t('common.filter')}{count > 0 ? ` · ${count}` : ''}
      </Button>
      {columnPicker}
      {/* 清空筛选常驻（与词库页一致）：空态也在，点它就是回到全量，不靠「计数 > 0」猜测能不能清。 */}
      <Button onClick={clear}>{t('common.clearFilters')}</Button>
      {summary && <span className="small muted tl-toolbar-summary">{summary}</span>}

      <Modal open={open} title={t('common.filterTitle')} onCancel={() => setOpen(false)} footer={null}>
        {groups.map((group) => (
          <div className="tl-filter-group" key={group.key}>
            <strong>{group.label}</strong>
            <div className="row" style={{ gap: 6 }}>
              {group.options.map((option) => {
                const on = (draft[group.key] ?? []).includes(option.value)
                return (
                  <button
                    key={option.value}
                    type="button"
                    className={`chip${on ? ' on' : ''}`}
                    aria-pressed={on}
                    onClick={() => toggle(group.key, option.value)}
                  >
                    {option.label}
                  </button>
                )
              })}
            </div>
          </div>
        ))}
        <div className="row" style={{ justifyContent: 'flex-end', gap: 8, marginTop: 12 }}>
          <Button onClick={() => { clear(); setOpen(false) }}>{t('common.reset')}</Button>
          <Button variant="primary" onClick={apply}>{t('common.apply')}</Button>
        </div>
      </Modal>
    </>
  )
}

/** 列偏好：显隐 + 顺序，存浏览器本地（首页与批改详情都用）。 */
export interface ColumnPrefs {
  order: string[]
  hidden: string[]
}

function normalize(storageKey: string, allowed: string[], raw: unknown): ColumnPrefs {
  const value = (raw ?? {}) as Partial<ColumnPrefs>
  const pick = (list: unknown): string[] =>
    Array.isArray(list) ? list.filter((key): key is string => allowed.includes(String(key))) : []
  const order = pick(value.order)
  return {
    order: order.length === allowed.length ? order : [...allowed],
    hidden: pick(value.hidden),
  }
}

export function useColumnPrefs(storageKey: string, defaultOrder: string[]) {
  const read = (): ColumnPrefs => {
    try {
      return normalize(storageKey, defaultOrder, JSON.parse(window.localStorage.getItem(storageKey) ?? 'null'))
    } catch {
      return { order: [...defaultOrder], hidden: [] }
    }
  }
  const [prefs, setPrefs] = useState<ColumnPrefs>(read)

  const save = useCallback((next: ColumnPrefs) => {
    const normalized = normalize(storageKey, defaultOrder, next)
    setPrefs(normalized)
    try {
      window.localStorage.setItem(storageKey, JSON.stringify(normalized))
    } catch {
      /* 隐私模式下写不进去：本次会话照常生效 */
    }
  }, [storageKey, defaultOrder])

  const toggle = useCallback((key: string) => {
    const hidden = prefs.hidden.includes(key)
      ? prefs.hidden.filter((item) => item !== key)
      : [...prefs.hidden, key]
    save({ ...prefs, hidden })
  }, [prefs, save])

  const move = useCallback((key: string, offset: number) => {
    const order = [...prefs.order]
    const index = order.indexOf(key)
    const target = index + offset
    if (index < 0 || target < 0 || target >= order.length) return
    ;[order[index], order[target]] = [order[target], order[index]]
    save({ ...prefs, order })
  }, [prefs, save])

  return {
    order: prefs.order,
    hidden: prefs.hidden,
    visible: prefs.order.filter((key) => !prefs.hidden.includes(key)),
    toggle,
    move,
  }
}

export interface ColumnPickerProps {
  open: boolean
  onClose: () => void
  order: string[]
  hidden: string[]
  labels: Record<string, string>
  onToggle: (key: string) => void
  onMove: (key: string, offset: number) => void
}

export function ColumnPicker({ open, onClose, order, hidden, labels, onToggle, onMove }: ColumnPickerProps) {
  const { t } = useTranslation('translation')
  const hiddenSet = new Set(hidden)
  return (
    <Modal open={open} title={t('common.columnsTitle')} onCancel={onClose} footer={null}>
      <p className="muted">{t('common.columnsHint')}</p>
      {order.map((key, index) => (
        <div className="tl-col-row" key={key}>
          <input
            type="checkbox"
            checked={!hiddenSet.has(key)}
            onChange={() => onToggle(key)}
            aria-label={labels[key] ?? key}
          />
          <span className="grow">{labels[key] ?? key}</span>
          <Button size="small" disabled={index === 0} onClick={() => onMove(key, -1)}>
            {t('common.moveUp')}
          </Button>
          <Button size="small" disabled={index === order.length - 1} onClick={() => onMove(key, 1)}>
            {t('common.moveDown')}
          </Button>
        </div>
      ))}
    </Modal>
  )
}
