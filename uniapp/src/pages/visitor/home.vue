<template>
  <view class="home">
    <view class="hero">
      <image class="logo" src="/static/logo.png" mode="aspectFit" />
      <text class="brand">访客预约</text>
      <text class="slogan">预约申请 · 到访通行</text>
    </view>

    <view class="actions">
      <button class="pill" @click="goBooking">预约申请</button>
      <button class="pill pill--ghost" @click="goMine">我的预约</button>
    </view>

    <view class="wave wave--1" />
    <view class="wave wave--2" />
    <view class="wave wave--3" />
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { wxLogin } from '@/api'
import { applyWxLogin, auth } from '@/store/auth'

/**
 * 访客端首页（微信小程序）。
 *
 * <p>不做注册：进小程序即静默一键登录（wx.login → /api/app/auth/wx-login）。
 * 未绑定时后端仍发 token（subjectId=0），访客可先提交预约，提交后自动绑定 openid。
 */
function goBooking() {
  uni.navigateTo({ url: '/pages/visitor/new' })
}

function goMine() {
  uni.navigateTo({ url: '/pages/visitor/index' })
}

async function oneClickLogin() {
  if (auth.token && auth.kind === 'APP') {
    return
  }
  try {
    const code = await new Promise<string>((resolve, reject) => {
      uni.login({
        provider: 'weixin',
        success: (res) => (res.code ? resolve(res.code) : reject(new Error('未获取到 code'))),
        fail: () => reject(new Error('微信登录失败'))
      })
    })
    applyWxLogin(await wxLogin(code))
  } catch {
    // 静默失败：不影响浏览与提交预约，提交时仍可正常工作
  }
}

onShow(() => {
  oneClickLogin()
})
</script>

<style scoped>
.home {
  position: relative;
  min-height: 100vh;
  background: #ffffff;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-top: 180rpx;
}

.logo {
  width: 200rpx;
  height: 200rpx;
  border-radius: 50%;
  background: #eaf3fe;
}

.brand {
  margin-top: 36rpx;
  font-size: 40rpx;
  font-weight: 600;
  color: #1f2329;
  letter-spacing: 4rpx;
}

.slogan {
  margin-top: 14rpx;
  font-size: 26rpx;
  color: #8a9099;
}

.actions {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 32rpx;
  margin-top: 120rpx;
}

.pill {
  width: 460rpx;
  height: 92rpx;
  line-height: 92rpx;
  border-radius: 46rpx;
  background: #ffffff;
  border: 2rpx solid #1989fa;
  color: #1f2329;
  font-size: 32rpx;
  font-weight: 500;
}

.pill::after {
  border: none;
}

.pill--ghost {
  background: #ffffff;
}

.wave {
  position: absolute;
  left: -20%;
  width: 140%;
  border-radius: 50%;
  background: #e8f2fd;
}

.wave--1 {
  bottom: -120rpx;
  height: 320rpx;
  opacity: 0.9;
}

.wave--2 {
  bottom: -200rpx;
  height: 360rpx;
  background: #d7e9fb;
  opacity: 0.9;
}

.wave--3 {
  bottom: -300rpx;
  height: 420rpx;
  background: #c3ddf7;
}
</style>
