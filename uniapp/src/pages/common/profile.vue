<template>
  <view class="page">
    <view class="card head">
      <view class="avatar">{{ initial }}</view>
      <view>
        <view class="name">{{ info?.name || auth.name }}</view>
        <view class="muted">{{ roleText }} · {{ info?.phoneMask || auth.phoneMask }}</view>
      </view>
    </view>

    <view class="card">
      <view class="row"><text class="row-label">角色</text><text>{{ roleText }}</text></view>
      <view v-if="info?.personNo" class="row"><text class="row-label">人员编号</text><text>{{ info.personNo }}</text></view>
      <view v-if="info?.deptName" class="row"><text class="row-label">部门</text><text>{{ info.deptName }}</text></view>
      <view v-if="info?.company" class="row"><text class="row-label">单位</text><text>{{ info.company }}</text></view>
    </view>

    <view v-if="!isEmployee()" class="card">
      <view class="card-title"><text>通行方式</text></view>
      <view class="muted">到闸机时请刷身份证进门（访客不支持刷脸）</view>
    </view>

    <button class="btn btn--danger btn--block" @click="onLogout">退出登录</button>
    <view class="muted" style="text-align: center; margin-top: 12px">访客通行 v1.0 · 如遇问题请联系前台</view>

    <TabBar :items="tabbar" />
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import TabBar from '@/components/TabBar.vue'
import { appMe } from '@/api'
import type { AppMe } from '@/api/types'
import { auth, isEmployee, logout, requireLogin } from '@/store/auth'
import { confirm } from '@/utils/ui'

const info = ref<AppMe | null>(null)
const tabbar = ref<any[]>([])

const roleText = computed(() => (isEmployee() ? '员工' : '访客'))
const initial = computed(() => (info.value?.name || auth.name || '?').slice(0, 1))

async function onLogout() {
  if (await confirm('确认退出登录？', '退出')) logout()
}

onShow(async () => {
  if (!requireLogin('APP')) return
  tabbar.value = isEmployee()
    ? [
        { text: '待我审批', path: '/pages/employee/todo' },
        { text: '消息', path: '/pages/common/messages' },
        { text: '我的', path: '/pages/common/profile' }
      ]
    : [
        { text: '消息', path: '/pages/common/messages' },
        { text: '我的', path: '/pages/common/profile' }
      ]
  try {
    info.value = await appMe()
  } catch {
    info.value = null
  }
})
</script>

<style scoped>
.head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.avatar {
  width: 46px;
  height: 46px;
  line-height: 46px;
  border-radius: 50%;
  background: #1989fa;
  color: #fff;
  text-align: center;
  font-size: 20px;
}

.name {
  font-size: 16px;
  font-weight: 600;
}
</style>
