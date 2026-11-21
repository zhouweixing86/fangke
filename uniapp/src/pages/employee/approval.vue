<template>
  <view class="page page--plain">
    <view v-if="!detail" class="empty">加载中…</view>

    <template v-else>
      <view class="card">
        <view class="card-title">
          <text>预约信息</text>
          <text class="tag" :class="detail.canAct ? 'tag--warn' : 'tag--ok'">{{ detail.statusText }}</text>
        </view>
        <view class="row"><text class="row-label">单号</text><text>{{ detail.appointmentNo }}</text></view>
        <view class="row"><text class="row-label">访客</text><text>{{ detail.visitorName }}（{{ detail.visitorType || '-' }}）</text></view>
        <view class="row"><text class="row-label">证件号</text><text>{{ detail.idCardMask }}</text></view>
        <view class="row"><text class="row-label">事由</text><text>{{ detail.purpose || '-' }}</text></view>
        <view class="row"><text class="row-label">时段</text><text>{{ detail.startTime }} ~ {{ detail.endTime }}</text></view>
        <view class="row"><text class="row-label">预约状态</text><text>{{ detail.appointmentStatusText }}</text></view>
      </view>

      <view v-if="detail.gates" class="card">
        <view class="card-title">
          <text>开放门点</text>
          <text class="tag tag--ok">{{ approvedGateIds.length }} 个</text>
        </view>
        <view class="muted" style="padding: 0 4px 6px">
          访客申请：{{ appliedText }}
        </view>
        <checkbox-group style="display: flex; flex-wrap: wrap; gap: 12px; padding: 4px 0"
          @change="onGateChange">
          <label v-for="g in detail.gates.selectable" :key="g.id"
            style="display: flex; align-items: center; gap: 4px">
            <checkbox :value="String(g.id)" :checked="approvedGateIds.includes(g.id)"
              :disabled="!detail.canAct" />
            <text>{{ g.name }}</text>
          </label>
        </checkbox-group>
        <view v-if="detail.canAct" style="display: flex; gap: 10px; margin-top: 8px">
          <button size="mini" @click="selectAllGates">全部门点</button>
          <button size="mini" @click="resetToApplied">恢复申请范围</button>
        </view>
        <view v-if="detail.gates.gateScope === 2" class="muted" style="padding-top: 6px">
          该访客申请了全部门点，请按实际需要收窄
        </view>
      </view>

      <view v-if="detail.qrRequested" class="card">
        <view class="card-title">
          <text>二维码开门</text>
          <text class="tag" :class="qrApproved ? 'tag--ok' : 'tag--info'">
            {{ qrApproved ? '已同意' : '未同意' }}
          </text>
        </view>
        <view class="muted">访客申请用二维码进闸（60 秒动态码）。同意后访客端「我的预约」才能生成通行码。</view>
        <label style="display: flex; align-items: center; gap: 8px; margin-top: 10px">
          <checkbox :checked="qrApproved" :disabled="!detail.canAct"
            @click="qrApproved = !qrApproved" />
          <text>同意二维码开门</text>
        </label>
      </view>

      <view v-if="detail.canAct" class="card">
        <view class="card-title">审批意见</view>
        <textarea v-model="comment" class="opinion" maxlength="200" placeholder="通过可不填；驳回必填原因" />
      </view>

      <view v-if="detail.canAct" class="actions">
        <button class="btn btn--danger" :loading="acting" @click="onReject">驳回</button>
        <button class="btn btn--primary" :loading="acting" @click="onPass">通过</button>
      </view>

      <view v-if="detail.actions.length" class="card" style="margin-top: 10px">
        <view class="card-title">流转记录</view>
        <view v-for="(a, i) in detail.actions" :key="i" class="line">
          {{ a.actionText }} · {{ a.at }}<text v-if="a.comment"> · {{ a.comment }}</text>
        </view>
      </view>
    </template>
  </view>
</template>

<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { approvalDetail, approvalPass, approvalReject } from '@/api'
import type { ApprovalDetail } from '@/api/types'
import { requireLogin } from '@/store/auth'
import { alert, showToast } from '@/utils/ui'

const taskId = ref(0)
const detail = ref<ApprovalDetail | null>(null)
const comment = ref('')
const acting = ref(false)
/** 审批人要批准的门点：默认等于访客申请集合，可增可减 */
const approvedGateIds = ref<number[]>([])
/** 是否同意访客的「二维码开门」申请（只在该预约申请过时才有意义） */
const qrApproved = ref(false)

const appliedText = computed(() => {
  const gates = detail.value?.gates
  if (!gates) return '-'
  if (gates.gateScope === 2) return '全部门点'
  const names = gates.selectable
    .filter((o) => gates.appliedGateIds.includes(o.id))
    .map((o) => o.name)
  return names.length > 0 ? names.join('、') : '-'
})

async function load() {
  detail.value = await approvalDetail(taskId.value)
  approvedGateIds.value = [...(detail.value.gates?.approvedGateIds ?? [])]
  qrApproved.value = Boolean(detail.value.qrApproved)
}

function onGateChange(e: any) {
  approvedGateIds.value = (((e.detail.value as string[]) || [])).map(Number)
}

function selectAllGates() {
  approvedGateIds.value = (detail.value?.gates?.selectable ?? []).map((o) => o.id)
}

function resetToApplied() {
  approvedGateIds.value = [...(detail.value?.gates?.appliedGateIds ?? [])]
}

async function onPass() {
  if (detail.value?.gates && approvedGateIds.value.length === 0) {
    showToast('至少要保留一个门点')
    return
  }
  acting.value = true
  try {
    const result = await approvalPass(taskId.value, comment.value || undefined,
      detail.value?.gates ? approvedGateIds.value : undefined,
      detail.value?.qrRequested ? qrApproved.value : undefined)
    await alert(
      result.needSecondLevel
        ? '预约 ' + result.appointmentNo + ' 已确认接待，转保安复核（' + result.statusText + '）'
        : '预约 ' + result.appointmentNo + ' 已通过（' + result.statusText + '），' +
          (result.dispatchStarted ? '授权正在下发到设备' : '等待下发授权'),
      '已提交'
    )
    uni.navigateBack()
  } finally {
    acting.value = false
  }
}

async function onReject() {
  if (!comment.value.trim()) {
    showToast('驳回必须填写原因')
    return
  }
  acting.value = true
  try {
    await approvalReject(taskId.value, comment.value.trim())
    showToast('已驳回')
    uni.navigateBack()
  } finally {
    acting.value = false
  }
}

onLoad((options) => {
  if (!requireLogin('APP')) return
  taskId.value = Number((options as Record<string, string>)?.taskId ?? 0)
  if (!taskId.value) {
    showToast('缺少任务号')
    return
  }
  load()
})
</script>

<style scoped>
.opinion {
  width: 100%;
  height: 72px;
  font-size: 14px;
  background: #f7f8fa;
  border-radius: 8px;
  padding: 8px;
  box-sizing: border-box;
}
</style>
