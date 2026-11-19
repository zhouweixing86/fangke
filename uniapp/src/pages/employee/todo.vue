<template>
  <view class="page">
    <view class="muted" style="margin-bottom: 8px">共 {{ total }} 条待办</view>

    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!list.length" class="empty">没有待审批的预约</view>

    <view v-for="item in list" :key="item.taskId" class="card" @click="open(item)">
      <view class="card-title">
        <text>{{ item.visitorName }}</text>
        <text class="tag" :class="item.level === 1 ? 'tag--warn' : 'tag--info'">
          {{ item.level === 1 ? '一级审批' : '二级审批' }}
        </text>
      </view>
      <view class="line">{{ item.purpose || '事由未填' }} · {{ item.idCardMask }}</view>
      <view class="line">{{ item.startTime }} ~ {{ item.endTime }}</view>
      <view class="line muted">单号 {{ item.appointmentNo }}</view>
    </view>

    <TabBar :items="tabbar" />
  </view>
</template>

<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import TabBar from '@/components/TabBar.vue'
import { myTodo, unreadCount } from '@/api'
import type { TodoView } from '@/api/types'
import { requireLogin } from '@/store/auth'

const list = ref<TodoView[]>([])
const total = ref(0)
const loading = ref(false)
const tabbar = ref([
  { text: '待我审批', path: '/pages/employee/todo' },
  { text: '消息', path: '/pages/common/messages', badge: 0 },
  { text: '我的', path: '/pages/common/profile' }
])

async function load() {
  loading.value = true
  try {
    const result = await myTodo({ page: 1, size: 50 })
    list.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function open(item: TodoView) {
  uni.navigateTo({ url: '/pages/employee/approval?taskId=' + item.taskId })
}

onShow(async () => {
  if (!requireLogin('APP')) return
  load()
  try {
    tabbar.value[1].badge = (await unreadCount()).unread
  } catch {
    // 忽略
  }
})

onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})
</script>
