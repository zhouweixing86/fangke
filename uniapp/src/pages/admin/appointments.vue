<template>
  <view class="page">
    <view class="chips">
      <view v-for="s in statuses" :key="s.name" class="chip" :class="{ active: status === s.name }"
        @click="switchStatus(s.name)">{{ s.label }}</view>
    </view>

    <view v-if="!rows.length" class="empty">没有符合条件的预约</view>

    <view v-for="row in rows" :key="row.appointmentNo" class="card" @click="actions(row)">
      <view class="card-title">
        <text>{{ row.visitorName }}</text>
        <text class="tag" :class="tagClass(row.status)">{{ row.statusText }}</text>
      </view>
      <view class="line">{{ row.purpose || '事由未填' }} · {{ row.idCardMask }}</view>
      <view class="line">{{ row.startTime }} ~ {{ row.endTime }}</view>
      <view class="line muted">{{ row.appointmentNo }} · {{ row.sourceText }}</view>
    </view>

    <TabBar :items="tabbar" />
  </view>
</template>

<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import TabBar from '@/components/TabBar.vue'
import { adminDispatch, listAppointments, revokeGrant } from '@/api'
import { requireLogin } from '@/store/auth'
import { alert, confirm, showToast } from '@/utils/ui'

const tabbar = [
  { text: '工作台', path: '/pages/admin/workbench' },
  { text: '预约', path: '/pages/admin/appointments' },
  { text: '通行', path: '/pages/admin/events' },
  { text: '设备', path: '/pages/admin/devices' }
]

const statuses = [
  { label: '全部', name: 'all' },
  { label: '待审批', name: '20' },
  { label: '可通行', name: '35' },
  { label: '已签离', name: '50' }
]
const status = ref('all')
const rows = ref<Record<string, any>[]>([])

function tagClass(value: number) {
  if (value === 35 || value === 40) return 'tag--ok'
  if (value === 20 || value === 25) return 'tag--warn'
  if (value === 60 || value === 70 || value === 90) return 'tag--danger'
  return 'tag--info'
}

async function load() {
  const params: Record<string, unknown> = { page: 1, size: 50 }
  if (status.value !== 'all') params.status = Number(status.value)
  rows.value = (await listAppointments(params)).list
}

function switchStatus(name: string) {
  status.value = name
  load()
}

function actions(row: Record<string, any>) {
  const items: string[] = ['复制单号', '手动重下']
  if (row.canRevoke) items.push('撤销授权')
  uni.showActionSheet({
    itemList: items,
    success: async (res) => {
      const picked = items[res.tapIndex]
      if (picked === '复制单号') {
        uni.setClipboardData({ data: row.appointmentNo })
      } else if (picked === '手动重下') {
        if (!(await confirm('重新下发 ' + row.appointmentNo + ' 到设备？', '手动下发'))) return
        const result = await adminDispatch(row.appointmentNo)
        await alert(result.message + '\n设备 ' + result.total + ' 台，成功 ' + result.success + '，失败 ' + result.fail, '下发结果')
        await load()
      } else if (picked === '撤销授权') {
        if (!(await confirm('撤销后该访客将从设备名单删除，确认？', '撤销授权'))) return
        const result = await revokeGrant(row.appointmentNo)
        showToast(result.message)
        await load()
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
