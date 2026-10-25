import { useTranslation } from 'react-i18next'
import { Button, DeleteOutlined, PauseCircleOutlined, StarFilled, StarOutlined } from '../ui'
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
  onPause,
  onRate,
  ratingDisabled = false,
  emptyHint,
}: {
  word: ReviewCardWord
  flipped: boolean
  onFlip: () => void
  onToggleFavorite?: () => void
  onDelete?: () => void
  /** 自由复习的「暂停这个词」——与收藏同一行的图标按钮 */
  onPause?: () => void
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
      <div className="review-card-body">
        <div className="review-card-head">
          {/* T437：单词与英美音标左对齐，收藏/删除（或暂停）右对齐 */}
          <div className="review-card-head-main">
            <div className="review-word">{word.form}</div>
            <div className="review-phonetic-row muted" onClick={(event) => event.stopPropagation()}>
              <span className="review-phonetic">
                {t('vocab.detail.phoneticUk')} {phonetic?.uk ?? '—'}
                <SpeakerButton form={word.form} accent="uk" shortcut="u" compact variant="text" />
              </span>
              <span className="review-phonetic">
                {t('vocab.detail.phoneticUs')} {phonetic?.us ?? '—'}
                <SpeakerButton form={word.form} accent="us" shortcut="a" compact variant="text" />
              </span>
            </div>
          </div>
          {(onToggleFavorite || (!flipped && (onDelete || onPause))) && (
            <div className="review-card-head-actions" onClick={(event) => event.stopPropagation()}>
              {onToggleFavorite && (
                <button
                  type="button"
                  className={`review-icon-action${word.favorite ? ' is-on' : ''}`}
                  aria-label={word.favorite ? t('vocab.library.unfavorite') : t('vocab.library.favorite')}
                  aria-pressed={!!word.favorite}
                  title={`${word.favorite ? t('vocab.library.unfavorite') : t('vocab.library.favorite')} · F`}
                  onClick={onToggleFavorite}
                >
                  {word.favorite ? <StarFilled /> : <StarOutlined />}
                </button>
              )}
              {!flipped && onDelete && (
                <button
                  type="button"
                  className="review-icon-action is-danger"
                  aria-label={t('vocab.library.delete')}
                  title={`${t('vocab.library.delete')} · Delete`}
                  onClick={onDelete}
                >
                  <DeleteOutlined />
                </button>
              )}
              {!flipped && onPause && (
                <button
                  type="button"
                  className="review-icon-action"
                  aria-label={t('vocab.freeReview.pause')}
                  title={`${t('vocab.freeReview.pause')} · S`}
                  onClick={onPause}
                >
                  <PauseCircleOutlined />
                </button>
              )}
            </div>
          )}
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

