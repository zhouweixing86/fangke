<template>
  <view class="page">
    <view class="chips">
      <view v-for="d in days" :key="d" class="chip" :class="{ active: range === d }" @click="switchRange(d)">
        近{{ d }}天
      </view>
    </view>

    <view v-if="!rows.length" class="empty">该时段没有通行记录</view>

    <view v-for="row in rows" :key="row.id" class="card">
      <view class="card-title">
        <text>{{ row.personName || row.personNo || '未知人员' }}</text>
        <text class="tag" :class="row.resultStatus === 1 ? 'tag--ok' : 'tag--danger'">{{ row.resultText }}</text>
      </view>
      <view class="line">{{ row.eventTime }} · {{ row.directionText }} · {{ row.identifyTypeText }}</view>
      <view class="line muted">{{ row.deviceSn }} · {{ row.sourceText }}</view>
      <view v-if="row.hasMedia" class="actions">
        <button class="btn btn--ghost" @click="showMedia(row)">查看抓拍</button>
      </view>
    </view>

    <TabBar :items="tabbar" />
  </view>
</template>

<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import TabBar from '@/components/TabBar.vue'
import { listEventMedia, listEvents } from '@/api'
import type { EventRow } from '@/api/types'
import { requireLogin } from '@/store/auth'
import { previewImages, showToast } from '@/utils/ui'

const tabbar = [
  { text: '工作台', path: '/pages/admin/workbench' },
  { text: '预约', path: '/pages/admin/appointments' },
  { text: '通行', path: '/pages/admin/events' },
  { text: '设备', path: '/pages/admin/devices' }
]

const days = [1, 7, 30]
const range = ref(7)
const rows = ref<EventRow[]>([])

function ymd(date: Date) {
  return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0')
}

async function load() {
  const to = new Date()
  const from = new Date(to.getTime() - (range.value - 1) * 86400000)
  rows.value = (await listEvents({ from: ymd(from), to: ymd(to), page: 1, size: 50 })).list
}

function switchRange(value: number) {
  range.value = value
  load()
}

async function showMedia(row: EventRow) {
  const media = await listEventMedia(row.id)
  if (!media.length) {
    showToast('照片已过保留期')
    return
  }
  previewImages(media.map((m) => m.url))
}

onShow(() => {
  if (!requireLogin('ADMIN')) return
  load()
})

onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})
</script>

<style scoped>
.chips {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.chip {
  padding: 5px 14px;
  border-radius: 14px;
  background: #fff;
  color: #646566;
  font-size: 13px;
}

.chip.active {
  background: #1989fa;
  color: #fff;
}
</style>
