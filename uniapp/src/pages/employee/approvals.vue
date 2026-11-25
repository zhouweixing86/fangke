<template>
  <view class="page">
    <view class="stats">
      <view class="stat"><text class="num">{{ stats.total }}</text><text class="cap">已审批</text></view>
      <view class="stat"><text class="num ok">{{ stats.approved }}</text><text class="cap">通过</text></view>
      <view class="stat"><text class="num no">{{ stats.rejected }}</text><text class="cap">驳回</text></view>
      <view class="stat"><text class="num warn">{{ pending }}</text><text class="cap">待审批</text></view>
    </view>

    <view class="tabs">
      <view class="tab" :class="{ active: tab === 'todo' }" @click="switchTab('todo')">
        待审批<text v-if="pending > 0" class="badge">{{ pending }}</text>
      </view>
      <view class="tab" :class="{ active: tab === 'done' }" @click="switchTab('done')">已审批</view>
    </view>

    <view v-if="tab === 'done'" class="chips">
      <view v-for="c in chips" :key="String(c.value)" class="chip" :class="{ active: actionFilter === c.value }"
        @click="switchFilter(c.value)">
        {{ c.label }}
      </view>
    </view>

    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="rows.length === 0" class="empty">
      {{ tab === 'todo' ? '暂无待审批' : '还没有审批记录' }}
    </view>

    <view v-for="item in rows" :key="item.taskId" class="item" @click="open(item)">
      <view class="item-head">
        <text class="visitor">{{ item.visitorName || '访客' }}</text>
        <text class="node">{{ item.nodeName || ('第 ' + item.level + ' 级') }}</text>
      </view>
      <view class="line">单号：{{ item.appointmentNo || '-' }}</view>
      <view class="line">事由：{{ item.purpose || '-' }}</view>
      <view v-if="tab === 'todo'" class="line">
        {{ item.startTime }} ~ {{ item.endTime }}
      </view>
      <view v-else class="line">
        {{ item.actionText }} · {{ item.actedAt }}
        <text v-if="item.comment"> · {{ item.comment }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { myApprovalCount, myApprovalDone, myApprovalStats, myApprovalTodo } from '@/api'
import type { MyApprovalDone, TodoView } from '@/api/types'
import { ensureVisitorLogin } from '@/utils/visitor'

type Tab = 'todo' | 'done'
type Row = (TodoView & { actionText?: string; actedAt?: string; comment?: string; nodeName?: string })

const tab = ref<Tab>('todo')
const pending = ref(0)
const rows = ref<Row[]>([])
const loading = ref(false)
const stats = ref({ total: 0, approved: 0, rejected: 0 })
/** 已审批筛选：全部 / 通过 / 驳回 */
const actionFilter = ref<number | undefined>(undefined)
const chips: { label: string; value: number | undefined }[] = [
  { label: '全部', value: undefined },
  { label: '通过', value: 1 },
  { label: '驳回', value: 2 }
]

async function loadStats() {
  try {
    stats.value = await myApprovalStats()
  } catch {
    stats.value = { total: 0, approved: 0, rejected: 0 }
  }
}

async function load() {
  loading.value = true
  try {
    await ensureVisitorLogin()
    const count = await myApprovalCount()
    pending.value = count.pending || 0
    await loadStats()
    if (tab.value === 'todo') {
      const page = await myApprovalTodo({ page: 1, size: 50 })
      rows.value = page.list as Row[]
    } else {
      const page = await myApprovalDone({ page: 1, size: 50, action: actionFilter.value })
      rows.value = page.list as unknown as Row[]
    }
  } finally {
    loading.value = false
  }
}

function switchTab(next: Tab) {
  if (tab.value === next) return
  tab.value = next
  rows.value = []
  load()
}

function switchFilter(value: number | undefined) {
  if (actionFilter.value === value) return
  actionFilter.value = value
  rows.value = []
  load()
}

function open(item: Row) {
  uni.navigateTo({ url: '/pages/employee/approval?taskId=' + item.taskId })
}

onShow(load)
onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding: 24rpx;
}

.tabs {
  display: flex;
  background: #ffffff;
  border-radius: 16rpx;
  overflow: hidden;
}

.stats {
  display: flex;
  background: #ffffff;
  border-radius: 16rpx;
  padding: 20rpx 0;
  margin-bottom: 16rpx;
}

.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.stat .num {
  font-size: 36rpx;
  font-weight: 600;
  color: #1f2329;
}

.stat .num.ok { color: #0aa06e; }
.stat .num.no { color: #e45656; }
.stat .num.warn { color: #e6a23c; }

.stat .cap {
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #909399;
}

.chips {
  display: flex;
  gap: 16rpx;
  margin-top: 16rpx;
}

.chip {
  padding: 10rpx 28rpx;
  border-radius: 30rpx;
  background: #ffffff;
  color: #606266;
  font-size: 26rpx;
}

.chip.active {
  background: #1989fa;
  color: #ffffff;
}

.tab {
  flex: 1;
  text-align: center;
  padding: 26rpx 0;
  font-size: 30rpx;
  color: #606266;
}

.tab.active {
  color: #1989fa;
  font-weight: 600;
  border-bottom: 4rpx solid #1989fa;
}

.badge {
  margin-left: 8rpx;
  padding: 0 10rpx;
  border-radius: 20rpx;
  background: #e45656;
  color: #ffffff;
  font-size: 22rpx;
}

.item {
  margin-top: 20rpx;
  background: #ffffff;
  border-radius: 16rpx;
  padding: 24rpx;
}

.item-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.visitor {
  font-size: 32rpx;
  font-weight: 600;
  color: #1f2329;
}

.node {
  font-size: 24rpx;
  color: #1989fa;
  background: #eaf3fe;
  padding: 4rpx 14rpx;
  border-radius: 8rpx;
}

.line {
  margin-top: 10rpx;
  font-size: 26rpx;
  color: #606266;
}

.empty {
  margin-top: 120rpx;
  text-align: center;
  color: #909399;
  font-size: 28rpx;
}
</style>
