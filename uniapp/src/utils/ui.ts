/** 跨端 UI 小工具：三端统一用 uni 的 API，避免各端差异散落在页面里。 */

export function showToast(title: string) {
  uni.showToast({ title, icon: 'none', duration: 2200 })
}

export function showLoading(title = '加载中') {
  uni.showLoading({ title, mask: true })
}

export function hideLoading() {
  uni.hideLoading()
}

/** 二次确认，返回是否点击了确定 */
export function confirm(content: string, title = '提示'): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title,
      content,
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false)
    })
  })
}

/** 只有确定按钮的提示 */
export function alert(content: string, title = '提示'): Promise<void> {
  return new Promise((resolve) => {
    uni.showModal({
      title,
      content,
      showCancel: false,
      success: () => resolve(),
      fail: () => resolve()
    })
  })
}

/** 多张图片预览（通行抓拍等） */
export function previewImages(urls: string[], current?: string) {
  if (!urls.length) return
  uni.previewImage({ urls, current: current || urls[0] })
}

/** 扫码：APP/小程序用原生扫码，H5 返回 null 由页面回退到手动输入 */
export function scanCode(): Promise<string | null> {
  return new Promise((resolve) => {
    // #ifdef H5
    resolve(null)
    // #endif
    // #ifndef H5
    uni.scanCode({
      success: (res) => resolve(res.result || null),
      fail: () => resolve(null)
    })
    // #endif
  })
}
