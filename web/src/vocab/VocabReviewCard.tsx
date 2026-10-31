import { Fragment, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  ApartmentOutlined,
  BookOutlined,
  Button,
  DeleteOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  StarFilled,
  StarOutlined,
  DownOutlined,
  SyncOutlined,
  TeamOutlined,
} from '../ui'
import { SpeakerButton } from './SpeakerButton'
import type { AudioLanguage } from './SpeakerButton'
import { useVocabAudioConfig } from './audioConfig'
import { familyAddsNothing, splitSenseText } from './morphology'
import { resolveCardBlocks, type ReviewCardBlock } from './reviewBlocks'

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
  /** 实验：模型生成的小 SVG（后端已做安全过滤） */
  image?: string
}

export interface ReviewCardMorphologyPart {
  text?: string
  type?: string
  meaning?: string
  origin?: string
}

export interface ReviewCardDerivative {
  word?: string
  partOfSpeech?: string
  meaning?: string
}

export interface ReviewCardInflection {
  word?: string
  label?: string
}

export interface ReviewCardFamily {
  word?: string
  partOfSpeech?: string
  meaning?: string
}

export interface ReviewCardMorphology {
  modernNote?: string
  parts?: ReviewCardMorphologyPart[]
  chain?: ReviewCardDerivationStep[]
  derivatives?: ReviewCardDerivative[]
  inflections?: ReviewCardInflection[]
  family?: ReviewCardFamily[]
}

export interface ReviewCardEtymology {
  text?: string
}

export interface ReviewCardSynonymsBlock {
  items?: ReviewCardWordPair[]
  antonyms?: ReviewCardWordPair[]
}

export interface ReviewCardWordPair {
  word?: string
  pos?: string
  nuance?: string
}

export interface ReviewCardContent {
  /** V3 内容：后端 ContentView 标记；前端据此走新卡面 */
  schemaVersion?: number
  phonetic?: { uk?: string; us?: string }
  coreSemantics?: ReviewCardCoreSemantics
  senses?: ReviewCardSense[]
  examples?: ReviewCardExample[]
  analysis?: string
  derivation?: ReviewCardDerivation
  /** US-88：近义 / 反义（后端 ContentView 从 v2 memoryAids 映射） */
  synonyms?: ReviewCardWordPair[]
  antonyms?: ReviewCardWordPair[]
  /** 词族：同族词，最多 8 个 */
  family?: string[]
  /** T594：后端输出的有序块（顺序 = 用户偏好顺序，hidden / 无内容的块不出现）；v1 旧内容没有 */
  blocks?: ReviewCardBlock[]
  /** T594：第 9 块配图；有图才有这个字段 */
  image?: { url?: string }
  /**
   * 2026-10-31：词卡配图地址（后端只在 WebP 真的存在时才给）。渲染在「语义核心」块右侧，
   * 88×88、圆角 8px、1px `var(--line)` 边框、`object-fit: cover`；没有就不渲染、不占位。
   */
  imageUrl?: string
  /** V3：构词与派生（现代构词 + 派生/词形变化 + 带 POS/含义的词族） */
  morphology?: ReviewCardMorphology
  /** V3：词源单独一段 */
  etymology?: ReviewCardEtymology
}

export interface ReviewCardWord {
  id: string
  form: string
  /** 词条语言（en/zh/es…）：例句喇叭跟着它选后端音色与浏览器语音 */
  language?: string
  favorite?: boolean
  content: ReviewCardContent | null
  /** AC-87.9：该词最近一次小测答错的时间；有值才渲染提示 */
  lastQuizWrongAt?: string | null
}

/** 渲染器只认「非数组对象」，其余类型一律当没有这块。 */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

const POS_SHORT: Record<string, string> = {
  adjective: 'adj.', adverb: 'adv.', noun: 'n.', verb: 'v.', pronoun: 'pron.',
  preposition: 'prep.', conjunction: 'conj.', interjection: 'interj.', determiner: 'det.',
  numeral: 'num.', article: 'art.',
}

