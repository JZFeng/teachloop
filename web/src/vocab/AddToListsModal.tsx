import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { Alert, Button, Input, Modal, useToast } from '../ui'
import { addFormsToList, fetchLists, findMemberships } from './lists'
import type { VocabList } from './lists'
import './vocab.css'

/**
 * 「加入词单」页内弹层（AC-69.2 / AC-69.5）：查词页头部图标与词库行内操作共用。
 *
 * 三条拍板口径：已在词单的行**置灰并标「已在词单」**、重复加入不报错、底部新建词单的名字
 * 与已有词单重名时**复用已有并选中**（不报错）；不在词库的词一步入库再引用。
 */
export default function AddToListsModal({
  open,
  form,
  inLibrary,
  onClose,
  onDone,
}: {
  open: boolean
  form: string
  /** 查词页给的「在不在词库」；不在时弹层要写明「会先加入你的词库」 */
  inLibrary: boolean
  onClose: () => void
  onDone?: () => void
}) {
  const { t } = useTranslation()
  const toast = useToast()
  const [lists, setLists] = useState<VocabList[] | null>(null)
  const [members, setMembers] = useState<Set<string>>(new Set())
  const [selected, setSelected] = useState<string[]>([])
  const [newName, setNewName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const reset = useCallback(() => {
    setSelected([])
    setNewName('')
    setError(null)
  }, [])

  useEffect(() => {
    if (!open) return
    let alive = true
    reset()
    setLists(null)
    setMembers(new Set())
    fetchLists()
      .then(async (value) => {
        if (!alive) return
        setLists(value)
        const found = await findMemberships(form, value)
        if (alive) setMembers(found)
      })
      .catch((loadError) => { if (alive) setError((loadError as Error).message) })
    return () => { alive = false }
  }, [open, form, reset])

  const toggle = (list: VocabList) => {
    if (members.has(list.id)) return
    setSelected((previous) => (previous.includes(list.id)
      ? previous.filter((item) => item !== list.id)
      : [...previous, list.id]))
    setError(null)
  }

  /** 新建名字与已有词单重名时复用已有（AC-69.5），不报错、不建第二份同名。 */
  const resolveTargets = async (): Promise<{ ids: string[]; created: string[] }> => {
    const ids = [...selected]
    const created: string[] = []
    const name = newName.trim()
    if (name) {
      const duplicate = (lists ?? []).find(
        (list) => list.name.toLowerCase() === name.toLowerCase(),
      )
      if (duplicate) {
        if (!ids.includes(duplicate.id) && !members.has(duplicate.id)) ids.push(duplicate.id)
      } else {
        const list = await api.post<VocabList>('/api/vocab/lists', { name })
        ids.push(list.id)
        created.push(list.name ?? name)
      }
    }
    return { ids, created }
  }

  const submit = async () => {
    if (busy) return
    if (!newName.trim() && selected.length === 0) {
      setError(t('vocab.lists.pickOrCreate'))
      return
    }
    setBusy(true)
    setError(null)
    try {
      const { ids } = await resolveTargets()
      if (ids.length === 0) {
        setError(t('vocab.lists.pickOrCreate'))
        return
      }
      const names: string[] = []
      let added = 0
      let duplicated = 0
      const latest = await fetchLists().catch(() => lists ?? [])
      for (const listId of ids) {
        const result = await addFormsToList(listId, [form])
        added += result.added
        duplicated += result.duplicated
        const name = latest.find((list) => list.id === listId)?.name
        if (name && result.added > 0) names.push(name)
      }
      if (added > 0) {
        toast.success(t('vocab.lists.addedToast', { name: names.join(', ') }))
      } else if (duplicated > 0) {
        const name = latest.find((list) => ids.includes(list.id))?.name ?? form
        toast.info(t('vocab.lists.alreadyToast', { name }))
      }
      onClose()
      onDone?.()
    } catch (saveError) {
      setError((saveError as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open={open}
      title={t('vocab.lists.addToListsTitle')}
      onCancel={onClose}
      onOk={() => void submit()}
      okText={t('vocab.lists.add')}
      cancelText={t('common.cancel')}
      confirmLoading={busy}
    >
      <p className="muted sm">{t('vocab.lists.addToListsHint')}</p>
      {!inLibrary && (
        <p className="muted sm">{t('vocab.lists.addToListsNotInLibrary')}</p>
      )}

      {lists === null ? (
        <p className="muted sm">{t('common.loading')}</p>
      ) : lists.length === 0 ? (
        <p className="muted sm">{t('vocab.lists.noListsHint')}</p>
      ) : (
        <div style={{ margin: '8px 0' }}>
          {lists.map((list) => {
            const has = members.has(list.id)
            return (
              <label key={list.id} className="row" style={{ gap: 8, marginBottom: 4 }}>
                <input
                  type="checkbox"
                  checked={has || selected.includes(list.id)}
                  disabled={has}
                  onChange={() => toggle(list)}
                />
                <span className="grow">{list.name}</span>
                <span className="muted sm">{t('vocab.lists.wordCount', { count: list.wordCount ?? 0 })}</span>
                {has && <span className="chip">{t('vocab.lists.alreadyInList')}</span>}
              </label>
            )
          })}
        </div>
      )}

      <div style={{ margin: '8px 0 4px' }}>
        <div className="muted sm">{t('vocab.lists.newListOptional')}</div>
        <Input
          value={newName}
          maxLength={80}
          placeholder={t('vocab.lists.namePlaceholder')}
          onChange={(event) => { setNewName(event.target.value); setError(null) }}
          onPressEnter={() => void submit()}
        />
      </div>

      {error && <Alert type="error" showIcon message={error} style={{ marginTop: 8 }} />}
    </Modal>
  )
}
