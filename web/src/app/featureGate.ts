/**
 * 路由级可见性判定（007-role-nav），从 `App.tsx` 的 `RequireFeature` 抽出来做纯函数。
 *
 * 为什么单独成文件：`web/tests/*.test.mjs` 只跑纯模块，不渲染 React；
 * 把「放行 / 等开关 / 退回落地页」这三态抽出来，才能对「学生深链 /admin/ai」
 * 写一条不依赖浏览器的断言（2026-10-29 Bug：这条路由漏挂守卫，学生能看到系统设置页）。
 */
export type FeatureGate = 'loading' | 'allow' | 'deny'

export function featureGate(input: {
  loaded: boolean
  features: Record<string, boolean>
  feature: string
}): FeatureGate {
  // 开关还没回来时不放行也不重定向：深链 /vocab/* 的开关后到，先跳会把 URL 吃掉（T389）。
  if (!input.loaded) return 'loading'
  return input.features[input.feature] === true ? 'allow' : 'deny'
}
