<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { auth } from '@/store/auth'

/** 未登录也能进的页面：登录页、首次来访预约页（后端预约接口是公开的） */
const PUBLIC_PAGES = ['pages/login/', 'pages/visitor/']

onLaunch(() => {
  // #ifdef MP-WEIXIN
  // 访客端：无登录页，进小程序即静默一键登录（各页自行调用 wx-login），这里不做跳转
  return
  // #endif
  // #ifndef MP-WEIXIN
  // 管理端 H5：无 token 直接进登录页（各页也有守卫）
  const pages = getCurrentPages()
  const current = pages.length ? pages[pages.length - 1].route : ''
  if (!auth.token && current && !PUBLIC_PAGES.some((p) => current.startsWith(p))) {
    uni.reLaunch({ url: '/pages/login/login' })
  }
  // #endif
})
</script>

<style>
page {
  background: #f5f6f8;
  color: #1f2329;
  font-size: 14px;
  font-family: -apple-system, BlinkMacSystemFont, 'Helvetica Neue', Helvetica, 'PingFang SC',
    'Microsoft YaHei', sans-serif;
}

.page {
  min-height: 100vh;
  padding: 12px 12px 76px;
  box-sizing: border-box;
}

.page--plain {
  padding-bottom: 12px;
}

.card {
  background: #fff;
  border-radius: 10px;
  padding: 12px;
  margin-bottom: 10px;
}

.card-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  font-size: 15px;
  margin-bottom: 6px;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 0;
  font-size: 13px;
}

.row-label {
  color: #969799;
}

.muted {
  color: #969799;
  font-size: 12px;
}

.line {
  margin-top: 3px;
  font-size: 12px;
  color: #646566;
}

.tag {
  display: inline-block;
  padding: 1px 7px;
  border-radius: 10px;
  font-size: 11px;
  line-height: 18px;
}

.tag--ok {
  color: #07c160;
  background: rgba(7, 193, 96, 0.1);
}

.tag--warn {
  color: #ff976a;
  background: rgba(255, 151, 106, 0.12);
}

.tag--danger {
  color: #ee0a24;
  background: rgba(238, 10, 36, 0.1);
}

.tag--info {
  color: #969799;
  background: #f2f3f5;
}

.btn {
  margin: 0;
  padding: 0;
  font-size: 14px;
  line-height: 1;
  background: transparent;
}

.btn::after {
  border: none;
}

.btn--primary {
  background: #1989fa;
  color: #fff;
  border-radius: 8px;
  height: 44px;
  line-height: 44px;
  text-align: center;
  font-size: 15px;
}

.btn--ghost {
  border: 1px solid #1989fa;
  color: #1989fa;
  border-radius: 8px;
  height: 38px;
  line-height: 36px;
  text-align: center;
}

.btn--danger {
  border: 1px solid #ee0a24;
  color: #ee0a24;
  border-radius: 8px;
  height: 38px;
  line-height: 36px;
  text-align: center;
}

.btn--block {
  display: block;
  width: 100%;
}

.actions {
  display: flex;
  gap: 10px;
  margin-top: 10px;
}

.actions > view,
.actions > button {
  flex: 1;
}

.field {
  display: flex;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid #f2f3f5;
}

.field-label {
  width: 92px;
  color: #646566;
  font-size: 13px;
}

.field-input {
  flex: 1;
  font-size: 14px;
}

.field-value {
  flex: 1;
  font-size: 14px;
  text-align: right;
}

.empty {
  padding: 48px 0;
  text-align: center;
  color: #969799;
  font-size: 13px;
}

.qr-box {
  text-align: center;
  padding: 8px 0;
}

.qr-img {
  width: 240px;
  height: 240px;
  margin: 0 auto;
  display: block;
}

.qr-code {
  margin-top: 8px;
  font-size: 11px;
  color: #969799;
  word-break: break-all;
}
</style>
