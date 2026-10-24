import { useTranslation } from 'react-i18next'
import { Empty, PageHeader } from '../ui'

/** T389 路由骨架：T408 在这里填词集搜索下拉、班级平铺多选与截止时间。 */
export default function VocabAssignPage() {
  const { t } = useTranslation()
  return (
    <>
      <PageHeader title={t('vocab.assign.title')} />
      <Empty>{t('vocab.assign.empty')}</Empty>
    </>
  )
}
