import { useTranslation } from 'react-i18next'
import { Empty, PageHeader } from '../ui'

/** T389 路由骨架：T397 在这里填常驻搜索、统计卡、筛选弹窗与表格。 */
export default function VocabLibraryPage() {
  const { t } = useTranslation()
  return (
    <>
      <PageHeader title={t('vocab.library.title')} />
      <Empty>{t('vocab.library.empty')}</Empty>
    </>
  )
}
