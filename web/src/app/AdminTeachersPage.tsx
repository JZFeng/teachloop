import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api/client'
import { Alert, Button, DataTable, InlineNote, PageHeader, Tag, useToast } from '../ui'
import type { Column, Tone } from '../ui'

/**
 * 管理员的教师管理页（AC-3.10 / 2026-10-30 用户拍板单开一页）。
 *
 * 这一页只做一件事：发教师邀请码。教师没有自助注册，邀请码是唯一入口，
 * 所以码必须**看得见**——列表常驻（可用 / 已兑换 / 已过期 / 已撤销），未过期的可复制、可撤销。
 *
 * 用户列表、改角色、授予管理员是下一份 spec（014）的事，这里只留一句边界说明，
 * 不放灰按钮进来（放了就等于承诺一个不存在的东西）。
 */
interface TeacherInviteView {
  code: string
  expiresAt: string
  createdAt: string
  status: 'ACTIVE' | 'USED' | 'REVOKED' | 'EXPIRED'
  usedByEmail: string | null
}

const STATUS_TONE: Record<TeacherInviteView['status'], Tone> = {
  ACTIVE: 'green',
  USED: 'gray',
  REVOKED: 'red',
  EXPIRED: 'amber',
}

export default function AdminTeachersPage() {
  const { t } = useTranslation()
  const toast = useToast()
  const [rows, setRows] = useState<TeacherInviteView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [createdCode, setCreatedCode] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setRows(await api.get<TeacherInviteView[]>('/api/admin/teacher-invites'))
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const create = async () => {
    setBusy(true)
    setError(null)
    try {
      const created = await api.post<{ code: string; expiresAt: string }>('/api/admin/teacher-invites')
      setCreatedCode(created.code)
      await load()
      toast.success(t('adminTeachers.created', { code: created.code }))
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const copy = async (code: string) => {
    try {
      await navigator.clipboard.writeText(code)
      toast.success(t('adminTeachers.copied'))
    } catch {
      // 剪贴板不可用（非 https / 权限拒绝）：把码露在列表里，让管理员手动选。
      toast.warning(t('adminTeachers.copyFailed'))
    }
  }

  const revoke = async (code: string) => {
    setError(null)
    try {
      await api.post(`/api/admin/teacher-invites/${encodeURIComponent(code)}/revoke`)
      await load()
      toast.success(t('adminTeachers.revoked'))
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const columns: Column<TeacherInviteView>[] = [
    {
      key: 'code',
      title: t('adminTeachers.colCode'),
      value: (row) => row.code,
      render: (row) => <b className="mono">{row.code}</b>,
    },
    {
      key: 'status',
      title: t('adminTeachers.colStatus'),
      value: (row) => row.status,
      render: (row) => <Tag tone={STATUS_TONE[row.status]}>{t(`adminTeachers.status.${row.status}`)}</Tag>,
    },
    {
      key: 'expiresAt',
      title: t('adminTeachers.colExpires'),
      value: (row) => row.expiresAt,
      render: (row) => <span className="small">{row.expiresAt.slice(0, 10)}</span>,
    },
    {
      key: 'usedBy',
      title: t('adminTeachers.colUsedBy'),
      value: (row) => row.usedByEmail ?? '—',
      render: (row) => <span className="small muted">{row.usedByEmail ?? '—'}</span>,
    },
    {
      key: 'actions',
      title: t('adminTeachers.colActions'),
      value: () => '',
      render: (row) => (
        <div className="row" style={{ gap: 8 }}>
          <Button size="small" disabled={row.status !== 'ACTIVE'} onClick={() => void copy(row.code)}>
            {t('adminTeachers.copy')}
          </Button>
          <Button size="small" disabled={row.status !== 'ACTIVE'} onClick={() => void revoke(row.code)}>
            {t('adminTeachers.revoke')}
          </Button>
        </div>
      ),
    },
  ]

  return (
    <>
      <PageHeader
        title={t('adminTeachers.title')}
        sub={t('adminTeachers.sub')}
        actions={(
          <Button variant="primary" disabled={busy} onClick={() => void create()}>
            {busy ? t('adminTeachers.creating') : t('adminTeachers.create')}
          </Button>
        )}
      />

      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}
      {createdCode && (
        <InlineNote tone="success" style={{ marginBottom: 12 }}>
          {t('adminTeachers.created', { code: createdCode })}
        </InlineNote>
      )}

      <div className="card">
        <DataTable
          rows={rows}
          columns={columns}
          rowKey={(row) => row.code}
          loading={loading}
          pageSize={20}
          empty={<InlineNote tone="warning">{t('adminTeachers.empty')}</InlineNote>}
        />
      </div>

      <div className="card" style={{ marginTop: 16 }}>
        <h2>{t('adminTeachers.rulesTitle')}</h2>
        <ul className="small muted" style={{ margin: '8px 0 0 18px', lineHeight: 1.9 }}>
          <li>{t('adminTeachers.ruleTeacherOnly')}</li>
          <li>{t('adminTeachers.ruleSelfOnly')}</li>
          <li>{t('adminTeachers.ruleOneShot')}</li>
          <li>{t('adminTeachers.ruleAudit')}</li>
          <li>{t('adminTeachers.ruleNoDemote')}</li>
        </ul>
        <InlineNote tone="warning" style={{ marginTop: 12 }}>{t('adminTeachers.nextVersion')}</InlineNote>
      </div>
    </>
  )
}
