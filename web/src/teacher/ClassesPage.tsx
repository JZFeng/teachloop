import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../api/auth'
import { api, fmtDateTime } from '../api/client'
import { offeringsForClass, splitClassesByLifecycle } from '../api/types'
import type {
  ClassLeavePreviewView,
  ClassMemberView,
  ClassView,
  OfferingView,
} from '../api/types'
import {
  Button,
  DataTable,
  InlineNote,
  RowActions,
  Tag,
  useConfirm,
  useListState,
  useToast,
} from '../ui'
import type { Column } from '../ui'
import { Empty, ErrorState, Loading, PageHeader } from '../components/States'
import { errorMessage, TeacherPage, useApiData } from './common'

/**
 * T040–T043 + 学生加入班级 + T589（SPEC 012 US-96/97/98）。
 *
 * 教师：建班、看邀请码与成员数、轮换邀请码、查看成员（可含已离开）、移出学生、结课 / 恢复；
 * 学生：看已加入的班（「进行中」在上、「已结束」在下），凭邀请码加入、退出班级、
 *       在本页内展开某个班的作业（进行中 → `/assignments/:id`，已结束 → `/feedback/:id` 只读）。
 *
 * 两条口径写在页面里而不是散在 JSX 中：
 *   1. 分组只认 `archived`，不看「学生还有几份没交」（AC-98.2 / FR-224）；
 *   2. 已结束的班只出现「查看成绩」，不出现「提交 / 去完成 / 进入班级」。
 */
