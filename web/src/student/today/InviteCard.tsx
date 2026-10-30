import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError, api } from '../../api/client'
import { Button, InlineNote, Input } from '../../ui'

interface InviteCardProps {
  /** 加入成功后由页面重新取 `/api/student/today`（AC-92.1：同一会话里出现作业卡）。 */
  onJoined: (className: string) => void
}

/** `POST /api/classes/join` 成功时的返回（classroom 的 `ClassView`，这里只用到 name）。 */
interface JoinedClass {
  id: string
  name: string
}

/**
 * 「有邀请码？」卡：未入班学生才看得到（AC-92.4 由页面控制，已入班时整卡不渲染）。
 *
 * 失败必须**内联**显示（AC-92.3）：不跳转、不清空输入——邀请码是一串容易看错的字符，
 * 清掉之后人只能重新抄一遍。404（码不对/班已结课）与 409（付费班暂未开放）都归到
 * 同一句 `today.invite.invalid`，因为对学生来说可做的动作是同一个：找老师换一个码。
 * 其它错误（403 / 网络 / 500）原样显示后端或网络的说明，不硬编一句「无效」去掩盖。
 *
 * 成功提示不放这里：加入成功后页面重新取数，`inClass` 变 true 会让本卡整个消失，
 * 卡片内的成功提示会连一个字都看不到。提示由页面渲染在卡片区上方。
 */
export default function InviteCard({ onJoined }: InviteCardProps) {
  const { t } = useTranslation()
  const [code, setCode] = useState('')
  const [joining, setJoining] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async () => {
    const value = code.trim()
    if (!value || joining) return
    setError(null)
    setJoining(true)
    try {
      const joined = await api.post<JoinedClass>('/api/classes/join', { inviteCode: value })
      onJoined(joined?.name ?? '')
    } catch (failure) {
      const status = failure instanceof ApiError ? failure.status : 0
      const known = status === 404 || status === 409 || status === 400
      setError(known ? t('today.invite.invalid') : (failure as Error).message)
    } finally {
      setJoining(false)
    }
  }

  return (
    <section className="card today-card" data-testid="today-invite-card">
      <div className="today-card-head">
        <h2>{t('today.invite.title')}</h2>
      </div>
      <div className="today-card-body">
        <p className="today-muted">{t('today.invite.body')}</p>
        {error && <InlineNote tone="error">{error}</InlineNote>}
        <div className="today-invite-form">
          <Input
            className="today-invite-input"
            data-testid="today-invite-input"
            value={code}
            placeholder={t('today.invite.placeholder')}
            autoComplete="off"
            spellCheck={false}
            onChange={(event) => {
              setCode(event.target.value)
              if (error) setError(null)
            }}
            onPressEnter={() => void submit()}
          />
          <Button
            variant="primary"
            data-testid="today-invite-submit"
            busy={joining}
            disabled={code.trim().length === 0}
            onClick={() => void submit()}
          >
            {joining ? t('today.invite.joining') : t('today.invite.submit')}
          </Button>
        </div>
      </div>
    </section>
  )
}
