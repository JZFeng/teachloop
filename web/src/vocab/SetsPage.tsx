import { useTranslation } from 'react-i18next'
import { Empty, PageHeader } from '../ui'

/** T389 路由骨架：T407 在这里填词集表格、批量条与编辑页入口。 */
export default function VocabSetsPage() {
  const { t } = useTranslation()
  return (
    <>
      <PageHeader title={t('vocab.sets.title')} />
      <Empty>{t('vocab.sets.empty')}</Empty>
    </>
  )
}
