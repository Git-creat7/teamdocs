import { useUserStore } from './user'
import { useSpacesStore } from './spaces'
import { usePreferencesStore } from './preferences'

export { useUserStore, useSpacesStore, usePreferencesStore }

/**
 * 登出 / 换账号时的唯一清理出口。
 * 新增 store 时在这里补一行 $reset，调用方永远只调这一个函数——
 * 避免"清理责任散落在各组件、漏一处就串号"的老问题。
 */
export function resetAllStores() {
  useUserStore().$reset()
  useSpacesStore().$reset()
}
