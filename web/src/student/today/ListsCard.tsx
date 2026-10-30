import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Button } from '../../ui'

/**
 * 「我的词单」入口卡（AC-90.5 / AC-90.7）。
 *
 * 刻意是一张**静态**卡：不请求 `/api/vocab/lists`。今日页首次加载只允许两次请求
 * （`/api/student/today` 与 `/api/auth/me`），为了在首页显示三份词单的名字去多打一枪，
 * 换来的只是把 `today` 页变成一个聚合面板——词单是「管理」动作，点进去看才是对的。
 */
export default function ListsCard() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  return (
    <section className="card today-card" data-testid="today-lists-card">
      <div className="today-card-head">
        <h2>{t('today.lists.title')}</h2>
      </div>
      <div className="today-card-body">
        <p className="today-muted">{t('today.lists.body')}</p>
        <div className="today-actions">
          <Button onClick={() => navigate('/vocab/lists')}>{t('today.lists.open')}</Button>
        </div>
      </div>
    </section>
  )
}
