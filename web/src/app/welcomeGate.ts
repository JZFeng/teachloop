/**
 * `/welcome` 首次引导的闸门（US-93 / AC-93.2）。
 *
 * 判断只做一件事：**要不要把人送去 `/welcome`**。跳转由 `App.tsx` 的路由层执行，
 * 记录「本设备已经问过」由 `markPrompted()` 写 `localStorage`。
 *
 * 为什么是纯函数 + 两个读写函数，而不是一个 hook：三个输入（资料是否缺、当前地址、是否问过）
 * 与一个输出（true/false）就是全部语义，纯函数才能被 `web/tests/welcome-gate.test.mjs` 直接跑，
 * 不用起浏览器也不用 mock react-router。
 *
 * 键名 `teachloop.profile.prompted` 是 spec 写死的（AC-93.2），不要改名：
 * 改了之后老设备上的「已问过」记录会全部失效，用户会第二次被拦。
 */

/** 引导页地址（AC-93.2 定的路由）。 */
export const WELCOME_PATH = '/welcome'

/** `localStorage` 键：本设备已经问过资料补全（AC-93.2）。 */
export const PROMPTED_KEY = 'teachloop.profile.prompted'

/**
 * 「补全资料」自己的两个页面：`/welcome`（首次引导）与 `/profile`（资料页）。
 *
 * 这两条地址被两处共用，别再各写一份：
 * 1. 引导闸门不拦它们（拦自己会无限重定向 / 人已经去补了，见下面的 `SKIP_PATHS`）；
 * 2. Shell 顶部那条「补全手机号」提示条不在它们上面显示（见 `shouldShowProfileBanner`）。
 */
export const PROFILE_COLLECTION_PATHS = ['/welcome', '/profile'] as const

/**
 * 这些地址不拦：
 * - 上面两个补全页：拦自己会无限重定向 / 人已经去补了；
 * - `/login`：还没登录，没有「资料缺不缺」这回事；
 * - `/setup`：全新实例只有它可达（建管理员之前没有任何账号）。
 */
const SKIP_PATHS: readonly string[] = [...PROFILE_COLLECTION_PATHS, '/login', '/setup']

export interface WelcomeGateInput {
  /**
   * 资料是否缺手机号：只有 `true` 才可能引导。
   * `false` 与 `null`（`useAuth().profileComplete` 的「还没查到 / 查不到」）一律放行——
   * 一次查询失败不该把人送进引导页。
   */
  profileIncomplete: boolean | null
  /** `useLocation().pathname`（不带查询串）。 */
  pathname: string
  /** 本设备是否已经问过（`isPrompted()` 的结果）。 */
  prompted: boolean
}

/** 去掉末尾斜杠与查询串/哈希：`/welcome/` 与 `/welcome` 是同一个页面。 */
function normalizePath(pathname: string): string {
  const raw = (pathname || '/').split('?')[0].split('#')[0]
  if (!raw) return '/'
  const trimmed = raw.replace(/\/+$/, '')
  return trimmed || '/'
}

/**
 * 是否需要送去 `/welcome`。
 *
 * 三个条件同时成立才为 true：资料确实缺手机号、当前地址不在豁免清单、本设备没问过。
 * 「资料未知」（查询失败）按不需要处理：宁可少拦一次，也不要因为一次网络抖动把人锁在引导页。
 */
export function shouldPrompt({ profileIncomplete, pathname, prompted }: WelcomeGateInput): boolean {
  if (profileIncomplete !== true) return false
  if (prompted) return false
  return !SKIP_PATHS.includes(normalizePath(pathname))
}

/** 当前地址是不是「补全资料」自己的页面（`/welcome`、`/profile`，带尾斜杠或查询串也算）。 */
export function isProfileCollectionPage(pathname: string): boolean {
  return (PROFILE_COLLECTION_PATHS as readonly string[]).includes(normalizePath(pathname))
}

export interface ProfileBannerInput {
  /**
   * `useAuth().profileComplete`：`false` 才缺资料；`true` 完整；
   * `null` 是「还没查到 / 查不到」，与引导闸门同口径——未知不等于缺失，不提醒。
   */
  profileComplete: boolean | null
  /** `useLocation().pathname`（不带查询串）。 */
  pathname: string
  /** 本会话是否已经点过提示条上的「关闭」（`sessionStorage` 记的就是这个）。 */
  dismissed: boolean
}

/**
 * Shell 顶部「补全手机号」提示条是否显示（AC-93.3）。
 *
 * 2026-10-30 Bug（用户截图 `/welcome`）：提示条原本只看「缺资料 + 没关过」，
 * 于是**人已经站在提示条要去的那一页上**（`/welcome` 首次引导、`/profile` 资料页），
 * 顶部还挂着「补全手机号 · 去补全」。那条提示在语义上已经被执行了（等同于「已读」），
 * 再显示只会重复催一次，而且它的整行宽度与下面的窄卡片叠出第三条左边缘。
 *
 * 这里只负责「目标页当场不显示」（避免等 effect 写完 sessionStorage 之前闪一下）；
 * 「进过补全页就把本会话标记成已读」由 `shouldMarkProfileBannerRead` 决定。
 */
export function shouldShowProfileBanner({
  profileComplete,
  pathname,
  dismissed,
}: ProfileBannerInput): boolean {
  if (profileComplete !== false) return false
  if (dismissed) return false
  return !isProfileCollectionPage(pathname)
}

/**
 * 要不要把提示条记成「已读」——写 `sessionStorage` 的那一次。
 *
 * 2026-10-30 用户决定（原话：「第一次没填，就不打扰用户了。以后逻辑需要的时候再引导过来」）：
 * 人一旦进过 `/welcome` 或 `/profile`，这条提示就算读过，**本会话内不再出现**；
 * 想再提醒就靠后续流程自己引导（教师建班的 403 `PROFILE_INCOMPLETE` 内联提示还在）。
 * 因此提示条在「新设备首登」这条路径上根本不会出现——那时人一定先落在 `/welcome`。
 */
export function shouldMarkProfileBannerRead({
  profileComplete,
  pathname,
  dismissed,
}: ProfileBannerInput): boolean {
  if (profileComplete !== false) return false
  if (dismissed) return false
  return isProfileCollectionPage(pathname)
}

/**
 * 取 `localStorage`；取不到（SSR、隐私模式、被策略禁用）返回 null。
 *
 * 访问 `localStorage` 本身可能抛异常（Safari 无痕模式），所以连「有没有」都要包在 try 里。
 * 用 `globalThis` 而不是 `window`：node 测试可以直接塞一个假实现进来。
 */
function storage(): Storage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

/**
 * 记下「本设备已经问过」。
 *
 * 写不进去也不阻断流程：引导页的「以后再说」必须能走完，
 * 存不了最多是下次登录再问一次，比卡在引导页好。
 */
export function markPrompted(): void {
  try {
    storage()?.setItem(PROMPTED_KEY, '1')
  } catch {
    // 配额满 / 隐私模式：忽略，不影响跳转。
  }
}

/** 本设备是否已经问过；没有 `localStorage` 或读失败时返回 false（安全默认：当作没问过）。 */
export function isPrompted(): boolean {
  try {
    return storage()?.getItem(PROMPTED_KEY) === '1'
  } catch {
    return false
  }
}

/** 清掉记录：只有「用户要求重新走一遍引导」这类显式动作才该调用（当前没有调用点）。 */
export function clearPrompted(): void {
  try {
    storage()?.removeItem(PROMPTED_KEY)
  } catch {
    // 同上，忽略。
  }
}
