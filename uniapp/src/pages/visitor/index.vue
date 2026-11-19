<template>
  <view class="page">
    <view class="chips">
      <view v-for="tab in tabs" :key="tab.name" class="chip" :class="{ active: active === tab.name }"
        @click="switchTab(tab.name)">{{ tab.label }}</view>
    </view>

    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!list.length" class="empty">暂无预约，点击下方「访客预约」提交</view>

    <view v-for="item in list" :key="item.appointmentNo" class="card" @click="open(item)">
      <view class="card-title">
        <text>{{ item.visitorName }}</text>
        <text class="tag" :class="tagClass(item.status)">{{ item.statusText }}</text>
      </view>
      <view class="line">{{ item.purpose || '事由未填' }}</view>
      <view class="line">{{ item.visitDate }} {{ shortTime(item.startTime) }}–{{ shortTime(item.endTime) }}</view>
      <view class="line muted">单号 {{ item.appointmentNo }}</view>
    </view>

    <TabBar :items="tabbar" />
  </view>
</template>

<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import TabBar from '@/components/TabBar.vue'
import { myAppointments, unreadCount } from '@/api'
import type { AppointmentView } from '@/api/types'
import { requireLogin } from '@/store/auth'

const tabs = [
  { label: '全部', name: 'all' },
  { label: '待审批', name: '20' },
  { label: '可通行', name: '35' },
  { label: '已签离', name: '50' }
]
const active = ref('all')
const list = ref<AppointmentView[]>([])
const loading = ref(false)
const badge = ref(0)
const tabbar = ref([
  { text: '我的预约', path: '/pages/visitor/index' },
  { text: '访客预约', path: '/pages/visitor/new' },
  { text: '消息', path: '/pages/common/messages', badge: 0 },
  { text: '我的', path: '/pages/common/profile' }
])

function shortTime(value: string) {
  return value ? value.slice(11, 16) : ''
}

function tagClass(status: number) {
  if (status === 35 || status === 40) return 'tag--ok'
  if (status === 20 || status === 25) return 'tag--warn'
  if (status === 60 || status === 70 || status === 90) return 'tag--danger'
  return 'tag--info'
}

async function load() {
  loading.value = true
  try {
    const params: Record<string, unknown> = { page: 1, size: 50 }
    if (active.value !== 'all') params.status = Number(active.value)
    const result = await myAppointments(params)
    list.value = result.list
  } finally {
    loading.value = false
  }
}

function switchTab(name: string) {
  active.value = name
  load()
}

function open(item: AppointmentView) {
  uni.navigateTo({ url: '/pages/visitor/appointment?no=' + item.appointmentNo })
}

async function refreshBadge() {
  try {
    const result = await unreadCount()
    badge.value = result.unread
    tabbar.value[2].badge = result.unread
  } catch {
    // 忽略：角标不是关键路径
  }
}

onShow(() => {
  if (!requireLogin('APP')) return
  load()
  refreshBadge()
})

onPullDownRefresh(async () => {
  await load()
  await refreshBadge()
  uni.stopPullDownRefresh()
})
</script>

<style scoped>
.chips {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
  overflow-x: auto;
}

.chip {
  padding: 5px 14px;
  border-radius: 14px;
  background: #fff;
  color: #646566;
  font-size: 13px;
  white-space: nowrap;
}

.chip.active {
  background: #1989fa;
  color: #fff;
}
</style>
