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
      <view class="tab" :class="{ active: tab === 'msg' }" @click="switchTab('msg')">
        消息<text v-if="unread > 0" class="badge">{{ unread }}</text>
      </view>
    </view>

    <!-- 消息：抄送知会 + 审批通知（docs/26 B5） -->
    <template v-if="tab === 'msg'">
      <view v-if="unread > 0" class="cc-bar" @click="markAll">
        <text>全部标为已读（{{ unread }}）</text>
      </view>
      <view v-if="loading" class="empty">加载中…</view>
      <view v-else-if="msgs.length === 0" class="empty">暂无消息</view>
      <view v-for="m in msgs" :key="m.id" class="item" :class="{ unread: !m.read }" @click="openMsg(m)">
        <view class="item-head">
          <text class="visitor">{{ m.title }}</text>
          <text class="node">{{ m.createdAt }}</text>
        </view>
        <view class="line">{{ m.content }}</view>
        <view v-if="m.bizNo" class="line">单号：{{ m.bizNo }}</view>
      </view>
    </template>

    <template v-else>
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
    </template>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import {
  listAppNotifications,
  appUnreadCount,
  readAllAppNotifications,
  readAppNotification,
  myApprovalCount,
  myApprovalDone,
  myApprovalStats,
  myApprovalTodo
} from '@/api'
import type { MyApprovalDone, MyNotification, TodoView } from '@/api/types'
import { ensureVisitorLogin } from '@/utils/visitor'

type Tab = 'todo' | 'done' | 'msg'
type Row = (TodoView & { actionText?: string; actedAt?: string; comment?: string; nodeName?: string })

const tab = ref<Tab>('todo')
const pending = ref(0)
const rows = ref<Row[]>([])
const loading = ref(false)
/** 消息中心：未读数与列表（抄送知会、待办/催办通知） */
const unread = ref(0)
const msgs = ref<MyNotification[]>([])
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
    // 未读角标与当前 tab 无关：进页面就能看到有没有新消息
    try {
      unread.value = (await appUnreadCount()).unread || 0
    } catch {
      unread.value = 0
    }
    if (tab.value === 'msg') {
      const list = await listAppNotifications({ page: 1, size: 50 })
      msgs.value = list.list
    } else if (tab.value === 'todo') {
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
  msgs.value = []
  load()
}

/** 读一条消息：收起未读角标，单号可点进审批详情（仅审批类） */
async function openMsg(m: MyNotification) {
  if (!m.read) {
    try {
      await readAppNotification(m.id)
      m.read = true
      unread.value = Math.max(0, unread.value - 1)
    } catch {
      // 标记已读失败不影响查看
    }
  }
}

async function markAll() {
  try {
    await readAllAppNotifications()
    msgs.value = msgs.value.map((m) => ({ ...m, read: true }))
    unread.value = 0
  } catch {
    // 忽略：下次刷新会重新拉未读数
  }
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

/**
 * 订阅消息授权（docs/26 B1）：模板 ID 来自构建环境变量，没配就跳过。
 * 一天只弹一次，避免每次进页面都打扰。
 */
const SUB_TODO = import.meta.env.VITE_WX_SUB_TODO || ''
const SUB_RESULT = import.meta.env.VITE_WX_SUB_RESULT || ''

function requestSubscribe() {
  const tmplIds = [SUB_TODO, SUB_RESULT].filter((id) => !!id)
  if (tmplIds.length === 0) return
  const today = new Date().toISOString().slice(0, 10)
  if (uni.getStorageSync('wx_sub_asked') === today) return
  uni.setStorageSync('wx_sub_asked', today)
  // #ifdef MP-WEIXIN
  uni.requestSubscribeMessage({
    tmplIds,
    fail: () => {
      // 用户拒绝或模板未配置：静默忽略，不影响审批
    }
  })
  // #endif
}

onShow(() => {
  load()
  requestSubscribe()
})
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

/* 未读消息左侧高亮条 */
.item.unread {
  border-left: 6rpx solid #1989fa;
}

.cc-bar {
  margin-top: 20rpx;
  background: #fff7e6;
  border-radius: 16rpx;
  padding: 20rpx 24rpx;
  font-size: 26rpx;
  color: #b25b00;
  text-align: center;
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
