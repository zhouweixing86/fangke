<template>
  <view class="page">
    <view class="bar">
      <text class="muted">未读 {{ unread }} 条</text>
      <text class="link" @click="markAll">全部已读</text>
    </view>

    <view v-if="!list.length" class="empty">暂无消息</view>

    <view v-for="item in list" :key="item.id" class="card" :style="{ opacity: item.read ? 0.65 : 1 }"
      @click="open(item)">
      <view class="card-title">
        <text>
          <text v-if="!item.read" class="dot" />{{ item.title }}
        </text>
        <text class="muted">{{ item.createdAt }}</text>
      </view>
      <view class="line">{{ item.content }}</view>
    </view>

    <TabBar :items="tabbar" />
  </view>
</template>

<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import TabBar from '@/components/TabBar.vue'
import { myNotifications, readAllNotifications, readNotification, unreadCount } from '@/api'
import type { NotificationItem } from '@/api/types'
import { auth, isEmployee, logout, requireLogin } from '@/store/auth'
import { showToast } from '@/utils/ui'

const list = ref<NotificationItem[]>([])
const unread = ref(0)
const tabbar = ref<any[]>([])

function buildTabbar(badge: number) {
  tabbar.value = isEmployee()
    ? [
        { text: '待我审批', path: '/pages/employee/todo' },
        { text: '消息', path: '/pages/common/messages', badge },
        { text: '我的', path: '/pages/common/profile' }
      ]
    : [
        { text: '我的预约', path: '/pages/visitor/index' },
        { text: '访客预约', path: '/pages/visitor/new' },
        { text: '消息', path: '/pages/common/messages', badge },
        { text: '我的', path: '/pages/common/profile' }
      ]
}

async function load() {
  const result = await myNotifications({ page: 1, size: 50 })
  list.value = result.list
  unread.value = (await unreadCount()).unread
  buildTabbar(unread.value)
}

async function open(item: NotificationItem) {
  if (!item.read) {
    await readNotification(item.id)
    item.read = true
    unread.value = Math.max(0, unread.value - 1)
    buildTabbar(unread.value)
  }
  if (item.bizType === 'APPOINTMENT' && item.bizNo) {
    uni.navigateTo({ url: '/pages/visitor/appointment?no=' + item.bizNo })
  } else if (item.bizType === 'APPROVAL') {
    uni.reLaunch({ url: '/pages/employee/todo' })
  }
}

async function markAll() {
  const result = await readAllNotifications()
  showToast('已全部标记已读（' + result.updated + ' 条）')
  await load()
}

onShow(() => {
  if (!requireLogin('APP')) return
  if (!auth.token) return
  load()
})

onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})
</script>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.link {
  color: #1989fa;
  font-size: 13px;
}

.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ee0a24;
  margin-right: 5px;
  vertical-align: middle;
}
</style>
