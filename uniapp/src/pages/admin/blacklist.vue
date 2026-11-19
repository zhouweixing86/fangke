<template>
  <view class="page">
    <view class="card">
      <view class="card-title">新增黑名单</view>
      <view class="chips" style="margin-bottom: 6px">
        <view class="chip" :class="{ active: targetType === 1 }" @click="targetType = 1">身份证号</view>
        <view class="chip" :class="{ active: targetType === 2 }" @click="targetType = 2">手机号</view>
      </view>
      <view class="field">
        <text class="field-label">对象</text>
        <input v-model="target" class="field-input" :placeholder="targetType === 1 ? '18 位身份证号' : '11 位手机号'" />
      </view>
      <view class="field">
        <text class="field-label">原因</text>
        <input v-model="reason" class="field-input" placeholder="拉黑原因（必填）" />
      </view>
      <button class="btn btn--primary" style="margin-top: 12px" :loading="saving" @click="submit">加入黑名单</button>
    </view>

    <view v-if="!rows.length" class="empty">暂无黑名单记录</view>

    <view v-for="row in rows" :key="row.id" class="card">
      <view class="card-title">
        <text>{{ row.targetMask }}</text>
        <text class="tag" :class="row.status === 1 ? 'tag--danger' : 'tag--info'">{{ row.statusText }}</text>
      </view>
      <view class="line">{{ row.targetTypeText }} · {{ row.reason }}</view>
      <view class="line muted">有效期 {{ row.validFrom || '不限' }} ~ {{ row.validTo || '不限' }}</view>
      <view class="actions">
        <button v-if="row.status === 1" class="btn btn--danger" @click="enforce(row)">立即生效</button>
        <button v-if="row.status === 1" class="btn btn--ghost" @click="changeStatus(row, 0)">失效</button>
        <button v-else class="btn btn--ghost" @click="changeStatus(row, 1)">恢复</button>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { createBlacklist, enforceBlacklist, listBlacklist, updateBlacklistStatus } from '@/api'
import type { BlacklistRow } from '@/api/types'
import { requireLogin } from '@/store/auth'
import { alert, confirm, showToast } from '@/utils/ui'

const targetType = ref(1)
const target = ref('')
const reason = ref('')
const saving = ref(false)
const rows = ref<BlacklistRow[]>([])

async function load() {
  rows.value = (await listBlacklist({ page: 1, size: 50 })).list
}

async function submit() {
  if (!target.value || !reason.value) {
    showToast('请填写对象与原因')
    return
  }
  saving.value = true
  try {
    await createBlacklist({
      targetType: targetType.value,
      target: target.value.trim(),
      reason: reason.value.trim()
    })
    showToast('已加入黑名单')
    target.value = ''
    reason.value = ''
    await load()
  } finally {
    saving.value = false
  }
}

async function enforce(row: BlacklistRow) {
  if (!(await confirm('立即对「' + row.targetMask + '」生效？\n会撤回在途预约、撤销授权（删设备名单）、作废通行码。', '拉黑立即生效'))) return
  const result = await enforceBlacklist(row.id)
  await alert(result.message, '执行结果')
  await load()
}

async function changeStatus(row: BlacklistRow, status: number) {
  const text = status === 0 ? '失效后将不再拦截其预约' : '恢复拦截后其预约会被拒绝'
  if (!(await confirm(text + '，确认？', status === 0 ? '失效黑名单' : '恢复黑名单'))) return
  await updateBlacklistStatus(row.id, status)
  showToast(status === 0 ? '已失效' : '已恢复')
  await load()
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
}

.chip {
  padding: 5px 14px;
  border-radius: 14px;
  background: #f2f3f5;
  color: #646566;
  font-size: 13px;
}

.chip.active {
  background: #1989fa;
  color: #fff;
}
</style>