export default function ClassesPage() {
  const { t } = useTranslation('translation')
  const { me } = useAuth()
  const navigate = useNavigate()
  const confirm = useConfirm()
  const toast = useToast()
  const isStudent = me?.role === 'STUDENT'
  const { sort, page, pageSize, setSort, setPage, setPageSize } = useListState(
    { key: 'name', dir: 'asc' },
    10,
  )

  const { data, loading, error, reload } = useApiData<ClassView[]>(
    () => api.get<ClassView[]>('/api/classes'),
    [],
  )

  const [name, setName] = useState('')
  const [inviteCode, setInviteCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  // 老师：成员面板。默认只取 ACTIVE，勾上「含已离开」才带 `?includeLeft=1`（AC-97.2 / FR-216）。
  const [membersClassId, setMembersClassId] = useState<string | null>(null)
  const [members, setMembers] = useState<ClassMemberView[] | null>(null)
  const [membersError, setMembersError] = useState<string | null>(null)
  const [includeLeft, setIncludeLeft] = useState(false)
  const [membersTick, setMembersTick] = useState(0)

  // 学生：展开的班级 + 该班作业。作业列表懒加载一次，展开第二个班不再重复请求。
  const [expandedClassId, setExpandedClassId] = useState<string | null>(null)
  const [offerings, setOfferings] = useState<OfferingView[] | null>(null)
  const [offeringsError, setOfferingsError] = useState<string | null>(null)
  const [offeringsBusy, setOfferingsBusy] = useState(false)

  const classes = data ?? []
  const selectedClass = classes.find((c) => c.id === membersClassId) ?? null
  const expandedClass = classes.find((c) => c.id === expandedClassId) ?? null
  const { active, archived } = splitClassesByLifecycle(classes)
  const expandedOfferings = expandedClass && offerings ? offeringsForClass(offerings, expandedClass.id) : []

  useEffect(() => {
    if (!membersClassId) return
    let alive = true
    setMembers(null)
    setMembersError(null)
    const query = includeLeft ? '?includeLeft=1' : ''
    api
      .get<ClassMemberView[]>(`/api/classes/${membersClassId}/members${query}`)
      .then((rows) => {
        if (alive) setMembers(rows)
      })
      .catch((err: unknown) => {
        if (alive) setMembersError(errorMessage(err))
      })
    return () => {
      alive = false
    }
  }, [membersClassId, includeLeft, membersTick])

  const refreshMembers = () => setMembersTick((n) => n + 1)

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
      // 新加入的班可能已经有作业：缓存作废，展开时重新取一次。
      setOfferings(null)
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

  const toggleMembers = (classId: string) => {
    setMembersClassId((current) => (current === classId ? null : classId))
    // 换班时先清空：否则新班标题下会闪一帧上一个班的成员。
    setMembers(null)
    setMembersError(null)
  }

  /** 学生：展开 / 收起某个班的作业（不新增路由，列表在本页内展开）。 */
  const toggleAssignments = (classId: string) => {
    const next = expandedClassId === classId ? null : classId
    setExpandedClassId(next)
    if (!next || offerings || offeringsBusy) return
    setOfferingsBusy(true)
    setOfferingsError(null)
    api
      .get<OfferingView[]>('/api/offerings')
      .then((rows) => setOfferings(rows))
      .catch((err: unknown) => setOfferingsError(errorMessage(err)))
      .finally(() => setOfferingsBusy(false))
  }

  /** AC-96.1 / AC-96.5：先取未交份数再问，未确认前不写任何东西。 */
  const leaveClass = async (row: ClassView) => {
    setBusy(true)
    setActionError(null)
    try {
      const preview = await api.get<ClassLeavePreviewView>(`/api/classes/${row.id}/leave-preview`)
      const ok = await confirm({
        title: t('class.lifecycle.leaveTitle', { name: row.name }),
        content: (
          <div className="small">
            {preview.pendingAssignments > 0 && (
              <p style={{ margin: '0 0 8px' }}>
                {t('class.lifecycle.leavePending', { n: preview.pendingAssignments })}
              </p>
            )}
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              <li>{t('class.lifecycle.leaveEffect1')}</li>
              <li>{t('class.lifecycle.leaveEffect2')}</li>
              <li>{t('class.lifecycle.leaveEffect3')}</li>
            </ul>
          </div>
        ),
        okText: t('class.lifecycle.leaveOk'),
        cancelText: t('common.cancel'),
        danger: true,
      })
      if (!ok) return
      await api.del<{ classId: string }>(`/api/classes/${row.id}/membership`)
      toast.success(t('class.lifecycle.leaveDone', { name: row.name }))
      setExpandedClassId(null)
      reload()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  /** AC-98.1：结课先说清四条后果（只读 / 邀请码失效 / 不能布置 / 可恢复）。 */
  const archiveClass = async (row: ClassView) => {
    const ok = await confirm({
      title: t('class.lifecycle.archiveTitle', { name: row.name }),
      content: (
        <ul style={{ margin: 0, paddingLeft: 18 }} className="small">
          <li>{t('class.lifecycle.archiveEffect1')}</li>
          <li>{t('class.lifecycle.archiveEffect2')}</li>
          <li>{t('class.lifecycle.archiveEffect3')}</li>
          <li>{t('class.lifecycle.archiveEffect4')}</li>
        </ul>
      ),
      okText: t('class.lifecycle.archiveOk'),
      cancelText: t('common.cancel'),
    })
    if (!ok) return
    setBusy(true)
    setActionError(null)
    try {
      await api.post<ClassView>(`/api/classes/${row.id}/archive`)
      toast.success(t('class.lifecycle.archiveDone', { name: row.name }))
      reload()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  /** AC-98.5：恢复——邀请码重新有效、可再布置与提交，历史数据不变。 */
  const restoreClass = async (row: ClassView) => {
    const ok = await confirm({
      title: t('class.lifecycle.restoreTitle', { name: row.name }),
      content: (
        <ul style={{ margin: 0, paddingLeft: 18 }} className="small">
          <li>{t('class.lifecycle.restoreEffect1')}</li>
          <li>{t('class.lifecycle.restoreEffect2')}</li>
          <li>{t('class.lifecycle.restoreEffect3')}</li>
        </ul>
      ),
      okText: t('class.lifecycle.restoreOk'),
      cancelText: t('common.cancel'),
    })
    if (!ok) return
    setBusy(true)
    setActionError(null)
    try {
      await api.post<ClassView>(`/api/classes/${row.id}/restore`)
      toast.success(t('class.lifecycle.restoreDone', { name: row.name }))
      reload()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  /** AC-97.1 / AC-97.2：移出只写状态，历史提交与成绩保留；成功刷新成员表与班级列表（人数变了）。 */
  const removeMember = async (row: ClassView, member: ClassMemberView) => {
    const displayName = member.displayName || t('teacher.classes.unnamedStudent')
    const ok = await confirm({
      title: t('class.lifecycle.removeTitle', { name: displayName }),
      content: (
        <ul style={{ margin: 0, paddingLeft: 18 }} className="small">
          <li>{t('class.lifecycle.removeEffect1')}</li>
          <li>{t('class.lifecycle.removeEffect2')}</li>
          <li>{t('class.lifecycle.removeEffect3')}</li>
        </ul>
      ),
      okText: t('class.lifecycle.removeOk'),
      cancelText: t('common.cancel'),
      danger: true,
    })
    if (!ok) return
    setBusy(true)
    setActionError(null)
    try {
      await api.del(`/api/classes/${row.id}/members/${member.studentId}`)
      toast.success(t('class.lifecycle.removeDone', { name: displayName }))
      refreshMembers()
      reload()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  // T444：成员列表也走统一表格（只读，不加排序，分页由组件自己管）
  const memberColumns: Column<ClassMemberView>[] = [
    {
      key: 'name',
      title: t('teacher.classes.colMemberName'),
      value: (member) => member.displayName || t('teacher.classes.unnamedStudent'),
      render: (member) => (
        <>
          <b>{member.displayName || t('teacher.classes.unnamedStudent')}</b>
          <div className="small muted mono">{member.studentId}</div>
        </>
      ),
    },
    {
      key: 'submissions',
      title: t('teacher.classes.colMemberSubmissions'),
      value: (member) => member.submissionCount,
    },
    {
      key: 'state',
      title: t('class.lifecycle.colMemberStatus'),
      value: (member) => member.status,
      render: (member) =>
        member.status === 'LEFT' ? (
          <>
            <Tag tone="gray">{t('class.lifecycle.memberLeft')}</Tag>
            <div className="small muted">{t('class.lifecycle.leftAt', { time: fmtDateTime(member.leftAt) })}</div>
          </>
        ) : (
          <Tag tone="green">{t('class.lifecycle.memberActive')}</Tag>
        ),
    },
    {
      key: 'actions',
      title: t('teacher.common.actions'),
      width: '100px',
      value: () => '',
      // 已离开的学生没有「移出」——他要走的那一步已经走完了（AC-97.2）。
      render: (member) =>
        member.status === 'LEFT' ? (
          <span className="small muted">—</span>
        ) : (
          <Button
            size="small"
            variant="danger"
            disabled={busy}
            onClick={() => {
              if (selectedClass) void removeMember(selectedClass, member)
            }}
          >
            {t('class.lifecycle.removeMember')}
          </Button>
        ),
    },
  ]

  const actionColumn: Column<ClassView> = {
    key: 'actions',
    title: t('teacher.common.actions'),
    width: '120px',
    value: () => '',
    // 成员是「行内展开」的主操作，结课 / 恢复与轮换邀请码是低频动作 → 收进「⋯」（AC-25.1）。
    // 结课班级的邀请码已经失效，轮换它没有意义：禁用并写出原因，不只靠 title（AC-25.5）。
    render: (row) => (
      <RowActions
        main={{
          key: 'members',
          label: membersClassId === row.id
            ? t('teacher.classes.hideMembers')
            : t('teacher.classes.members'),
          disabled: busy,
          onClick: () => toggleMembers(row.id),
        }}
        items={[
          row.archived
            ? {
              key: 'restore',
              label: t('class.lifecycle.restoreAction'),
              disabled: busy,
              onClick: () => void restoreClass(row),
            }
            : {
              key: 'archive',
              label: t('class.lifecycle.archiveAction'),
              disabled: busy,
              onClick: () => void archiveClass(row),
            },
          {
            key: 'regenerate',
            label: t('teacher.classes.regenerate'),
            disabled: busy || row.archived,
            reason: row.archived ? t('class.lifecycle.rotateDisabledArchived') : undefined,
            onClick: () => void regenerateInvite(row.id),
          },
        ]}
      />
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
    {
      key: 'state',
      title: t('teacher.classes.colState'),
      value: (row) => (row.archived ? 'ARCHIVED' : 'ACTIVE'),
      render: (row) => (
        <Tag tone={row.archived ? 'gray' : 'green'}>
          {t(row.archived ? 'class.lifecycle.archivedSection' : 'class.lifecycle.activeSection')}
        </Tag>
      ),
    },
    actionColumn,
  ]

  // 学生：班级行照原型画（班名 + 人数/状态 + 动作），不是表格——一个分区常常只有两三条，
  // 表格自带的分页条比内容还重。整行可点＝在本页内展开该班作业；「退出班级」自己拦住冒泡。
  const studentClassRow = (row: ClassView, isArchived: boolean) => {
    const expanded = expandedClassId === row.id
    return (
      <div
        className="feed"
        key={row.id}
        role="button"
        tabIndex={0}
        onClick={() => toggleAssignments(row.id)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault()
            toggleAssignments(row.id)
          }
        }}
      >
        <div className="top">
          <div style={{ minWidth: 0 }}>
            <b>{row.name}</b>
            <div className="meta">
              {isArchived
                ? t('class.lifecycle.metaArchived', { n: row.memberCount })
                : t('class.lifecycle.metaActive', { n: row.memberCount })}
            </div>
          </div>
          <div className="row" onClick={(event) => event.stopPropagation()}>
            {isArchived && <Tag tone="gray">{t('class.lifecycle.tagArchived')}</Tag>}
            <Button size="small" disabled={busy} onClick={() => toggleAssignments(row.id)}>
              {expanded
                ? t('class.lifecycle.collapseClass')
                : t(isArchived ? 'class.lifecycle.viewScores' : 'class.lifecycle.enterClass')}
            </Button>
            {!isArchived && (
              <Button size="small" variant="danger" disabled={busy} onClick={() => void leaveClass(row)}>
                {t('class.lifecycle.leaveClass')}
              </Button>
            )}
          </div>
        </div>
      </div>
    )
  }

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
                <Button variant="primary" disabled={busy} onClick={() => void joinClass()}>
                  {busy ? t('teacher.common.saving') : t('teacher.classes.join')}
                </Button>
              </div>
              {actionError && (
                <InlineNote tone="error" style={{ marginTop: 6 }}>
                  {actionError}
                </InlineNote>
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
                <Button variant="primary" disabled={busy} onClick={() => void createClass()}>
                  {busy ? t('teacher.common.saving') : t('teacher.classes.create')}
                </Button>
              </div>
              {actionError && (
                <InlineNote tone="error" style={{ marginTop: 6 }}>
                  {actionError}
                </InlineNote>
              )}
              <div className="hint">{t('teacher.classes.createHint')}</div>
            </div>
          )}

          {isStudent ? (
            <>
              <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
                <h2 style={{ margin: 0 }}>
                  {t('class.lifecycle.activeSection')}{' '}
                  <small className="muted">{t('class.lifecycle.count', { n: active.length })}</small>
                </h2>
              </div>
              {active.length === 0 ? (
                <p className="small muted">{t('class.lifecycle.activeEmpty')}</p>
              ) : (
                active.map((row) => studentClassRow(row, false))
              )}

              <div className="row" style={{ justifyContent: 'space-between', margin: '20px 0 8px' }}>
                <h2 style={{ margin: 0 }}>
                  {t('class.lifecycle.archivedSection')}{' '}
                  <small className="muted">{t('class.lifecycle.count', { n: archived.length })}</small>
                </h2>
              </div>
              {archived.length === 0 ? (
                <p className="small muted">{t('class.lifecycle.archivedEmpty')}</p>
              ) : (
                archived.map((row) => studentClassRow(row, true))
              )}

              {expandedClass && (
                <div className="card">
                  <h2>
                    {t('class.lifecycle.assignmentsTitle', { name: expandedClass.name })}
                    {expandedClass.archived && <small>{t('class.lifecycle.archivedReadOnly')}</small>}
                  </h2>
                  {offeringsBusy && <Loading label={t('teacher.common.loading')} />}
                  {offeringsError && (
                    <InlineNote tone="error">
                      {t('class.lifecycle.assignmentsError', { message: offeringsError })}
                    </InlineNote>
                  )}
                  {!offeringsBusy && !offeringsError && expandedOfferings.length === 0 && (
                    <p className="small muted">{t('class.lifecycle.assignmentsEmpty')}</p>
                  )}
                  {expandedOfferings.map((offering) => {
                    // 进行中：去作答（考试走考试路由）；已结束：只读看成绩（AC-98.2，无提交入口）。
                    const go = () => {
                      if (expandedClass.archived) {
                        navigate(`/feedback/${offering.id}`)
                      } else {
                        navigate(offering.kind === 'EXAM' ? `/exams/${offering.id}` : `/assignments/${offering.id}`)
                      }
                    }
                    return (
                      <div
                        className="feed"
                        key={offering.id}
                        role="button"
                        tabIndex={0}
                        onClick={go}
                        onKeyDown={(event) => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault()
                            go()
                          }
                        }}
                      >
                        <div className="top">
                          <div style={{ minWidth: 0 }}>
                            <b>{offering.paperTitle}</b>
                            <div className="meta">
                              {offering.dueAt
                                ? t('class.lifecycle.dueAt', { time: fmtDateTime(offering.dueAt) })
                                : t('class.lifecycle.noDue')}
                            </div>
                          </div>
                        </div>
                      </div>
                    )
                  })}
                </div>
              )}
            </>
          ) : (
            <>
              <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
                <h2 style={{ margin: 0 }}>
                  {t('teacher.classes.listTitle')}{' '}
                  <small className="muted">{t('teacher.classes.count', { n: classes.length })}</small>
                </h2>
              </div>

              {classes.length === 0 ? (
                <Empty>{t('teacher.classes.empty')}</Empty>
              ) : (
                <DataTable
                  rows={classes}
                  columns={teacherColumns}
                  rowKey={(row) => row.id}
                  sort={sort}
                  page={page}
                  pageSize={pageSize}
                  onSortChange={setSort}
                  onPageChange={setPage}
                  onPageSizeChange={setPageSize}
                  empty={t('teacher.common.empty')}
                />
              )}

              {selectedClass && (
                <div className="card">
                  <div className="row" style={{ justifyContent: 'space-between' }}>
                    <h2 style={{ margin: 0 }}>
                      {selectedClass.name} <small>{t('teacher.classes.membersTitle')}</small>
                    </h2>
                    <label className="row" style={{ gap: 6 }}>
                      <input
                        type="checkbox"
                        checked={includeLeft}
                        onChange={(e) => setIncludeLeft(e.target.checked)}
                      />
                      <span className="small">{t('class.lifecycle.includeLeft')}</span>
                    </label>
                  </div>
                  {selectedClass.archived && (
                    <InlineNote tone="info">{t('class.lifecycle.membersHintArchived')}</InlineNote>
                  )}
                  {membersError && <InlineNote tone="error">{membersError}</InlineNote>}
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
                        <DataTable
                          rows={members}
                          columns={memberColumns}
                          rowKey={(member) => member.studentId}
                        />
                      )}
                    </>
                  )}
                </div>
              )}

              <div className="card">
                <h2>{t('teacher.classes.inviteInfoTitle')}</h2>
                <div className="hint">{t('teacher.classes.inviteInfo')}</div>
              </div>
            </>
          )}
        </>
      )}
    </TeacherPage>
  )
}
