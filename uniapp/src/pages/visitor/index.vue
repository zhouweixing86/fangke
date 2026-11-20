<template>
  <view class="page">
    <view v-if="loading" class="hint">加载中…</view>

    <!-- 空态：对照江苏院小程序「无预约」样式 -->
    <view v-else-if="!list.length" class="empty">
      <view class="empty-line" />
      <text class="empty-text">无预约</text>
      <view class="empty-line" />
      <button class="empty-btn" @click="goBooking">去预约</button>
    </view>

    <template v-else>
      <view class="chips">
        <view v-for="tab in tabs" :key="tab.name" class="chip" :class="{ active: active === tab.name }"
          @click="switchTab(tab.name)">{{ tab.label }}</view>
      </view>

      <view v-for="item in list" :key="item.appointmentNo" class="card" @click="open(item)">
        <view class="card-head">
          <text class="name">{{ item.visitorName }}</text>
          <text class="tag" :class="tagClass(item.status)">{{ item.statusText }}</text>
        </view>
        <view class="line">{{ item.visitDate }} {{ item.periodText || '' }}</view>
        <view class="line muted">{{ item.purpose || '事由未填' }}</view>
        <view class="line muted">
          <text>被访人 {{ item.visitPhoneMask || '-' }}</text>
          <text v-if="item.company"> · {{ item.company }}</text>
        </view>
        <view class="line muted">单号 {{ item.appointmentNo }}</view>
      </view>
    </template>
  </view>
</template>

<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { myAppointments, wxLogin } from '@/api'
import type { AppointmentView } from '@/api/types'
import { applyWxLogin, auth } from '@/store/auth'

const tabs = [
  { label: '全部', name: 'all' },
  { label: '待审批', name: '20' },
  { label: '可通行', name: '35' },
  { label: '已签离', name: '50' }
]
const active = ref('all')
const list = ref<AppointmentView[]>([])
const loading = ref(false)

function tagClass(status: number) {
  if (status === 35 || status === 40) return 'tag--ok'
  if (status === 20 || status === 25) return 'tag--warn'
  if (status === 60 || status === 70 || status === 90) return 'tag--danger'
  return 'tag--info'
}

function goBooking() {
  uni.navigateTo({ url: '/pages/visitor/new' })
}

function open(item: AppointmentView) {
  uni.navigateTo({ url: '/pages/visitor/appointment?no=' + item.appointmentNo })
}

function switchTab(name: string) {
  active.value = name
  load()
}

/** 静默一键登录：已有 token 直接复用，没有就 wx.login 换一个 */
async function ensureLogin() {
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
    // 未登录也允许浏览（首次来访还没提交过预约）
  }
}

async function load() {
  if (!auth.token) {
    list.value = []
    return
  }
  loading.value = true
  try {
    const params: Record<string, unknown> = { page: 1, size: 50 }
    if (active.value !== 'all') params.status = Number(active.value)
    const result = await myAppointments(params)
    list.value = result.list
  } catch {
    list.value = []
  } finally {
    loading.value = false
  }
}

onShow(async () => {
  await ensureLogin()
  await load()
})

onPullDownRefresh(async () => {
  await ensureLogin()
  await load()
  uni.stopPullDownRefresh()
})
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: #eef4fb;
  padding: 20rpx 24rpx 40rpx;
  box-sizing: border-box;
}

.hint {
  text-align: center;
  color: #9aa1ad;
  font-size: 32rpx;
  padding-top: 160rpx;
}

.empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 20rpx;
  padding-top: 60rpx;
  flex-wrap: wrap;
}

.empty-line {
  width: 180rpx;
  height: 1rpx;
  background: #2b3a55;
  opacity: 0.6;
}

.empty-text {
  font-size: 40rpx;
  color: #2b3a55;
  letter-spacing: 4rpx;
}

.empty-btn {
  width: 100%;
  margin-top: 60rpx;
  height: 88rpx;
  line-height: 88rpx;
  border-radius: 44rpx;
  background: #1565e8;
  color: #ffffff;
  font-size: 34rpx;
}

.empty-btn::after {
  border: none;
}

.chips {
  display: flex;
  gap: 12rpx;
  margin-bottom: 16rpx;
  overflow-x: auto;
}

.chip {
  padding: 10rpx 26rpx;
  border-radius: 28rpx;
  background: #ffffff;
  color: #646566;
  font-size: 30rpx;
  white-space: nowrap;
}

.chip.active {
  background: #1565e8;
  color: #ffffff;
}

.card {
  background: #ffffff;
  border-radius: 16rpx;
  padding: 24rpx 26rpx;
  margin-bottom: 16rpx;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10rpx;
}

.name {
  font-size: 36rpx;
  font-weight: 600;
  color: #1f2329;
}

.tag {
  font-size: 26rpx;
  padding: 4rpx 14rpx;
  border-radius: 20rpx;
}

.tag--ok {
  background: #e7f7ee;
  color: #17a34a;
}

.tag--warn {
  background: #fff4e5;
  color: #d97706;
}

.tag--danger {
  background: #fdecec;
  color: #d93026;
}

.tag--info {
  background: #f1f3f5;
  color: #6b7280;
}

.line {
  font-size: 30rpx;
  color: #4a5160;
  line-height: 1.7;
}

.muted {
  color: #8a9099;
}
</style>
