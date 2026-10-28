import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../api/client'
import { Alert, Button, Field, InlineNote, Input, Password, Radio } from '../ui'
import type { NoteTone } from '../ui'
import { channelReadiness } from './registrationGate'
import type { ChannelReadiness } from './registrationGate'
import { buildEmailUpdate, toEmailForm, validateEmailForm } from './emailChannelForm'
import type { EmailChannelRow, EmailForm, EmailFormIssue, SmtpSecurity } from './emailChannelForm'

/**
 * 2026-10-28 Bug（生产注册不可用）：以前「保存成功」就等于「能用」，
 * 但 `enabled` 关着时保存照样回成功——生产就是这样把「填好了」当成「配好了」。
 * 现在每行固定显示就绪档位，不用等人去注册页踩一次才知道。
 */
const READINESS_TONE: Record<ChannelReadiness, NoteTone> = {
  ready: 'success',
  disabled: 'warning',
  incomplete: 'warning',
  unreadable: 'error',
}

interface TestResult {
  ok: boolean
  /** 可翻译的固定原因；与 message 最多有一个。 */
  messageKey: string | null
  /** 底层异常原文（SMTP 返回码这类），只在 messageKey 为空时使用。 */
  message: string | null
  target: string | null
  latencyMs: number
}

/**
 * 009 T535：全域通讯通道配置面板（嵌在管理端的系统设置页里）。
 *
 * 2026-10-28 改版三条约束（用户当场指定）：
 * - 名字从「身份通道」改成**全域通讯通道**——它管的不只是登录身份，还有注册与找回密码的信；
 * - 手机短信与微信**只在 UI 隐藏**，后端接口、`identity_channels` 三行数据与驱动枚举都不动，
 *   等资质齐了把这两行放回来即可；
 * - 邮件不再是一行「驱动 + 地址 + 密钥」，而是摊成发件人名称 / 发件人邮箱 / SMTP 服务器 /
 *   端口 / 加密方式 / 授权码六个字段，并可当场发一封测试邮件；
 *   2026-10-28 当天再简化：**认证账号不再单列**——登录账号就是发件人邮箱。
 *
 * 字段到库的映射全在 `emailChannelForm.ts`，这里只负责渲染。
 */
