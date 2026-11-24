import { wxLogin } from '@/api'
import { applyWxLogin, auth, cacheWxCode, requestWxCode } from '@/store/auth'

/**
 * 确保拿到「可用的访客登录态」。
 *
 * <p>小程序的身份来自静默 wx.login：第一次进来只能拿到未绑定的 token（subjectId=0），
 * 只有提交过一次预约、后端把 openid 绑到访客档案后，再登录才会带上 subjectId。
 * 所以「我的预约」这类需要身份的页面必须按下面条件重新登录，而不是"有 token 就直接用"：
 * 没有 token，或者 token 里的 subjectId 还是 0（未绑定）。
 *
 * @param force 强制重新登录（例如刚提交完预约，需要立刻刷新绑定状态）
 */
export async function ensureVisitorLogin(force = false): Promise<void> {
  const usable = !!auth.token && auth.kind === 'APP' &&
    (auth.subjectId > 0 || auth.roles.indexOf('APP_APPROVER') >= 0)
  if (!force && usable) {
    return
  }
  const code = await requestWxCode()
  const result = await wxLogin(code)
  applyWxLogin(result)
  // mock 模式下 openid 由 code 派生，必须复用同一个 code 才能保持身份稳定
  if (result.mock) cacheWxCode(code)
}

/**
 * 取当前微信的 openid（顺带完成登录）。
 *
 * <p>首次预约要把 openid 传给后端做绑定，否则「我的预约」永远查不到自己的单。
 * 登录失败时返回已缓存的 openid（可能为空），不阻塞预约提交。
 */
export async function visitorOpenid(): Promise<string> {
  try {
    await ensureVisitorLogin()
  } catch {
    // 静默失败：预约本身是公开接口，不带 openid 也能提交，只是这次不绑定
  }
  return auth.openid || ''
}
