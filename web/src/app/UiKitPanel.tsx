import { useState } from 'react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import {
  AuditOutlined,
  BarChartOutlined,
  BellOutlined,
  BookOutlined,
  Button,
  CheckCircleOutlined,
  CommentOutlined,
  CONTRAST_NOTES,
  DataTable,
  Empty,
  ErrorState,
  Field,
  FileTextOutlined,
  HighlightOutlined,
  SyncOutlined,
  HomeOutlined,
  InlineNote,
  Loading,
  LogoutOutlined,
  MoonOutlined,
  SettingOutlined,
  StatCard,
  SunOutlined,
  Tag,
  TeamOutlined,
  UserOutlined,
} from '../ui'
import type { Column } from '../ui'

interface DemoRow {
  id: string
  name: string
  state: string
  updated: string
}

const BASE_ROWS: DemoRow[] = [
  { id: '1', name: 'demoRow1', state: 'grading', updated: '2026-10-20 09:24' },
  { id: '2', name: 'demoRow2', state: 'done', updated: '2026-10-19 21:10' },
  { id: '3', name: 'demoRow3', state: 'failed', updated: '2026-10-18 16:03' },
]

/** 24 行只为让排序、翻页、每页条数真的可点（T441 验收要求能操作，不是静态摆拍）。 */
const DEMO_ROWS: DemoRow[] = Array.from({ length: 24 }, (_, index) => ({
  ...BASE_ROWS[index % BASE_ROWS.length],
  id: String(index + 1),
}))

/** 图标清单：与 `ui/icons.tsx` 的导出一一对应，文案复用导航/通用 key。 */
const ICON_DEMO: { key: string; icon: ReactNode }[] = [
  { key: 'nav.home', icon: <HomeOutlined /> },
  { key: 'nav.classes', icon: <TeamOutlined /> },
  { key: 'nav.assignments', icon: <BookOutlined /> },
  { key: 'nav.papers', icon: <FileTextOutlined /> },
  { key: 'nav.grading', icon: <HighlightOutlined /> },
  { key: 'nav.review', icon: <AuditOutlined /> },
  { key: 'nav.reports', icon: <BarChartOutlined /> },
  { key: 'nav.aiSettings', icon: <SettingOutlined /> },
  { key: 'student.feedbackTitle', icon: <CommentOutlined /> },
  { key: 'nav.notifications', icon: <BellOutlined /> },
  { key: 'nav.profile', icon: <UserOutlined /> },
  { key: 'common.logout', icon: <LogoutOutlined /> },
  { key: 'ui.theme.light', icon: <SunOutlined /> },
  { key: 'ui.theme.dark', icon: <MoonOutlined /> },
]

/**
 * UI 标准面板：一页看全组件的每种状态。
 *
 * 它不再单独占一个路由/导航入口，而是收在资料页「偏好设置」里按需展开——
 * 组件标准是给改样式的人对照的，不是产品功能。
 * 规矩：这一块不许写死颜色/字号，只能引用组件与令牌——它自己就是被检查的对象。
 */