/** 词性缩写：卡片上只留 adj./adv./n./v.，不把 adjective 塞进 44px 的列里。 */
function shortPos(value?: string): string {
  if (!value) return ''
  return value.split(',').map((part) => {
    const key = part.trim().toLowerCase()
    return POS_SHORT[key] ?? part.trim()
  }).join(', ')
}

/** 提示里只显示 M-D，年份对「上次小测」没有意义。 */
function shortDate(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return `${date.getMonth() + 1}-${String(date.getDate()).padStart(2, '0')}`
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
  flipDisabled = false,
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
  blocks,
  hidden,
}: {
  word: ReviewCardWord
  flipped: boolean
  onFlip: () => void
  /**
   * 2026-10-31 用户要求：查词页（word-details）只显示卡背，不许翻到正面。
   * 传 true 时卡片退化成静态块——不挂 role="button" / tabIndex / onClick / onKeyDown，
   * 光标回到默认。今日复习与自由复习两个调用点不传，行为不变。
   */
  flipDisabled?: boolean
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
   * T440 / T510：卡片正面（review-front）版式——单词居中、英美音标另起一行居中；**只作用于未翻面**。
   * 卡片反面（review-back）保持 T437 的左对齐一行版式。
   * T510（2026-10-26 用户要求）起查词页（WordDetailPage）的正面也用这套版式，所以三个调用点都传 true。
   */
  centered?: boolean
  /** 反面块顺序（US-88）；不传 = 默认顺序。T594 起只给没有 blocks 字段的旧内容用 */
  blocks?: string[]
  /** 关掉的块：顺序不决定有无，这一段才决定。T594 起只给没有 blocks 字段的旧内容用 */
  hidden?: string[]
}) {
  const { t } = useTranslation()
  // 决策 019 / AC-57.6：服务端关掉例句朗读时整卡不渲染例句喇叭。
  const { sentenceAudio } = useVocabAudioConfig()
  const content = word.content
  const phonetic = content?.phonetic
  // 019：例句喇叭跟随词条语言，避免中文 / 西语词条被硬编码读成英文音。
  const exampleLang: AudioLanguage =
    word.language === 'zh' ? 'zh' : word.language === 'es' ? 'es' : 'en'
  const isV3 = content?.schemaVersion === 3 || !!content?.morphology
  // V3 例句默认 3 条；展开只影响例句块（2026-10-31 用户要求）。
  const [showAllExamples, setShowAllExamples] = useState(false)
  useEffect(() => setShowAllExamples(false), [word.id])

  // T594：v2 内容由后端给有序 blocks（顺序、有无都由后端定）；v1 旧内容没有 blocks
  // 字段，回退到「偏好顺序 + content.* 字段」，所以 blocks / hidden 两个 props 仍然保留。
  const visibleBlocks = resolveCardBlocks(content, blocks, hidden)
  // 空提示只看「内容里根本没有块」：用户自己把块全关掉不算空内容。
  const hasContentBlocks = resolveCardBlocks(content, blocks, []).length > 0
  // 块顺序只有一个真源：后端 content.blocks（2026-10-31 撤掉前端 orderV3Blocks 那层归一）。
  const v3Blocks = isV3 ? resolveCardBlocks(content) : []

  /**
   * 2026-10-31：词卡配图（113 张定稿 WebP）。后端只在图片**真的存在**时才给 `imageUrl`，
   * 所以这里判空一次就够——没有图的词整块不渲染、不占位，也不会打出破图请求。
   * 位置固定在「语义核心」块右侧；review-back 与 word-details 共用这张卡，两处一起生效。
   */
  const imageUrl =
    typeof content?.imageUrl === 'string' && content.imageUrl.trim() !== ''
      ? content.imageUrl
      : undefined

  /** 配图（88×88、圆角 8px、1px 边框、cover）；与块内的 inline SVG 二选一，避免一行塞两张图。 */
  const renderWordImage = () => (imageUrl ? (
    <img
      className="review-word-image"
      src={imageUrl}
      alt=""
      width={88}
      height={88}
      loading="lazy"
      decoding="async"
    />
  ) : null)

  /** 每块只吃 payload：类型对不上就返回 null，不读 content.*、不崩。 */
  const renderBlock = (id: string, payload: unknown) => {
    switch (id) {
      case 'core': {
        if (!isRecord(payload)) return null
        const core = payload as ReviewCardCoreSemantics
        return (
          <div className="core-semantics">
            <div className="core-semantics-text">
              {core.core && <p className="core-semantics-core">{core.core}</p>}
              {core.notThis && <p className="core-semantics-not muted">{core.notThis}</p>}
              {core.mentalImage && <p className="core-semantics-image">{core.mentalImage}</p>}
            </div>
            {imageUrl ? renderWordImage() : core.image && (
              <div className="core-semantics-art" aria-hidden="true"
                dangerouslySetInnerHTML={{ __html: core.image }} />
            )}
          </div>
        )
      }
      case 'senses': {
        if (!Array.isArray(payload) || payload.length === 0) return null
        const senses = payload as ReviewCardSense[]
        return (
          <ul className="vocab-sense">
            {senses.map((sense, senseIndex) => (
              <li key={senseIndex}>
                {sense.pos && <span className="chip">{sense.pos}</span>} {sense.zh ?? sense.en}
              </li>
            ))}
          </ul>
        )
      }
      case 'examples': {
        if (!Array.isArray(payload) || payload.length === 0) return null
        const examples = payload as ReviewCardExample[]
        return (
          <div className="review-examples">
            {examples.map((example, exampleIndex) => (
              <div key={exampleIndex} style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
                <div style={{ flex: '1 1 auto', minWidth: 0, overflowWrap: 'anywhere' }}>
                  {example.en && <div>{example.en}</div>}
                  {example.zh && <div className="muted">{example.zh}</div>}
                </div>
                {sentenceAudio && example.en && (
                  <span style={{ flex: '0 0 auto' }}
                    onClick={(event) => event.stopPropagation()}
                    onKeyDown={(event) => event.stopPropagation()}>
                    <SpeakerButton text={example.en} lang={exampleLang} compact variant="text" />
                  </span>
                )}
              </div>
            ))}
          </div>
        )
      }
      case 'synonyms':
      case 'antonyms': {
        if (!Array.isArray(payload) || payload.length === 0) return null
        const pairs = payload as ReviewCardWordPair[]
        return (
          <div className="word-pairs">
            <div className="derivation-title">{t(`vocab.blocks.${id}`)}</div>
            <ul className="word-pair-list">
              {pairs.map((pair, index) => (
                <li key={`${pair.word}-${index}`}>
                  <b>{pair.word}</b>
                  {pair.pos && <em>{pair.pos}</em>}
                  {pair.nuance && <span className="muted">{pair.nuance}</span>}
                </li>
              ))}
            </ul>
          </div>
        )
      }
      case 'family': {
        if (!Array.isArray(payload) || payload.length === 0) return null
        const family = payload as string[]
        return (
          <div className="word-pairs">
            <div className="derivation-title">{t('vocab.blocks.family')}</div>
            <div className="word-family-row">
              {family.slice(0, 8).map((member) => (
                <span className="chip" key={member}>{member}</span>
              ))}
            </div>
          </div>
        )
      }
      case 'derivation': {
        if (!isRecord(payload)) return null
        const derivation = payload as ReviewCardDerivation
        return (
          <div className="derivation">
            <div className="derivation-title">{t('vocab.detail.derivation')}</div>
            {(derivation.parts?.length ?? 0) > 0 && (
              <div className="derivation-parts">
                {derivation.parts!.map((part, index) => (
                  <span className="derivation-part" key={`${part.text}-${index}`}>
                    <b className="mono">{part.text}</b>
                    {part.type && <em>{part.type}</em>}
                    {part.meaning && <span>{part.meaning}</span>}
                    {part.origin && <span className="muted">{part.origin}</span>}
                  </span>
                ))}
              </div>
            )}
            {(derivation.chain?.length ?? 0) > 0 && (
              <ol className="derivation-chain">
                {derivation.chain!.map((step, index) => (
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
        )
      }
      case 'analysis': {
        if (typeof payload !== 'string' || payload.trim() === '') return null
        return <p className="muted">{payload}</p>
      }
      case 'image': {
        if (!isRecord(payload)) return null
        const url = (payload as { url?: unknown }).url
        if (typeof url !== 'string' || url.trim() === '') return null
        return (
          <div className="review-image-block">
            <img src={url} alt="" loading="lazy" />
          </div>
        )
      }
      default:
        return null
    }
  }

  const renderEn = (value?: string) => {
    if (!value) return null
    const escaped = word.form.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const parts = value.split(new RegExp(`(${escaped})`, 'ig'))
    return parts.map((part, index) => (
      part.toLowerCase() === word.form.toLowerCase()
        ? <mark key={index}>{part}</mark>
        : <Fragment key={index}>{part}</Fragment>
    ))
  }

  const renderExample = (example: ReviewCardExample, index: number) => (
    <div className="v3-example" key={index}>
      <div>
        <div className="v3-en">{renderEn(example.en)}</div>
        {example.zh && <div className="v3-zh">{example.zh}</div>}
      </div>
      {sentenceAudio && example.en && (
        <span className="v3-example-audio" onClick={(event) => event.stopPropagation()}>
          <SpeakerButton text={example.en} lang={exampleLang} compact variant="text" />
        </span>
      )}
    </div>
  )

  const renderV3Block = (id: string, payload: unknown) => {
    switch (id) {
      case 'senses': {
        if (!Array.isArray(payload) || payload.length === 0) return null
        const senses = payload as ReviewCardSense[]
        // 2026-10-31 方案 5：义项一行一个、带编号，词性做成浅灰标签只出现一次。
        // AI 内容常把多个义项写进同一个 zh 字符串（「1. …；2. …；3. …」），
        // 所以先按编号拆（splitSenseText 只拆 ≥2 个编号的句子，不猜断句）。
        const rows: { pos: string; text: string }[] = []
        for (const sense of senses) {
          const pos = shortPos(sense.pos)
          for (const text of splitSenseText(sense.zh ?? sense.en)) rows.push({ pos, text })
        }
        if (rows.length === 0) return null
        const posValues = [...new Set(rows.map((row) => row.pos).filter(Boolean))]
        const sharedPos = posValues.length === 1 ? posValues[0] : ''
        return (
          <div className="v3-senses-block">
            {sharedPos && <span className="v3-pos v3-pos--tag">{sharedPos}</span>}
            <ol className="v3-senses">
              {rows.map((row, index) => (
                <li key={index}>
                  <span className="v3-sense-no" aria-hidden="true">{index + 1}.</span>
                  {!sharedPos && row.pos && (
                    <span className="v3-pos v3-pos--tag">{row.pos}</span>
                  )}
                  <span className="v3-meaning">{row.text}</span>
                </li>
              ))}
            </ol>
          </div>
        )
      }
      case 'morphology': {
        if (!isRecord(payload)) return null
        const morphology = payload as ReviewCardMorphology
        const parts = morphology.parts ?? []
        const chain = morphology.chain ?? []
        const derivatives = morphology.derivatives ?? []
        const inflections = morphology.inflections ?? []
        const family = morphology.family ?? []
        return (
          <section className="v3-section" aria-labelledby="v3MorphTitle">
            <h3 id="v3MorphTitle">{t('vocab.card.morphology')}</h3>
            {morphology.modernNote && <p className="v3-morph-note">{morphology.modernNote}</p>}
            {parts.length > 0 && (
              <div className="v3-morph-row">
                <span className="v3-morph-label">{t('vocab.card.structure')}</span>
                <div className="v3-parts">
                  {parts.map((part, index) => (
                    <span className="v3-part" key={`${part.text}-${index}`}>
                      <b className="mono">{part.text}</b>
                      {part.type && <em>{part.type}</em>}
                      {part.meaning && <span>{part.meaning}</span>}
                      {part.origin && <span className="muted">{part.origin}</span>}
                    </span>
                  ))}
                </div>
              </div>
            )}
            {chain.length > 0 && (
              <div className="v3-morph-row">
                <span className="v3-morph-label">{t('vocab.card.chain')}</span>
                <ol className="v3-chain">
                  {chain.map((step, index) => (
                    <li key={`${step.to}-${index}`}>
                      <span className="mono">{(step.from ?? []).join(' + ')}</span>
                      <span className="v3-arrow">→</span>
                      <b className="mono">{step.to}</b>
                      {step.partOfSpeech && <em>{step.partOfSpeech}</em>}
                      {step.meaning && <span className="muted">{step.meaning}</span>}
                    </li>
                  ))}
                </ol>
              </div>
            )}
            {inflections.length > 0 && (
              <div className="v3-morph-group">
                <div className="v3-morph-group-label">
                  <SyncOutlined aria-hidden="true" />
                  {t('vocab.card.inflections')}
                </div>
                <div className="v3-morph-cards">
                  {inflections.map((item, index) => (
                    <div className="v3-morph-card" key={`${item.word}-${index}`}>
                      <b>{item.word}</b>
                      {item.label && <span className="v3-morph-gloss">{item.label}</span>}
                    </div>
                  ))}
                </div>
              </div>
            )}
            {derivatives.length > 0 && (
              <div className="v3-morph-group">
                <div className="v3-morph-group-label">
                  <ApartmentOutlined aria-hidden="true" />
                  {t('vocab.card.derivatives')}
                </div>
                <div className="v3-morph-cards">
                  {derivatives.map((item, index) => (
                    <div className="v3-morph-card" key={`${item.word}-${index}`}>
                      <b>{item.word}</b>
                      {item.partOfSpeech && (
                        <span className="v3-pos v3-pos--tag">{shortPos(item.partOfSpeech)}</span>
                      )}
                      {item.meaning && <span className="v3-morph-gloss">{item.meaning}</span>}
                    </div>
                  ))}
                </div>
              </div>
            )}
            {family.length > 0 && !familyAddsNothing(word.form, morphology) && (
              <div className="v3-morph-group">
                <div className="v3-morph-group-label">
                  <TeamOutlined aria-hidden="true" />
                  {t('vocab.card.family')}
                </div>
                <div className="v3-morph-cards">
                  {family.map((item, index) => (
                    <div className="v3-morph-card" key={`${item.word}-${index}`}>
                      <b>{item.word}</b>
                      {item.partOfSpeech && (
                        <span className="v3-pos v3-pos--tag">{shortPos(item.partOfSpeech)}</span>
                      )}
                      {item.meaning && <span className="v3-morph-gloss">{item.meaning}</span>}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </section>
        )
      }
      case 'core': {
        if (!isRecord(payload)) return null
        const core = payload as ReviewCardCoreSemantics
        return (
          <section className="v3-section" aria-labelledby="v3CoreTitle">
            <h3 id="v3CoreTitle">{t('vocab.card.core')}</h3>
            <div className="v3-core-row">
              <div className="v3-core">
                {core.core && <p className="v3-core-core">{core.core}</p>}
                {core.notThis && <p className="v3-core-not">{core.notThis}</p>}
                {core.mentalImage && <p className="v3-core-image">{core.mentalImage}</p>}
              </div>
              {renderWordImage()}
            </div>
          </section>
        )
      }
      case 'examples': {
        if (!Array.isArray(payload) || payload.length === 0) return null
        const examples = payload as ReviewCardExample[]
        const shown = showAllExamples ? examples : examples.slice(0, 3)
        const extra = Math.max(0, examples.length - 3)
        return (
          <section className="v3-section v3-section--divided" aria-labelledby="v3ExamplesTitle">
            <h3 id="v3ExamplesTitle">{t('vocab.card.examples')}</h3>
            <div className="v3-examples">{shown.map(renderExample)}</div>
            {extra > 0 && (
              <button
                type="button"
                className="v3-expand"
                aria-expanded={showAllExamples}
                onClick={(event) => {
                  event.stopPropagation()
                  setShowAllExamples((value) => !value)
                }}
              >
                {showAllExamples
                  ? t('vocab.card.lessExamples', { count: extra })
                  : t('vocab.card.moreExamples', { count: extra })}
                <DownOutlined aria-hidden="true" />
              </button>
            )}
          </section>
        )
      }
      case 'synonyms': {
        if (!isRecord(payload)) return null
        const pairs = payload as ReviewCardSynonymsBlock
        const items = pairs.items ?? []
        const antonyms = pairs.antonyms ?? []
        if (items.length === 0 && antonyms.length === 0) return null
        return (
          <section className="v3-section" aria-labelledby="v3SynTitle">
            <h3 id="v3SynTitle">{t('vocab.card.synonyms')}</h3>
            {items.length > 0 && (
              <div className="v3-syn-list">
                {items.map((pair, index) => (
                  <div className="v3-syn-row" key={`${pair.word}-${index}`}>
                    <b>{pair.word}</b>
                    <span className="v3-pos">{shortPos(pair.pos)}</span>
                    <span>{pair.nuance}</span>
                  </div>
                ))}
              </div>
            )}
            {antonyms.length > 0 && (
              <div className="v3-antonyms">
                <div className="v3-ant-label">{t('vocab.card.antonyms')}</div>
                <div className="v3-syn-list">
                  {antonyms.map((pair, index) => (
                    <div className="v3-syn-row" key={`${pair.word}-${index}`}>
                      <b>{pair.word}</b>
                      <span className="v3-pos">{shortPos(pair.pos)}</span>
                      <span>{pair.nuance}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </section>
        )
      }
      case 'etymology': {
        if (!isRecord(payload)) return null
        const etymology = payload as ReviewCardEtymology
        if (!etymology.text) return null
        return (
          <section className="v3-section v3-section--divided">
            <h3>{t('vocab.card.etymology')}</h3>
            <p className="v3-etymology">{etymology.text}</p>
          </section>
        )
      }
      default:
        return null
    }
  }

  return (
    <div
      className={`review-card${flipped ? ' is-flipped' : ''}${centered ? ' is-centered' : ''}${isV3 ? ' is-v3' : ''}${flipDisabled ? ' is-static' : ''}`}
      role={flipDisabled ? undefined : 'button'}
      tabIndex={flipDisabled ? undefined : 0}
      aria-label={flipDisabled ? undefined : (flipped ? t('vocab.review.back') : t('vocab.review.front'))}
      onClick={flipDisabled ? undefined : onFlip}
      onKeyDown={flipDisabled ? undefined : (event) => {
        if (event.key === 'Enter') onFlip()
      }}
    >
      <div className="review-card-body">
        <div className="review-card-head">
          {/* T437 查词页：单词与英美音标左对齐；T440 复习页（centered）：单词居中、音标另起一行居中。收藏/删除（或暂停）始终在右上角 */}
          <div className="review-card-head-main">
            <div className="review-word">{word.form}</div>
            <div className="review-phonetic-row muted" onClick={(event) => event.stopPropagation()}>
              {/* 2026-10-31 用户回退：始终两段「英 /…/ 🔊」「美 /…/ 🔊」。 */}
              {/* 即使英美 IPA 相同也不合并（inflect 两段都是 /ɪnˈflekt/，用户要两边各一个小喇叭）。 */}
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
            {!hasContentBlocks && emptyHint && <p className="muted">{emptyHint}</p>}
            {isV3
              ? v3Blocks.map((block) => (
                <Fragment key={block.id}>{renderV3Block(block.id, block.payload)}</Fragment>
              ))
              : visibleBlocks.map((block) => (
                <Fragment key={block.id}>{renderBlock(block.id, block.payload)}</Fragment>
              ))}
            {word.lastQuizWrongAt && (
              <p className="quiz-flag">{t('vocab.quiz.lastWrong', { date: shortDate(word.lastQuizWrongAt) })}</p>
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
