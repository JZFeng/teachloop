import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Button, Input, PageHeader, useToast } from '../ui'
import { ImportPastePanel } from './import/ImportPastePanel'
import type { ImportCommitResult } from './import/ImportPastePanel'
import './vocab.css'

/**
 * 加词与导入（T391 / T393 / T453）。
 *
 * 粘贴/上传、预览（计数/跳过/清洗/拼写）与提交结果这一整段已经抽到
 * `vocab/import/ImportPastePanel.tsx`：013 的「书导入」要用**同一套** UI，
 * 只是把提交目标从「我的词库」换成某本书（AC-100.6 / FR-241）。
 * 这个页面只做三件加词页特有的事：
 *   1. 传 `listName`（AC-69.5：填了词单名就把这一批词一起放进去）；
 *   2. 提交成功后提示「加进哪份词单 / 词单达上限」并给出「去词库」；
 *   3. 提供「返回词库」。
 */
export default function VocabAddWordsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const toast = useToast()
  const [params] = useSearchParams()
  const [listName, setListName] = useState('')

  const name = listName.trim()

  const onCommitted = (committed: ImportCommitResult) => {
    const attached = committed.list
    if (attached?.skippedReason) {
      toast.error(t('vocab.add.listLimitSkipped', { name: attached.name }))
    } else if (attached) {
      toast.success(t('vocab.add.addedToList', { name: attached.name, added: attached.added }))
    } else {
      toast.success(t('vocab.add.done'))
    }
  }

  return (
    <>
      <PageHeader title={t('vocab.add.title')} />
      <div className="card" style={{ maxWidth: 820 }}>
        <ImportPastePanel
          previewPath="/api/vocab/import/preview"
          commitPath="/api/vocab/import/commit"
          defaultTab={params.get('tab') === 'file' ? 'file' : 'paste'}
          extraFields={{ language: 'en', listName: name || null }}
          extraHint={t('vocab.add.listNameHint')}
          extraInput={(
            <Input value={listName} placeholder={t('vocab.add.listName')} style={{ maxWidth: 240 }}
              onChange={(event) => setListName(event.target.value)} />
          )}
          onCommitted={onCommitted}
          secondaryAction={(
            <Button onClick={() => navigate('/vocab/library')}>{t('vocab.add.back')}</Button>
          )}
          doneAction={(
            <Button onClick={() => navigate('/vocab/library')}>{t('vocab.add.goLibrary')}</Button>
          )}
        />
      </div>
    </>
  )
}