export function UiKitPanel() {
  const { t } = useTranslation('translation')
  const [demoSort, setDemoSort] = useState<{ key: string; dir: 'asc' | 'desc' }>({ key: 'updated', dir: 'desc' })
  const [demoPage, setDemoPage] = useState(1)
  const [demoPageSize, setDemoPageSize] = useState(10)
  const [demoSelected, setDemoSelected] = useState<string[]>([])

  const columns: Column<DemoRow>[] = [
    { key: 'name', title: t('ui.kit.colName'), value: (row) => t(`ui.kit.${row.name}`), sortable: true },
    {
      key: 'state',
      title: t('ui.kit.colState'),
      value: (row) => row.state,
      render: (row) => (
        <Tag tone={row.state === 'done' ? 'green' : row.state === 'failed' ? 'red' : 'amber'}>
          {row.state}
        </Tag>
      ),
    },
    { key: 'updated', title: t('ui.kit.colUpdated'), value: (row) => row.updated, sortable: true },
  ]

  return (
    <div className="tl-kit-panel">
      <Section title={t('ui.kit.sectionButton')}>
        <StateRow label={t('ui.kit.stateDefault')}>
          <Button variant="primary">{t('ui.kit.variantPrimary')}</Button>
          <Button>{t('ui.kit.variantDefault')}</Button>
          <Button variant="text">{t('ui.kit.variantText')}</Button>
          <Button variant="danger">{t('ui.kit.variantDanger')}</Button>
        </StateRow>
        <StateRow label={t('ui.kit.stateHover')}>
          <span className="tl-demo-note">{t('ui.kit.stateHover')} · hover</span>
        </StateRow>
        <StateRow label={t('ui.kit.stateFocus')}>
          <span className="tl-demo-focus">
            <Button variant="primary">{t('ui.kit.stateFocus')}</Button>
          </span>
        </StateRow>
        <StateRow label={t('ui.kit.stateDisabled')}>
          <Button disabled>{t('ui.kit.variantDefault')}</Button>
          <Button variant="primary" disabled>{t('ui.kit.variantPrimary')}</Button>
        </StateRow>
        <StateRow label={t('ui.kit.stateLoading')}>
          <Button variant="primary" busy>{t('ui.kit.variantPrimary')}</Button>
          <Button busy>{t('ui.kit.variantDefault')}</Button>
        </StateRow>
      </Section>

      <Section title={t('ui.kit.sectionField')}>
        <div className="tl-kit-grid">
          <Field label={t('ui.kit.variantDefault')} htmlFor="kit-input">
            <input id="kit-input" defaultValue="teacher@teachloop.local" />
          </Field>
          <Field label={t('ui.kit.variantPrimary')} required hint={t('ui.kit.note')}>
            <input defaultValue="" placeholder="…" />
          </Field>
          <Field label={t('ui.kit.sectionState')} error={t('ui.kit.demoError')}>
            <input defaultValue="abc" />
          </Field>
          <Field label={t('ui.kit.stateDisabled')}>
            <input defaultValue="—" disabled />
          </Field>
        </div>
      </Section>

      <Section title={t('ui.kit.sectionStat')}>
        <div className="tl-kit-stats">
          <StatCard value={12} label={t('ui.kit.sectionStat')} sub={t('ui.kit.note')} />
          <StatCard value={3} label={t('ui.kit.sectionTag')} sub={t('ui.kit.note')} onClick={() => {}} />
          <StatCard value="—" label={t('ui.kit.demoEmpty')} />
        </div>
      </Section>

      <Section title={t('ui.kit.sectionTag')}>
        <div className="row">
          <Tag tone="green">done</Tag>
          <Tag tone="amber">grading</Tag>
          <Tag tone="red">failed</Tag>
          <Tag tone="blue">queued</Tag>
          <Tag>draft</Tag>
        </div>
        {/* 行内状态 chip：与上面的 Tag 同一套语义，只是更轻，用在表格单元格与词库里。 */}
        <div className="row" style={{ marginTop: 8 }}>
          <span className="chip">NEW</span>
          <span className="chip info">{t('ui.kit.chipRunning')}</span>
          <span className="chip warn">{t('ui.kit.chipWarn')}</span>
          <span className="chip err">{t('ui.kit.chipErr')}</span>
          <span className="chip ok">{t('ui.kit.chipOk')}</span>
        </div>
      </Section>

      <Section title={t('ui.kit.sectionNote')}>
        <div className="tl-kit-notes">
          <InlineNote tone="error">{t('ui.kit.demoError')}</InlineNote>
          <InlineNote tone="warning">{t('ui.kit.demoWarn')}</InlineNote>
          <InlineNote tone="success">{t('ui.kit.demoOk')}</InlineNote>
          <InlineNote>{t('ui.kit.demoInfo')}</InlineNote>
        </div>
      </Section>

      <Section title={t('ui.kit.sectionTable')}>
        <p className="tl-demo-note">{t('ui.kit.tableHint')}</p>
        <DataTable
          rows={DEMO_ROWS}
          columns={columns}
          rowKey={(row) => row.id}
          sort={demoSort}
          page={demoPage}
          pageSize={demoPageSize}
          onSortChange={(next) => { setDemoSort(next); setDemoPage(1) }}
          onPageChange={setDemoPage}
          onPageSizeChange={(size) => { setDemoPageSize(size); setDemoPage(1) }}
          selection={{ selectedKeys: demoSelected, onChange: setDemoSelected }}
          batchBar={<span>{t('ui.kit.tableSelected', { count: demoSelected.length })}</span>}
        />
      </Section>

      <Section title={t('ui.kit.sectionState')}>
        <div className="tl-kit-states">
          <Loading />
          <ErrorState error={new Error(t('ui.kit.demoError'))} onRetry={() => {}} />
          <Empty>{t('ui.kit.demoEmpty')}</Empty>
        </div>
      </Section>

      <Section title={t('ui.kit.sectionBrand')}>
        <div className="tl-brand-kit">
          <div className="tl-brand-row">
            <span className="tl-brand-row-label">{t('ui.kit.brandFeatures')}</span>
            <span className="tl-brand-chip" data-feature="create">
              <span className="tl-brand-chip-ico" aria-hidden="true"><FileTextOutlined /></span>
              Create
            </span>
            <span className="tl-brand-chip" data-feature="assess">
              <span className="tl-brand-chip-ico" aria-hidden="true"><CheckCircleOutlined /></span>
              Assess
            </span>
            <span className="tl-brand-chip" data-feature="practice">
              <span className="tl-brand-chip-ico" aria-hidden="true"><SyncOutlined /></span>
              Practice
            </span>
            <span className="tl-brand-chip" data-feature="insights">
              <span className="tl-brand-chip-ico" aria-hidden="true"><BarChartOutlined /></span>
              Insights
            </span>
          </div>
          <div className="tl-brand-row">
            <span className="tl-brand-row-label">{t('ui.kit.brandSpaces')}</span>
            <span className="tl-brand-chip" data-feature="home">
              <span className="tl-brand-chip-ico" aria-hidden="true"><HomeOutlined /></span>
              Home
            </span>
            <span className="tl-brand-chip" data-feature="classes">
              <span className="tl-brand-chip-ico" aria-hidden="true"><TeamOutlined /></span>
              Classes
            </span>
          </div>
          <div className="tl-brand-row">
            <span className="tl-brand-row-label">{t('ui.kit.brandStatus')}</span>
            <Tag tone="green">{t('ui.kit.statusPass')}</Tag>
            <Tag tone="amber">{t('ui.kit.statusPartial')}</Tag>
            <Tag tone="red">{t('ui.kit.statusFail')}</Tag>
          </div>
          <div className="tl-brand-row">
            <span className="tl-brand-row-label">{t('ui.kit.brandRaw')}</span>
            <span className="tl-brand-swatch" data-brand="navy"><i />--brand-navy</span>
            <span className="tl-brand-swatch" data-brand="azure"><i />--brand-azure</span>
            <span className="tl-brand-swatch" data-brand="teal"><i />--brand-teal</span>
            <span className="tl-brand-swatch" data-brand="yellow"><i />--brand-yellow</span>
            <span className="tl-brand-swatch" data-brand="rose"><i />--brand-rose</span>
          </div>
          <p className="small muted">{t('ui.kit.brandNote')}</p>
          <p className="small muted">{t('ui.kit.navToneNote')}</p>
          <div className="tl-brand-ratio">
            <span>{t('ui.kit.brandContrast')}</span>
            <span className="mono small">--accent {CONTRAST_NOTES.light.colorPrimary} / {CONTRAST_NOTES.dark.colorPrimary}</span>
            <span className="mono small">--line-strong {CONTRAST_NOTES.light.colorBorder} / {CONTRAST_NOTES.dark.colorBorder}</span>
            <span className="mono small">--accent-text {CONTRAST_NOTES.light.colorLink} / {CONTRAST_NOTES.dark.colorLink}</span>
          </div>
        </div>
      </Section>

      <Section title={t('ui.kit.sectionIcon')}>
        <div className="tl-kit-icons">
          {ICON_DEMO.map((item) => (
            <div className="tl-kit-icon" key={item.key}>
              <span className="tl-kit-icon-glyph" aria-hidden="true">{item.icon}</span>
              <span>{t(item.key)}</span>
            </div>
          ))}
        </div>
        <p className="small muted">{t('ui.kit.noteIcon')}</p>
      </Section>

      <Section title={t('ui.kit.sectionType')}>
        <div className="tl-kit-type">
          <h1>H1 · 24 / 1.4</h1>
          <h2>H2 · 20 / 1.4</h2>
          <h3>H3 · 16 / 1.5</h3>
          <p>{t('ui.kit.typeSample')}</p>
          <p className="small muted">辅助文字 13 / 1.55</p>
        </div>
      </Section>

      <Section title={t('ui.kit.sectionTokens')}>
        <div className="tl-kit-swatches">
          <Swatch label="--accent" />
          <Swatch label="--text" />
          <Swatch label="--muted" />
          <Swatch label="--line" />
          <Swatch label="--accent-text" />
          <Swatch label="--create" />
          <Swatch label="--assess" />
          <Swatch label="--practice" />
          <Swatch label="--insights" />
          <Swatch label="--space-home" />
          <Swatch label="--space-classes" />
          <Swatch label="--green" />
          <Swatch label="--amber" />
          <Swatch label="--red" />
        </div>
        <p className="small muted">{t('ui.kit.note')}</p>
      </Section>
    </div>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="tl-kit-section">
      <h3 className="tl-kit-section-title">{title}</h3>
      {children}
    </section>
  )
}

function StateRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="tl-kit-row">
      <span className="tl-kit-row-label">{label}</span>
      <div className="row">{children}</div>
    </div>
  )
}

function Swatch({ label }: { label: string }) {
  return (
    <div className="tl-kit-swatch">
      <span className="tl-kit-swatch-color" style={{ background: `var(${label})` }} />
      <code className="mono small">{label}</code>
    </div>
  )
}
