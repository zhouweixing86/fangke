<template>
  <view class="page">
    <view v-if="!detail" class="empty">加载中…</view>
    <template v-else>
      <view class="card">
        <view class="row"><text class="label">预约单号</text><text>{{ detail.appointmentNo }}</text></view>
        <view class="row"><text class="label">访客</text><text>{{ detail.visitorName }}</text></view>
        <view class="row"><text class="label">证件号</text><text>{{ detail.idCardMask }}</text></view>
        <view class="row"><text class="label">类型/事由</text><text>{{ detail.visitorType }} · {{ detail.purpose || '-' }}</text></view>
        <view class="row"><text class="label">来访时间</text><text>{{ detail.startTime }} ~ {{ detail.endTime }}</text></view>
        <view class="row"><text class="label">被访人手机</text><text>{{ detail.visitPhoneMask }}</text></view>
        <view class="row">
          <text class="label">审批节点</text>
          <text>{{ detail.nodeName || ('第 ' + detail.level + ' 级') }}（{{ detail.statusText }}）</text>
        </view>
        <view class="row">
          <text class="label">批准门点</text>
          <text>{{ gateText }}</text>
        </view>
      </view>

      <view v-if="detail.qrRequested" class="card">
        <view class="row">
          <text class="label">同意二维码开门</text>
          <switch :checked="qrApproved" @change="onQrChange" />
        </view>
        <view class="tip">访客申请了二维码开门，同意后访客可在「我的预约」出示二维码进闸。</view>
      </view>

      <view v-if="detail.actions && detail.actions.length" class="card">
        <view class="card-title">审批轨迹</view>
        <view v-for="(a, i) in detail.actions" :key="i" class="action">
          {{ a.actionText }} · {{ a.operatorId }} · {{ a.at }}
          <text v-if="a.comment">（{{ a.comment }}）</text>
        </view>
      </view>

      <view v-if="detail.canAct" class="card action-card">
        <view class="card-title">审批意见</view>
        <textarea v-model="comment" class="textarea" maxlength="100"
          placeholder="选填；驳回时必须填写原因" />
      </view>

      <view v-if="detail.canAct" class="footer-bar">
        <button class="foot-btn ghost" @click="reject">驳回</button>
        <button class="foot-btn primary" :loading="submitting" @click="pass">通过</button>
      </view>
    </template>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { approveTask, myApprovalDetail, rejectTask } from '@/api'
import type { ApprovalDetail } from '@/api/types'
import { ensureVisitorLogin } from '@/utils/visitor'
import { alert, showToast } from '@/utils/ui'

const detail = ref<ApprovalDetail | null>(null)
const taskId = ref(0)
const comment = ref('')
const qrApproved = ref(false)
const submitting = ref(false)

const gateText = computed(() => {
  const gates = detail.value?.gates
  if (!gates) return '-'
  const ids = gates.approvedGateIds && gates.approvedGateIds.length
    ? gates.approvedGateIds
    : gates.appliedGateIds
  const names = (ids || []).map((id) => gates.selectable.find((g) => g.id === id)?.name || String(id))
  return names.join('、') || (gates.gateScope === 2 ? '全部门点' : '-')
})

function onQrChange(e: any) {
  qrApproved.value = !!e.detail.value
}

async function load() {
  await ensureVisitorLogin()
  detail.value = await myApprovalDetail(taskId.value)
  qrApproved.value = !!detail.value.qrApproved
}

async function pass() {
  submitting.value = true
  try {
    const result = await approveTask(taskId.value, {
      comment: comment.value || undefined,
      qrApproved: detail.value?.qrRequested ? qrApproved.value : undefined
    })
    await alert(`预约 ${result.appointmentNo} 已通过\n当前状态：${result.statusText}`, '审批完成')
    uni.navigateBack()
  } finally {
    submitting.value = false
  }
}

async function reject() {
  if (!comment.value.trim()) return showToast('驳回必须填写原因')
  submitting.value = true
  try {
    await rejectTask(taskId.value, comment.value.trim())
    await alert('已驳回该预约', '审批完成')
    uni.navigateBack()
  } finally {
    submitting.value = false
  }
}

onLoad(async (options: Record<string, string>) => {
  taskId.value = Number(options?.taskId || 0)
  if (!taskId.value) {
    showToast('缺少任务参数')
    return
  }
  await load()
})
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding: 24rpx;
  /* 给底部固定操作栏留出空间 */
  padding-bottom: 220rpx;
}

.card {
  background: #ffffff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin-bottom: 20rpx;
}

.card-title {
  font-size: 30rpx;
  font-weight: 600;
  margin-bottom: 12rpx;
}

.row {
  display: flex;
  justify-content: space-between;
  padding: 12rpx 0;
  font-size: 28rpx;
  color: #1f2329;
}

.label {
  color: #909399;
}

.action {
  padding: 8rpx 0;
  font-size: 26rpx;
  color: #606266;
}

.tip {
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #909399;
}

.textarea {
  display: block;
  width: 100%;
  height: 180rpx;
  border: 2rpx solid #e4e7ed;
  border-radius: 12rpx;
  padding: 16rpx;
  font-size: 28rpx;
  box-sizing: border-box;
}

/* 底部固定操作栏：驳回 / 通过 各占一半，避免误触 */
.footer-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  gap: 24rpx;
  padding: 20rpx 24rpx;
  padding-bottom: calc(20rpx + env(safe-area-inset-bottom));
  background: #ffffff;
  box-shadow: 0 -2rpx 12rpx rgba(0, 0, 0, 0.06);
  z-index: 10;
}

.foot-btn {
  flex: 1;
  margin: 0;
  height: 96rpx;
  line-height: 96rpx;
  border-radius: 48rpx;
  font-size: 32rpx;
  font-weight: 500;
}

.foot-btn::after {
  border: none;
}

.foot-btn.ghost {
  background: #ffffff;
  color: #e45656;
  border: 2rpx solid #e45656;
}

.foot-btn.primary {
  background: #1989fa;
  color: #ffffff;
}

.empty {
  margin-top: 120rpx;
  text-align: center;
  color: #909399;
  font-size: 28rpx;
}
</style>
