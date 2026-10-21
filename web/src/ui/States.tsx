import type { ReactNode } from 'react'
import { Alert, App, Button, Empty as AntEmpty, Skeleton } from 'antd'
import { useTranslation } from 'react-i18next'

/**
 * 三种状态必须是三种不同的东西，而不是一行灰字：
 *   加载 → 骨架屏（用户能看出「在等」且知道内容形状）
 *   出错 → 可重试的警告块
 *   空   → 空态插画 + 一句该干什么
 */
export function Loading({ label }: { label?: string }) {
  const { t } = useTranslation('translation')
  return (
    <div className="tl-state-card" aria-busy="true" aria-label={label ?? t('common.loading')}>
      <Skeleton active paragraph={{ rows: 3 }} title={false} />
    </div>
  )
}

export function ErrorState({ error, onRetry }: { error: Error; onRetry?: () => void }) {
  const { t } = useTranslation('translation')
  return (
    <div className="tl-state-card">
      <Alert
        type="error"
        showIcon
        message={t('common.errorWith')}
        description={error.message}
        action={onRetry ? <Button size="small" onClick={onRetry}>{t('common.retry')}</Button> : undefined}
      />
    </div>
  )
}

export function Empty({ children }: { children?: ReactNode }) {
  const { t } = useTranslation('translation')
  return (
    <div className="tl-state-card">
      <AntEmpty description={children ?? t('common.empty')} image={AntEmpty.PRESENTED_IMAGE_SIMPLE} />
    </div>
  )
}

/** 二次确认：替代 `window.confirm`，键盘可取消，危险操作用红色主按钮。 */
export function useConfirm() {
  const { modal } = App.useApp()
  return (options: { title: string; content?: ReactNode; okText?: string; cancelText?: string; danger?: boolean }) =>
    new Promise<boolean>((resolve) => {
      modal.confirm({
        title: options.title,
        content: options.content,
        okText: options.okText,
        cancelText: options.cancelText,
        okButtonProps: { danger: options.danger },
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      })
    })
}

/** 轻提示：成功/失败给一句，不要用 alert。 */
export function useToast() {
  const { message } = App.useApp()
  return {
    success: (text: string) => message.success(text),
    error: (text: string) => message.error(text),
    info: (text: string) => message.info(text),
  }
}
