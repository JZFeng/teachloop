import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../api/auth'
import { api } from '../api/client'
import type { ClassView } from '../api/types'
import { DataTable } from '../components/DataTable'
import type { Column } from '../components/DataTable'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import { useListState } from '../components/useListState'
import {
  errorMessage,
  PageSizePicker,
  Tag,
  TeacherPage,
  useApiData,
} from './common'

/**
 * T040–T043 + 学生加入班级。
 * 教师：建班、看邀请码与成员数、轮换邀请码、查看成员；
 * 学生：看已加入的班、凭邀请码加入。
 */
export default function ClassesPage() {
  const { t } = useTranslation('translation')
  const { me } = useAuth()
  const isStudent = me?.role === 'STUDENT'
  const { sort, page, pageSize, setSort, setPage } = useListState(
    { key: 'name', dir: 'asc' },
    20,
  )

  const { data, loading, error, reload } = useApiData<ClassView[]>(
    () => api.get<ClassView[]>('/api/classes'),
    [],
  )

  const [name, setName] = useState('')
  const [inviteCode, setInviteCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [membersClassId, setMembersClassId] = useState<string | null>(null)
  const [members, setMembers] = useState<string[] | null>(null)
  const [membersError, setMembersError] = useState<string | null>(null)

  const classes = data ?? []
  const selectedClass = classes.find((c) => c.id === membersClassId) ?? null

  const createClass = async () => {
    if (!name.trim()) {
      setActionError(t('teacher.classes.nameRequired'))
      return
    }
    setBusy(true)
    setActionError(null)
    try {
      await api.post<ClassView>('/api/classes', { name: name.trim() })
      setName('')
      reload()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const joinClass = async () => {
    if (!inviteCode.trim()) {
      setActionError(t('teacher.classes.inviteRequired'))
      return
    }
    setBusy(true)
    setActionError(null)
    try {
      await api.post<ClassView>('/api/classes/join', { inviteCode: inviteCode.trim() })
      setInviteCode('')
      reload()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const regenerateInvite = async (classId: string) => {
    setBusy(true)
    setActionError(null)
    try {
      await api.post<ClassView>(`/api/classes/${classId}/invite/regenerate`)
      reload()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const showMembers = async (classId: string) => {
    if (membersClassId === classId) {
      setMembersClassId(null)
      setMembers(null)
      return
    }
    setMembersClassId(classId)
    setMembers(null)
    setMembersError(null)
    try {
      const ids = await api.get<string[]>(`/api/classes/${classId}/members`)
      setMembers(ids)
    } catch (err) {
      setMembersError(errorMessage(err))
    }
  }

  const actionColumn: Column<ClassView> = {
    key: 'actions',
    title: t('teacher.common.actions'),
    value: () => '',
    render: (row) => (
      <div className="row" style={{ gap: 6 }} onClick={(e) => e.stopPropagation()}>
        <button type="button" className="btn sm" disabled={busy} onClick={() => void showMembers(row.id)}>
          {membersClassId === row.id
            ? t('teacher.classes.hideMembers')
            : t('teacher.classes.members')}
        </button>
        <button
          type="button"
          className="btn sm"
          disabled={busy}
          onClick={() => void regenerateInvite(row.id)}
        >
          {t('teacher.classes.regenerate')}
        </button>
      </div>
    ),
  }

  const teacherColumns: Column<ClassView>[] = [
    {
      key: 'name',
      title: t('teacher.classes.colName'),
      value: (row) => row.name,
      sortable: true,
      render: (row) => <b>{row.name}</b>,
    },
    {
      key: 'memberCount',
      title: t('teacher.classes.colMembers'),
      value: (row) => row.memberCount,
      sortable: true,
      render: (row) => <span className="mono">{row.memberCount}</span>,
    },
    {
      key: 'inviteCode',
      title: t('teacher.classes.colInvite'),
      value: (row) => row.inviteCode,
      sortable: true,
      render: (row) => <span className="mono">{row.inviteCode}</span>,
    },
    actionColumn,
  ]

  const studentColumns: Column<ClassView>[] = [
    {
      key: 'name',
      title: t('teacher.classes.colName'),
      value: (row) => row.name,
      sortable: true,
      render: (row) => <b>{row.name}</b>,
    },
    {
      key: 'memberCount',
      title: t('teacher.classes.colMembers'),
      value: (row) => row.memberCount,
      sortable: true,
      render: (row) => <span className="mono">{row.memberCount}</span>,
    },
    {
      key: 'inviteCode',
      title: t('teacher.classes.colInvite'),
      value: (row) => row.inviteCode,
      sortable: true,
      render: (row) => <span className="mono">{row.inviteCode}</span>,
    },
    {
      key: 'state',
      title: t('teacher.classes.colState'),
      value: () => 'JOINED',
      render: () => <Tag tone="green">{t('teacher.classes.joined')}</Tag>,
    },
  ]

  return (
    <TeacherPage>
      <PageHeader
        title={t('teacher.classes.title')}
        sub={isStudent ? t('teacher.classes.studentSub') : t('teacher.classes.sub')}
      />

      {loading && <Loading label={t('teacher.common.loading')} />}
      {error && <ErrorState error={error} onRetry={reload} />}

      {data && (
        <>
          {isStudent ? (
            <div className="card">
              <h2>{t('teacher.classes.joinTitle')}</h2>
              <div className="inline-form">
                <label className="f" style={{ flex: '1 1 240px' }}>
                  <b>{t('teacher.classes.inviteCode')}</b>
                  <input
                    value={inviteCode}
                    onChange={(e) => setInviteCode(e.target.value)}
                    placeholder={t('teacher.classes.invitePlaceholder')}
                  />
                </label>
                <button type="button" className="btn pri" disabled={busy} onClick={() => void joinClass()}>
                  {busy ? t('teacher.common.saving') : t('teacher.classes.join')}
                </button>
              </div>
              {actionError && (
                <div className="hint" style={{ color: 'var(--red)' }}>
                  {actionError}
                </div>
              )}
              <div className="hint">{t('teacher.classes.joinHint')}</div>
            </div>
          ) : (
            <div className="card">
              <h2>{t('teacher.classes.createTitle')}</h2>
              <div className="inline-form">
                <label className="f" style={{ flex: '1 1 240px' }}>
                  <b>{t('teacher.classes.className')}</b>
                  <input
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    placeholder={t('teacher.classes.classNamePlaceholder')}
                  />
                </label>
                <button type="button" className="btn pri" disabled={busy} onClick={() => void createClass()}>
                  {busy ? t('teacher.common.saving') : t('teacher.classes.create')}
                </button>
              </div>
              {actionError && (
                <div className="hint" style={{ color: 'var(--red)' }}>
                  {actionError}
                </div>
              )}
              <div className="hint">{t('teacher.classes.createHint')}</div>
            </div>
          )}

          <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
            <h2 style={{ margin: 0 }}>
              {t('teacher.classes.listTitle')}{' '}
              <small className="muted">{t('teacher.classes.count', { n: classes.length })}</small>
            </h2>
            <PageSizePicker pageSize={pageSize} />
          </div>

          {classes.length === 0 ? (
            <Empty>{isStudent ? t('teacher.classes.studentEmpty') : t('teacher.classes.empty')}</Empty>
          ) : (
            <DataTable
              rows={classes}
              columns={isStudent ? studentColumns : teacherColumns}
              rowKey={(row) => row.id}
              sort={sort}
              page={page}
              pageSize={pageSize}
              onSortChange={setSort}
              onPageChange={setPage}
              empty={t('teacher.common.empty')}
            />
          )}

          {!isStudent && selectedClass && (
            <div className="card">
              <h2>
                {selectedClass.name} <small>{t('teacher.classes.membersTitle')}</small>
              </h2>
              {membersError && <p className="inline-note">{membersError}</p>}
              {members === null && !membersError && <Loading label={t('teacher.common.loading')} />}
              {members !== null && (
                <>
                  <div className="kv">
                    <span>{t('teacher.classes.memberCount')}</span>
                    <span>{members.length}</span>
                  </div>
                  {members.length === 0 ? (
                    <p className="muted">{t('teacher.classes.noMembers')}</p>
                  ) : (
                    <div className="member-ids">
                      {members.map((id) => (
                        <code key={id}>{id}</code>
                      ))}
                    </div>
                  )}
                  <div className="hint">{t('teacher.classes.memberIdHint')}</div>
                </>
              )}
            </div>
          )}

          {!isStudent && (
            <div className="card">
              <h2>{t('teacher.classes.inviteInfoTitle')}</h2>
              <div className="hint">{t('teacher.classes.inviteInfo')}</div>
            </div>
          )}
        </>
      )}
    </TeacherPage>
  )
}
