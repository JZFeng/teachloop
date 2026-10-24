import { useTranslation } from 'react-i18next'
import { Empty, PageHeader } from '../ui'

/** T389 路由骨架：T403 在这里填两档评分卡、翻面与键盘操作。 */
export default function VocabReviewPage() {
  const { t } = useTranslation()
  return (
    <>
      <PageHeader title={t('vocab.review.title')} />
      <Empty>{t('vocab.review.empty')}</Empty>
    </>
  )
}
