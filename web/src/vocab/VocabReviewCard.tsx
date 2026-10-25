import { useTranslation } from 'react-i18next'
import {
  BookOutlined,
  Button,
  DeleteOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  StarFilled,
  StarOutlined,
} from '../ui'
import { SpeakerButton } from './SpeakerButton'
import type { AudioLanguage } from './SpeakerButton'
import { useVocabAudioConfig } from './audioConfig'

export interface ReviewCardSense {
  pos?: string
  zh?: string
  en?: string
}

export interface ReviewCardExample {
  en?: string
  zh?: string
}

export interface ReviewCardDerivationPart {
  text?: string
  type?: string
  meaning?: string
  origin?: string
}

export interface ReviewCardDerivationStep {
  from?: string[]
  to?: string
  partOfSpeech?: string
  meaning?: string
}

export interface ReviewCardDerivation {
  parts?: ReviewCardDerivationPart[]
  chain?: ReviewCardDerivationStep[]
}

export interface ReviewCardCoreSemantics {
  core?: string
  notThis?: string
  mentalImage?: string
}

export interface ReviewCardContent {
  phonetic?: { uk?: string; us?: string }
  coreSemantics?: ReviewCardCoreSemantics
  senses?: ReviewCardSense[]
  examples?: ReviewCardExample[]
  analysis?: string
  derivation?: ReviewCardDerivation
}

export interface ReviewCardWord {
  id: string
  form: string
  /** 词条语言（en/zh/es…）：例句喇叭跟着它选后端音色与浏览器语音 */
  language?: string
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
  onAdd,
  onAddToList,
  onDelete,
  paused = false,
  onTogglePause,
  onRate,
  ratingDisabled = false,
  emptyHint,
  notice,
  centered = false,
}: {
  word: ReviewCardWord
  flipped: boolean
  onFlip: () => void
  onToggleFavorite?: () => void
  /** 不在库时的「＋ 加入词库」，与收藏同一行的图标按钮 */
  onAdd?: () => void
  /** AC-69.5 查词页入口：「加入词单」图标，点开页内弹层 */
  onAddToList?: () => void
  onDelete?: () => void
  /** 自由复习的暂停开关：`paused` 决定图标（⏸/▶），点一下切换 */
  paused?: boolean
  onTogglePause?: () => void
  onRate?: (rating: 'KNOWN' | 'DONT_KNOW') => void
  ratingDisabled?: boolean
  /** 明细为空时显示的一行提示（查词/未入库/生成中） */
  emptyHint?: string
  /** 覆盖正面提示行（例如「已暂停」） */
  notice?: string
  /**
   * T440：卡片正面（review-front）版式——单词居中、英美音标另起一行居中；**只作用于未翻面**。
   * 卡片反面（review-back）与查词页（WordDetailPage）都保持 T437 的左对齐一行版式。
   * 今日复习 / 自由复习传 true，查词页不传。
   */
  centered?: boolean
}) {
  const { t } = useTranslation()
  // 决策 019 / AC-57.6：服务端关掉例句朗读时整卡不渲染例句喇叭。
  const { sentenceAudio } = useVocabAudioConfig()
  const content = word.content
  const phonetic = content?.phonetic
  // 019：例句喇叭跟随词条语言，避免中文 / 西语词条被硬编码读成英文音。
  const exampleLang: AudioLanguage =
    word.language === 'zh' ? 'zh' : word.language === 'es' ? 'es' : 'en'

  return (
    <div
      className={`review-card${flipped ? ' is-flipped' : ''}${centered ? ' is-centered' : ''}`}
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
          {/* T437 查词页：单词与英美音标左对齐；T440 复习页（centered）：单词居中、音标另起一行居中。收藏/删除（或暂停）始终在右上角 */}
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
          {(onToggleFavorite || onAdd || onAddToList || (!flipped && (onDelete || onTogglePause))) && (
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
              {onAdd && (
                <button
                  type="button"
                  className="review-icon-action"
                  aria-label={t('vocab.detail.addToLibrary')}
                  title={t('vocab.detail.addToLibrary')}
                  onClick={onAdd}
                >
                  <PlusOutlined />
                </button>
              )}
              {onAddToList && (
                <button
                  type="button"
                  className="review-icon-action"
                  aria-label={t('vocab.lists.addToLists')}
                  title={t('vocab.lists.addToLists')}
                  onClick={onAddToList}
                >
                  <BookOutlined />
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
              {!flipped && onTogglePause && (
                <button
                  type="button"
                  className={`review-icon-action${paused ? ' is-on' : ''}`}
                  aria-label={paused ? t('vocab.library.resume') : t('vocab.freeReview.pause')}
                  aria-pressed={paused}
                  title={`${paused ? t('vocab.library.resume') : t('vocab.freeReview.pause')} · S`}
                  onClick={onTogglePause}
                >
                  {paused ? <PlayCircleOutlined /> : <PauseCircleOutlined />}
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
                  // 决策 019 / AC-57.6：每条例句右侧一个小喇叭；句子与翻译竖排，
                  // 喇叭与文本行 flex 同行，长句在文本列里换行、不挤压喇叭。
                  <div
                    key={exampleIndex}
                    style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}
                  >
                    <div style={{ flex: '1 1 auto', minWidth: 0, overflowWrap: 'anywhere' }}>
                      {example.en && <div>{example.en}</div>}
                      {example.zh && <div className="muted">{example.zh}</div>}
                    </div>
                    {sentenceAudio && example.en && (
                      // 卡片整体是「点击翻面」，喇叭上的点击/回车必须留在本地。
                      <span
                        style={{ flex: '0 0 auto' }}
                        onClick={(event) => event.stopPropagation()}
                        onKeyDown={(event) => event.stopPropagation()}
                      >
                        <SpeakerButton text={example.en} lang={exampleLang} compact variant="text" />
                      </span>
                    )}
                  </div>
                ))}
              </div>
            )}
            {content?.analysis && <p className="muted">{content.analysis}</p>}
            {content?.derivation && (
              <div className="derivation">
                <div className="derivation-title">{t('vocab.detail.derivation')}</div>
                {content.derivation.parts && content.derivation.parts.length > 0 && (
                  <div className="derivation-parts">
                    {content.derivation.parts.map((part, index) => (
                      <span className="derivation-part" key={`${part.text}-${index}`}>
                        <b className="mono">{part.text}</b>
                        {part.type && <em>{part.type}</em>}
                        {part.meaning && <span>{part.meaning}</span>}
                        {part.origin && <span className="muted">{part.origin}</span>}
                      </span>
                    ))}
                  </div>
                )}
                {content.derivation.chain && content.derivation.chain.length > 0 && (
                  <ol className="derivation-chain">
                    {content.derivation.chain.map((step, index) => (
                      <li key={`${step.to}-${index}`}>
                        <span className="mono">{(step.from ?? []).join(' + ')}</span>
                        <span className="derivation-arrow">→</span>
                        <b className="mono">{step.to}</b>
                        {step.partOfSpeech && <em>{step.partOfSpeech}</em>}
                        {step.meaning && <span className="muted">{step.meaning}</span>}
                      </li>
                    ))}
                  </ol>
                )}
              </div>
            )}
          </div>
        )}
        {!flipped && <div className="review-hint muted">{notice ?? t('vocab.review.showAnswer')}</div>}
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

