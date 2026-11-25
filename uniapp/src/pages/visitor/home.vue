<template>
  <view class="home">
    <view class="hero">
      <image class="logo" src="/static/logo.png" mode="aspectFit" />
      <text class="brand">访客预约</text>
      <text class="slogan">预约申请 · 到访通行</text>
      <text class="build">构建版 2026-11-21</text>
    </view>

    <view class="actions">
      <button class="pill" @click="goBooking">预约申请</button>
      <button class="pill pill--ghost" @click="goMine">我的预约</button>
      <button v-if="approver" class="pill pill--approval" @click="goApprovals">
        我的审批<text v-if="pending > 0" class="badge">{{ pending }}</text>
      </button>
    </view>

    <view class="wave wave--1" />
    <view class="wave wave--2" />
    <view class="wave wave--3" />
  </view>
</template>

<script setup lang="ts">
import { onShareAppMessage, onShareTimeline, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { myApprovalCount } from '@/api'
import { auth, isApprover } from '@/store/auth'
import { ensureVisitorLogin } from '@/utils/visitor'

/**
 * 访客端首页（微信小程序）。
 *
 * <p>不做注册：进小程序即静默一键登录（wx.login → /api/app/auth/wx-login）。
 * 未绑定时后端仍发 token（subjectId=0），访客可先提交预约，提交后自动绑定 openid。
 *
 * <p>C-76：扫过管理端「角色二维码」的微信会带上后台角色，这里额外显示「我的审批」。
 */
const approver = ref(isApprover())
const pending = ref(0)

function goBooking() {
  uni.navigateTo({ url: '/pages/visitor/new' })
}

function goMine() {
  uni.navigateTo({ url: '/pages/visitor/index' })
}

function goApprovals() {
  uni.navigateTo({ url: '/pages/employee/approvals' })
}

async function oneClickLogin() {
  if (auth.token && auth.kind === 'APP' && (auth.subjectId > 0 || isApprover())) {
    return
  }
  try {
    await ensureVisitorLogin()
  } catch {
    // 静默失败：不影响浏览与提交预约，提交时会再试一次
  }
}

async function refreshApprover() {
  approver.value = isApprover()
  if (!approver.value) {
    pending.value = 0
    return
  }
  try {
    const result = await myApprovalCount()
    pending.value = result.pending || 0
  } catch {
    pending.value = 0
  }
}

onShow(async () => {
  await oneClickLogin()
  approver.value = isApprover()
  await refreshApprover()
})

/* ---------------- 分享（C-81）：小程序转发 + 朋友圈 ---------------- */
onShareAppMessage(() => ({
  title: '访客通行｜预约申请、刷证进门',
  path: '/pages/visitor/home'
}))
onShareTimeline(() => ({
  title: '访客通行｜预约申请、刷证进门'
}))
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
  font-size: 46rpx;
  font-weight: 600;
  color: #1f2329;
  letter-spacing: 4rpx;
}

.slogan {
  margin-top: 14rpx;
  font-size: 30rpx;
  color: #8a9099;
}

/* 构建标识：联调时用来确认开发者工具跑的是哪一版 */
.build {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #c0c4cc;
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
  height: 100rpx;
  line-height: 100rpx;
  border-radius: 50rpx;
  background: #ffffff;
  border: 2rpx solid #1989fa;
  color: #1f2329;
  font-size: 36rpx;
  font-weight: 500;
}

.pill::after {
  border: none;
}

.pill--ghost {
  background: #ffffff;
}

.pill--approval {
  background: #1989fa;
  color: #ffffff;
  border-color: #1989fa;
}

.badge {
  margin-left: 8rpx;
  padding: 0 10rpx;
  border-radius: 20rpx;
  background: #e45656;
  color: #ffffff;
  font-size: 22rpx;
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
