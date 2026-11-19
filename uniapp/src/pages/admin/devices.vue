<template>
  <view class="page">
    <view v-if="!devices.length" class="empty">没有启用中的设备</view>

    <view v-for="device in devices" :key="device.id" class="card" @click="actions(device)">
      <view class="card-title">
        <text>{{ device.deviceName }}（{{ device.directionText }}）</text>
        <text class="tag" :class="device.online ? 'tag--ok' : 'tag--danger'">
          {{ device.online ? '在线' : '离线' }}
        </text>
      </view>
      <view class="line">{{ device.model }} · {{ device.channel }} · {{ device.ip }}</view>
      <view class="line">门点 {{ device.gateName || '未分配' }}</view>
      <view class="line">SN {{ device.deviceSn }}</view>
      <view class="line">人数 {{ device.personCount }} / {{ device.capacity || '-' }} · 心跳 {{ device.lastHeartbeat || '-' }}</view>
    </view>

    <TabBar :items="tabbar" />
  </view>
</template>

<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import TabBar from '@/components/TabBar.vue'
import { listDevices, openDeviceDoor, selfCheckDevice, showDeviceMessage } from '@/api'
import type { DeviceView } from '@/api/types'
import { requireLogin } from '@/store/auth'
import { alert, confirm, showToast } from '@/utils/ui'

const tabbar = [
  { text: '工作台', path: '/pages/admin/workbench' },
  { text: '预约', path: '/pages/admin/appointments' },
  { text: '通行', path: '/pages/admin/events' },
  { text: '设备', path: '/pages/admin/devices' }
]

const devices = ref<DeviceView[]>([])

async function load() {
  devices.value = await listDevices()
}

function actions(device: DeviceView) {
  const items = ['远程开门', '接入自检', '屏幕提示', '复制 SN']
  uni.showActionSheet({
    itemList: items,
    success: async (res) => {
      const picked = items[res.tapIndex]
      if (picked === '远程开门') {
        if (!(await confirm('确认远程打开「' + device.deviceName + '」？', '开门'))) return
        await openDeviceDoor(device.id)
        showToast('开门指令已发送')
      } else if (picked === '接入自检') {
        const result = await selfCheckDevice(device.id)
        const lines = result.items.map((i) => '[' + i.status + '] ' + i.label + '：' + i.detail)
        await alert('结论：' + result.status + '\n' + lines.join('\n'), '自检结果')
      } else if (picked === '屏幕提示') {
        uni.showModal({
          title: '屏幕提示（≤14 字）',
          editable: true,
          placeholderText: '如：请到前台登记',
          success: async (modal) => {
            const text = (modal.content || '').trim()
            if (!text) return
            if (text.length > 14) {
              showToast('最多 14 个字')
              return
            }
            await showDeviceMessage(device.id, text)
            showToast('已发送')
          }
        })
      } else {
        uni.setClipboardData({ data: device.deviceSn })
      }
    }
  })
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
