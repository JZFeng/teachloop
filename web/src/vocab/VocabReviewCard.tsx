import { useTranslation } from 'react-i18next'
import { Button, DeleteOutlined, StarFilled, StarOutlined } from '../ui'
import { SpeakerButton } from './SpeakerButton'

export interface ReviewCardSense {
  pos?: string
  zh?: string
  en?: string
}

export interface ReviewCardExample {
  en?: string
  zh?: string
}

export interface ReviewCardContent {
  phonetic?: { uk?: string; us?: string }
  senses?: ReviewCardSense[]
  examples?: ReviewCardExample[]
  analysis?: string
}

export interface ReviewCardWord {
  id: string
  form: string
  favorite?: boolean
  content: ReviewCardContent | null
}

/**
 * 统一张复习卡（T435/T436）：今日复习与自由复习共用同一套卡面。
 *
 * <p>正面：单词 + 英美音标（各带喇叭）+ 收藏星标同一行，底部两档评分；
 * 翻面后是只读的「单词明细」——不显示评分、不显示删除，收藏仍在音标行。
 * 删除只在正面，且只在调用方传入 `onDelete` 时出现（自由复习不传）。
 */
export function VocabReviewCard({
  word,
  flipped,
  onFlip,
  onToggleFavorite,
  onDelete,
  onRate,
  ratingDisabled = false,
  emptyHint,
}: {
  word: ReviewCardWord
  flipped: boolean
  onFlip: () => void
  onToggleFavorite?: () => void
  onDelete?: () => void
  onRate?: (rating: 'KNOWN' | 'DONT_KNOW') => void
  ratingDisabled?: boolean
  /** 明细为空时显示的一行提示（查词/未入库/生成中） */
  emptyHint?: string
}) {
  const { t } = useTranslation()
  const content = word.content
  const phonetic = content?.phonetic

  return (
    <div
      className={`review-card${flipped ? ' is-flipped' : ''}`}
      role="button"
      tabIndex={0}
      aria-label={flipped ? t('vocab.review.back') : t('vocab.review.front')}
      onClick={onFlip}
      onKeyDown={(event) => {
        if (event.key === 'Enter') onFlip()
      }}
    >
      {!flipped && onDelete && (
        <div className="review-card-tools">
          <button
            type="button"
            className="review-icon-action is-danger"
            aria-label={t('vocab.library.delete')}
            title={`${t('vocab.library.delete')} · Delete`}
            onClick={(event) => { event.stopPropagation(); onDelete() }}
          >
            <DeleteOutlined />
          </button>
        </div>
      )}

      <div className="review-card-body">
        <div className="review-card-head">
          <div className="review-word">{word.form}</div>
          <div
            className="review-phonetic-row muted"
            onClick={(event) => event.stopPropagation()}
          >
            <span className="review-phonetic">
              {t('vocab.detail.phoneticUk')} {phonetic?.uk ?? '—'}
              <SpeakerButton form={word.form} accent="uk" shortcut="u" compact variant="text" />
            </span>
            <span className="review-phonetic">
              {t('vocab.detail.phoneticUs')} {phonetic?.us ?? '—'}
              <SpeakerButton form={word.form} accent="us" shortcut="a" compact variant="text" />
            </span>
            {onToggleFavorite && (
              <button
                type="button"
                className={`review-icon-action${word.favorite ? ' is-on' : ''}`}
                aria-label={word.favorite ? t('vocab.library.unfavorite') : t('vocab.library.favorite')}
                aria-pressed={!!word.favorite}
                title={`${word.favorite ? t('vocab.library.unfavorite') : t('vocab.library.favorite')} · F`}
                onClick={(event) => { event.stopPropagation(); onToggleFavorite() }}
              >
                {word.favorite ? <StarFilled /> : <StarOutlined />}
              </button>
            )}
          </div>
        </div>

        {flipped && (
          <div className="review-back">
            {(content?.senses?.length ?? 0) === 0
              && (content?.examples?.length ?? 0) === 0
              && !content?.analysis
              && emptyHint && <p className="muted">{emptyHint}</p>}
            {(content?.senses?.length ?? 0) > 0 && (
              <ul className="vocab-sense">
                {content!.senses!.map((sense, senseIndex) => (
                  <li key={senseIndex}>
                    {sense.pos && <span className="chip">{sense.pos}</span>} {sense.zh ?? sense.en}
                  </li>
                ))}
              </ul>
            )}
            {(content?.examples?.length ?? 0) > 0 && (
              <div className="review-examples">
                {content!.examples!.map((example, exampleIndex) => (
                  <div key={exampleIndex}>
                    <div>{example.en}</div>
                    {example.zh && <div className="muted">{example.zh}</div>}
                  </div>
                ))}
              </div>
            )}
            {content?.analysis && <p className="muted">{content.analysis}</p>}
          </div>
        )}
        {!flipped && <div className="review-hint muted">{t('vocab.review.showAnswer')}</div>}
      </div>

      {!flipped && onRate && (
        <div className="review-card-actions" onClick={(event) => event.stopPropagation()}>
          <Button
            size="large"
            className="review-unknown"
            disabled={ratingDisabled}
            onClick={() => onRate('DONT_KNOW')}
          >
            {t('vocab.review.unknown')}
          </Button>
          <Button
            size="large"
            variant="primary"
            disabled={ratingDisabled}
            onClick={() => onRate('KNOWN')}
          >
            {t('vocab.review.known')}
          </Button>
        </div>
      )}
    </div>
  )
}

