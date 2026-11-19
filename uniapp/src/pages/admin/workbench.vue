<template>
  <view class="page">
    <view class="card head">
      <view>
        <view class="name">{{ auth.name }}</view>
        <view class="muted">{{ auth.roles.join(' / ') }} · 管理端</view>
      </view>
      <text class="link" @click="onLogout">退出</text>
    </view>

    <view class="stats">
      <view class="stat"><text class="stat-value">{{ dashboard.onsite }}</text><text class="muted">当前在访</text></view>
      <view class="stat"><text class="stat-value">{{ dashboard.pendingApproval }}</text><text class="muted">待审批</text></view>
      <view class="stat">
        <text class="stat-value">{{ dashboard.devicesOnline }}/{{ dashboard.devicesTotal }}</text>
        <text class="muted">设备在线</text>
      </view>
      <view class="stat"><text class="stat-value">{{ dashboard.todayCheckins }}</text><text class="muted">今日到访</text></view>
    </view>

    <view class="quick">
      <text class="quick-item" @click="go('/pages/admin/blacklist')">黑名单</text>
      <text class="quick-item" @click="go('/pages/admin/report')">报表</text>
      <text class="quick-item" @click="refresh">刷新</text>
    </view>

    <view class="chips">
      <view class="chip" :class="{ active: tab === 'onsite' }" @click="tab = 'onsite'">在访人员</view>
      <view class="chip" :class="{ active: tab === 'pending' }" @click="tab = 'pending'">待审批（只读）</view>
    </view>

    <view v-if="tab === 'onsite'">
      <view v-if="!onsite.length" class="empty">当前没有在访人员</view>
      <view v-for="row in onsite" :key="row.appointmentNo" class="card">
        <view class="card-title">
          <text>{{ row.visitorName }}</text>
          <text class="tag" :class="row.overdue ? 'tag--danger' : 'tag--ok'">{{ row.overdue ? '已超时' : '在访' }}</text>
        </view>
        <view class="line">{{ row.purpose || '事由未填' }} · {{ row.idCardMask }}</view>
        <view class="line">入场 {{ row.checkinAt }}，预约结束 {{ row.endTime }}</view>
        <view class="actions">
          <button class="btn btn--ghost" @click="openDoor(row.appointmentNo)">开门</button>
          <button class="btn btn--danger" @click="checkout(row.appointmentNo)">签离</button>
        </view>
      </view>
    </view>

    <view v-else>
      <view v-if="!pending.length" class="empty">没有待审批的现场登记</view>
      <view v-for="row in pending" :key="row.appointmentNo" class="card">
        <view class="card-title">
          <text>{{ row.visitorName }}</text>
          <text class="tag tag--warn">{{ row.sourceText }}</text>
        </view>
        <view class="line">{{ row.purpose || '事由未填' }} · {{ row.idCardMask }}</view>
        <view class="line">提交 {{ row.createdAt }}</view>
        <view class="actions">
          <button class="btn btn--ghost" @click="remind(row.appointmentNo)">催办</button>
        </view>
      </view>
    </view>

    <TabBar :items="tabbar" />
  </view>
</template>

<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import TabBar from '@/components/TabBar.vue'
import { guardCheckout, guardDashboard, guardOnsite, guardOpenDoor, guardPending, guardRemind } from '@/api'
import type { GuardDashboard, OnsiteRow, PendingRow } from '@/api/types'
import { auth, isGuardOrAdmin, logout, requireLogin } from '@/store/auth'
import { alert, confirm, showToast } from '@/utils/ui'

const tabbar = [
  { text: '工作台', path: '/pages/admin/workbench' },
  { text: '预约', path: '/pages/admin/appointments' },
  { text: '通行', path: '/pages/admin/events' },
  { text: '设备', path: '/pages/admin/devices' }
]

const dashboard = ref<GuardDashboard>({
  onsite: 0, pendingApproval: 0, devicesOnline: 0, devicesTotal: 0, todayCheckins: 0, deviceStatusText: ''
})
const onsite = ref<OnsiteRow[]>([])
const pending = ref<PendingRow[]>([])
const tab = ref<'onsite' | 'pending'>('onsite')

async function refresh() {
  dashboard.value = await guardDashboard()
  onsite.value = (await guardOnsite({ page: 1, size: 50 })).list
  pending.value = (await guardPending({ page: 1, size: 50 })).list
}

function go(path: string) {
  uni.navigateTo({ url: path })
}

async function openDoor(appointmentNo: string) {
  if (!(await confirm('确认为该访客远程开门？', '开门'))) return
  const result = await guardOpenDoor({ appointmentNo })
  showToast(result.opened ? '已开门（' + result.devices + ' 台设备）' : '开门失败')
  await refresh()
}

async function checkout(appointmentNo: string) {
  if (!(await confirm('确认手工签离？', '签离'))) return
  await guardCheckout(appointmentNo)
  showToast('已签离')
  await refresh()
}

async function remind(appointmentNo: string) {
  try {
    const result = await guardRemind(appointmentNo)
    showToast('已催办，剩余 ' + result.remaining + ' 次')
  } catch {
    // 限流等错误已由 http 层提示
  }
}

async function onLogout() {
  if (await confirm('确认退出登录？', '退出')) logout()
}

onShow(() => {
  if (!requireLogin('ADMIN')) return
  if (!isGuardOrAdmin()) {
    alert('当前账号没有保安/管理员权限', '无权限').then(() => logout())
    return
  }
  refresh()
})

onPullDownRefresh(async () => {
  await refresh()
  uni.stopPullDownRefresh()
})
</script>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.name {
  font-size: 16px;
  font-weight: 600;
}

.link {
  color: #1989fa;
  font-size: 13px;
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
  font-size: 18px;
  font-weight: 600;
}

.quick {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.quick-item {
  flex: 1;
  text-align: center;
  padding: 9px 0;
  background: #fff;
  border-radius: 8px;
  font-size: 13px;
  color: #1989fa;
}

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
