<template>
  <view class="page">
    <view class="chips">
      <view v-for="d in ranges" :key="d" class="chip" :class="{ active: range === d }" @click="switchRange(d)">
        近{{ d }}天
      </view>
    </view>

    <view class="stats">
      <view class="stat"><text class="stat-value">{{ overview.appointments }}</text><text class="muted">预约总数</text></view>
      <view class="stat"><text class="stat-value">{{ overview.approved }}</text><text class="muted">审批通过</text></view>
      <view class="stat"><text class="stat-value">{{ overview.checkins }}</text><text class="muted">到访人次</text></view>
      <view class="stat"><text class="stat-value">{{ overview.onsiteNow }}</text><text class="muted">当前在访</text></view>
    </view>
    <view class="stats">
      <view class="stat"><text class="stat-value">{{ overview.events }}</text><text class="muted">通行事件</text></view>
      <view class="stat"><text class="stat-value">{{ percent(overview.eventPassRate) }}</text><text class="muted">通行成功率</text></view>
      <view class="stat"><text class="stat-value">{{ overview.avgApprovalMinutes }}</text><text class="muted">平均审批(分)</text></view>
      <view class="stat"><text class="stat-value">{{ overview.pending }}</text><text class="muted">待审批</text></view>
    </view>

    <view class="card">
      <view class="card-title">
        <text>逐日明细</text>
        <text class="muted">{{ overview.from }} ~ {{ overview.to }}</text>
      </view>
      <view class="line" style="display: flex; justify-content: space-between">
        <text>日期</text><text>预约 / 到访 / 通行(成功·失败)</text>
      </view>
      <view v-for="day in daily" :key="day.date" class="line" style="display: flex; justify-content: space-between">
        <text>{{ day.date.slice(5) }}</text>
        <text>{{ day.appointments }} / {{ day.checkins }} / {{ day.eventPass }}·{{ day.eventFail }}</text>
      </view>
      <view v-if="!daily.length" class="empty">该时段没有数据</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { reportDaily, reportOverview } from '@/api'
import type { ReportDaily, ReportOverview } from '@/api/types'
import { requireLogin } from '@/store/auth'

const ranges = [7, 30]
const range = ref(7)
const overview = ref<ReportOverview>({
  from: '', to: '', appointments: 0, approved: 0, rejected: 0, pending: 0, withdrawn: 0,
  checkins: 0, onsiteNow: 0, events: 0, eventPass: 0, eventFail: 0, avgApprovalMinutes: 0,
  approvalRate: 0, eventPassRate: 0
})
const daily = ref<ReportDaily[]>([])

function percent(rate: number) {
  if (!rate) return '0%'
  return (rate * 100).toFixed(1) + '%'
}

async function load() {
  const to = new Date()
  const from = new Date(to.getTime() - (range.value - 1) * 86400000)
  const fmt = (d: Date) =>
    d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0')
  const params = { from: fmt(from), to: fmt(to) }
  overview.value = await reportOverview(params)
  daily.value = (await reportDaily(params)).list
}

function switchRange(value: number) {
  range.value = value
  load()
}

onShow(() => {
  if (!requireLogin('ADMIN')) return
  load()
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

.stats {
  display: flex;
  background: #fff;
  border-radius: 10px;
  padding: 12px 0;
  margin-bottom: 10px;
}

.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.stat-value {
  font-size: 17px;
  font-weight: 600;
}
</style>
