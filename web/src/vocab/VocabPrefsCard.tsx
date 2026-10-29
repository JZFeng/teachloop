import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, InlineNote } from '../ui'
import { getReviewPrefs, saveReviewPrefs, type ReviewPrefs } from './prefs'
import { BLOCK_LABELS } from './wordBlocks'
import './vocab.css'

/** 三档：正常 3–49 / 进阶 50–99 / 挑战 100–200（点一下设成该档起步值）。 */
function tierOf(cards: number): 'normal' | 'hard' | 'extreme' {
  if (cards >= 100) return 'extreme'
  if (cards >= 50) return 'hard'
  return 'normal'
}

const SWITCHES = ['derivation', 'synonyms', 'antonyms', 'family'] as const

/**
 * 背单词偏好（US-88 / T555）。`/profile` 与复习页齿轮共用同一个组件、同一份数据。
 *
 * <p>任务量用三档按钮 + 数字框：档位只决定「区间与一键取值」，数字框仍可填 3–200，
 * 档位由数值自动判定——这样「三档」与「连续可配」不打架。
 */
export function VocabPrefsCard({ compact = false }: { compact?: boolean } = {}) {
  const { t } = useTranslation()
  const [prefs, setPrefs] = useState<ReviewPrefs | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    getReviewPrefs().then(setPrefs).catch((loadError) => setError((loadError as Error).message))
  }, [])

  const apply = (next: ReviewPrefs) => {
    setPrefs(next)
    setSaving(true)
    saveReviewPrefs(next)
      .then(setPrefs)
      .catch((saveError) => setError((saveError as Error).message))
      .finally(() => setSaving(false))
  }

  if (error) return <InlineNote tone="error">{error}</InlineNote>
  if (!prefs) return <p className="muted small">{t('common.loading')}</p>

  const tier = tierOf(prefs.dailyNewCards)
  const toggleHidden = (key: string) => {
    const off = new Set(prefs.hidden)
    if (off.has(key)) off.delete(key)
    else off.add(key)
    apply({ ...prefs, hidden: [...off] })
  }
  const move = (index: number, delta: number) => {
    const next = [...prefs.blocks]
    const target = index + delta
    if (target < 0 || target >= next.length) return
    ;[next[index], next[target]] = [next[target], next[index]]
    apply({ ...prefs, blocks: next })
  }

  return (
    <div className="vocab-prefs">
      <div className="pref-block">
        <div className="pref-title">{t('vocab.prefs.cards')}</div>
        <div className="pref-row">
          <div className="pref-tiers">
            {(['normal', 'hard', 'extreme'] as const).map((key, index) => (
              <Button
                key={key}
                size="small"
                variant={tier === key ? 'primary' : 'default'}
                className={`pref-tier pref-tier--${key}`}
                onClick={() => apply({
                  ...prefs,
                  dailyNewCards: [20, 50, 100][index],
                })}
              >
                {t(`vocab.prefs.tier.${key}`)}
              </Button>
            ))}
          </div>
          <input
            className="pref-number"
            type="number"
            min={3}
            max={200}
            value={prefs.dailyNewCards}
            onChange={(event) => {
              const value = Number(event.target.value)
              if (Number.isFinite(value)) {
                apply({ ...prefs, dailyNewCards: Math.min(200, Math.max(3, Math.round(value))) })
              }
            }}
          />
          <span className="muted small">{t('vocab.prefs.cardsUnit')}</span>
        </div>
        <p className="muted small">
          {prefs.dailyNewCards > 50 ? t('vocab.prefs.cardsWarn') : t('vocab.prefs.cardsHint')}
        </p>
      </div>

      <div className="pref-block">
        <div className="pref-title">{t('vocab.prefs.strategy')}</div>
        <div className="pref-tiers">
          {(['REVIEW_FIRST', 'MIXED', 'NEW_FIRST'] as const).map((key) => (
            <Button
              key={key}
              size="small"
              variant={prefs.strategy === key ? 'primary' : 'default'}
              onClick={() => apply({ ...prefs, strategy: key })}
            >
              {t(`vocab.prefs.strategyOptions.${key}`)}
            </Button>
          ))}
        </div>
        <p className="muted small">{t(`vocab.prefs.strategyHint.${prefs.strategy}`)}</p>
      </div>

      {compact && (
        <p className="muted small">{t('vocab.prefs.moreInProfile')}</p>
      )}

      {!compact && (
      <>
      <div className="pref-block">
        <div className="pref-title">{t('vocab.prefs.content')}</div>
        <div className="pref-switches">
          {SWITCHES.map((key) => (
            <label className="pref-switch" key={key}>
              <input
                type="checkbox"
                checked={!prefs.hidden.includes(key)}
                onChange={() => toggleHidden(key)}
              />
              <span>
                <b>{t(`vocab.blocks.${key}`)}</b>
                <span className="muted small">{t(BLOCK_LABELS[key].desc)}</span>
              </span>
            </label>
          ))}
        </div>
        <p className="muted small">{t('vocab.prefs.imageSkipped')}</p>
      </div>

      <div className="pref-block">
        <div className="pref-title">{t('vocab.prefs.order')}</div>
        <div className="pref-order">
          {prefs.blocks.map((key, index) => (
            <div className="pref-order-row" key={key}>
              <span className="pref-order-no">{index + 1}</span>
              <span className="pref-order-name">{t(BLOCK_LABELS[key]?.name ?? key)}</span>
              <span className="pref-order-ops">
                <Button size="small" variant="text" disabled={index === 0}
                  onClick={() => move(index, -1)} aria-label={t('vocab.prefs.moveUp')}>↑</Button>
                <Button size="small" variant="text" disabled={index === prefs.blocks.length - 1}
                  onClick={() => move(index, 1)} aria-label={t('vocab.prefs.moveDown')}>↓</Button>
              </span>
            </div>
          ))}
        </div>
        <p className="muted small">
          {t('vocab.prefs.orderHint')}
          {saving ? ` · ${t('vocab.prefs.saving')}` : ''}
        </p>
      </div>
      </>
      )}
    </div>
  )
}