export default function IdentityChannelsPanel() {
  const { t } = useTranslation('translation')
  const [row, setRow] = useState<EmailChannelRow | null>(null)
  const [form, setForm] = useState<EmailForm | null>(null)
  const [issue, setIssue] = useState<EmailFormIssue | null>(null)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [test, setTest] = useState<TestResult | null>(null)

  const load = () => {
    api.get<EmailChannelRow[]>('/api/admin/identity-channels')
      .then((rows) => {
        // 手机短信与微信：后端照旧返回，界面先不显示（2026-10-28 用户要求）。
        const email = rows.find((r) => r.channel === 'email') ?? null
        if (!email) {
          setError(t('app.channels.missing'))
          return
        }
        setRow(email)
        setForm(toEmailForm(email))
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : String(e)))
  }

  useEffect(load, [])

  const change = (patch: Partial<EmailForm>) => {
    setForm((prev) => (prev ? { ...prev, ...patch } : prev))
    setIssue(null)
    setSaved(false)
    setTest(null)
  }

  const save = async () => {
    if (!row || !form) return
    const found = validateEmailForm(form, row.hasSecret)
    setIssue(found)
    if (found) return
    setSaving(true)
    setError(null)
    setSaved(false)
    setTest(null)
    try {
      const updated = await api.put<EmailChannelRow>('/api/admin/identity-channels/email',
        buildEmailUpdate(form, row.dailyQuota))
      setRow(updated)
      setForm(toEmailForm(updated))
      setSaved(true)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  const sendTest = async () => {
    setTesting(true)
    setError(null)
    setTest(null)
    try {
      setTest(await api.post<TestResult>('/api/admin/identity-channels/email/test'))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setTesting(false)
    }
  }

  const readiness = row ? channelReadiness(row) : 'incomplete'

  return (
    <div className="card" style={{ maxWidth: 900, marginTop: 16 }}>
      <h2>{t('app.channels.title')}</h2>
      <p className="small muted">{t('app.channels.sub')}</p>

      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}
      {saved && <Alert type="success" showIcon message={t('app.channels.saved')} style={{ marginBottom: 12 }} />}

      {row && form && (
        <>
          <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
            <b>{t('app.channels.emailTitle')}</b>
            <label className="row" style={{ gap: 8 }}>
              <span className="small">{t('app.channels.enable')}</span>
              <input type="checkbox" checked={form.enabled}
                onChange={(e) => change({ enabled: e.target.checked })} />
              <span className="small">{form.enabled ? t('app.channels.switchOn') : t('app.channels.switchOff')}</span>
            </label>
          </div>
          <p className="small muted">{t('app.channels.emailSub')}</p>

          <Field label={t('app.channels.fromName')} hint={t('app.channels.fromNameHint')} htmlFor="channel-from-name">
            <Input id="channel-from-name" value={form.fromName}
              placeholder={t('app.channels.fromNamePlaceholder')}
              onChange={(e) => change({ fromName: e.target.value })} />
          </Field>

          <Field label={t('app.channels.fromAddress')} required
            error={issue === 'fromAddress' ? t('app.channels.issue.fromAddress') : null}
            hint={t('app.channels.fromAddressHint')} htmlFor="channel-from-address">
            <Input id="channel-from-address" type="email" value={form.fromAddress}
              placeholder="no-reply@example.com"
              onChange={(e) => change({ fromAddress: e.target.value })} />
          </Field>

          <div className="row" style={{ gap: 12, alignItems: 'flex-start' }}>
            <div className="grow">
              <Field label={t('app.channels.smtpHost')} required
                error={issue === 'host' ? t('app.channels.issue.host') : null}
                hint={t('app.channels.smtpHostHint')} htmlFor="channel-smtp-host">
                <Input id="channel-smtp-host" value={form.host} placeholder="smtp.163.com"
                  onChange={(e) => change({ host: e.target.value })} />
              </Field>
            </div>
            <div style={{ width: 160 }}>
              <Field label={t('app.channels.smtpPort')} required
                error={issue === 'port' ? t('app.channels.issue.port') : null}
                htmlFor="channel-smtp-port">
                <Input id="channel-smtp-port" value={form.port} placeholder="465" inputMode="numeric"
                  onChange={(e) => change({ port: e.target.value })} />
              </Field>
            </div>
          </div>

          <Field label={t('app.channels.security')} info={t('app.channels.securityHint')}>
            <Radio.Group value={form.security}
              onChange={(e) => change({ security: e.target.value as SmtpSecurity })}>
              <Radio value="none">{t('app.channels.securityNone')}</Radio>
              <Radio value="ssl">{t('app.channels.securitySsl')}</Radio>
              <Radio value="starttls">{t('app.channels.securityStarttls')}</Radio>
            </Radio.Group>
          </Field>

          <Field label={t('app.channels.secret')} required={!row.hasSecret}
            error={issue === 'secret' ? t('app.channels.issue.secret') : null}
            hint={t('app.channels.secretHint')} htmlFor="channel-secret">
            <Password id="channel-secret" value={form.secret}
              placeholder={row.hasSecret ? (row.secretMask ?? '••••••••') : ''}
              autoComplete="new-password"
              onChange={(e) => change({ secret: e.target.value })} />
          </Field>

          <InlineNote tone={READINESS_TONE[readiness]} style={{ marginBottom: 10 }}>
            {t(`app.channels.state.${readiness}`)}
          </InlineNote>

          {test && (
            <Alert
              type={test.ok ? 'success' : 'error'}
              showIcon
              style={{ marginBottom: 12 }}
              message={test.ok
                ? t('app.channels.testOk', { to: test.target ?? '', ms: test.latencyMs })
                : t('app.channels.testFail', {
                  message: test.messageKey ? t(test.messageKey) : (test.message ?? t('app.channels.testUnknown')),
                })}
            />
          )}

          <div style={{ borderTop: '1px solid var(--line)', marginTop: 14, paddingTop: 14 }}>
            <div className="row" style={{ gap: 8 }}>
              <Button variant="primary" busy={saving} onClick={() => void save()}>
                {t('app.channels.saveSettings')}
              </Button>
              <Button busy={testing} disabled={!row.hasSecret} onClick={() => void sendTest()}>
                {t('app.channels.sendTest')}
              </Button>
            </div>
            <p className="small muted" style={{ marginTop: 8 }}>{t('app.channels.testHint')}</p>
          </div>
        </>
      )}
    </div>
  )
}
