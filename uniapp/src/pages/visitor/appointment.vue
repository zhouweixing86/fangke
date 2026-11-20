<template>
  <view class="page page--plain">
    <view v-if="!detail" class="empty">加载中…</view>

    <template v-else>
      <view class="card">
        <view class="card-title">
          <text>预约信息</text>
          <text class="tag" :class="tagClass(detail.status)">{{ detail.statusText }}</text>
        </view>
        <view class="row"><text class="row-label">预约单号</text><text>{{ detail.appointmentNo }}</text></view>
        <view class="row"><text class="row-label">访客</text><text>{{ detail.visitorName }}</text></view>
        <view class="row"><text class="row-label">证件号</text><text>{{ detail.visitorIdCardMask }}</text></view>
        <view class="row"><text class="row-label">事由</text><text>{{ detail.purpose || '-' }}</text></view>
        <view class="row"><text class="row-label">到访日期</text><text>{{ detail.visitDate }}</text></view>
        <view class="row"><text class="row-label">到访门点</text><text>{{ gateText }}</text></view>
        <view class="row">
          <text class="row-label">时段</text>
          <text>{{ shortTime(detail.startTime) }} – {{ shortTime(detail.endTime) }}</text>
        </view>
        <view class="row"><text class="row-label">同行人</text><text>{{ detail.mateModeText }}</text></view>
      </view>

      <button v-if="canShowQr" class="btn btn--primary btn--block" :loading="qrLoading" @click="refreshQr">
        {{ qr ? '刷新通行码' : '生成通行码' }}
      </button>

      <view v-if="qr" class="card qr-box" style="margin-top: 10px">
        <view class="card-title" style="justify-content: center">通行码</view>
        <image v-if="qr.qrImage" class="qr-img" mode="widthFix" :src="'data:image/png;base64,' + qr.qrImage" />
        <view class="qr-code">{{ qr.code }}</view>
        <view class="muted" style="margin-top: 6px">
          <template v-if="remain > 0">剩余 {{ remain }} 秒，过期自动刷新</template>
          <template v-else>正在刷新…</template>
        </view>
        <view class="muted">保安扫码核验；60 秒内可重复使用</view>
      </view>

      <button v-if="detail.canCancel" class="btn btn--danger btn--block" style="margin-top: 10px"
        :loading="cancelling" @click="onCancel">撤回预约</button>
    </template>
  </view>
</template>

<script setup lang="ts">
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { appointmentDetail, cancelAppointment, issueQr } from '@/api'
import type { AppointmentView, QrIssueResult } from '@/api/types'
import { confirm, showToast } from '@/utils/ui'

const no = ref('')
const detail = ref<AppointmentView | null>(null)
const qr = ref<QrIssueResult | null>(null)
const qrLoading = ref(false)
const remain = ref(0)
const cancelling = ref(false)

/** 门点范围展示：申请「全部」时直接写全部门点，多选时列出名称 */
const gateText = computed(() => {
  const d = detail.value
  if (!d) return '-'
  if (d.gateScope === 2) return '全部门点'
  if (d.appliedGateNames && d.appliedGateNames.length > 0) {
    return d.appliedGateNames.join('、')
  }
  return d.gateName || '-'
})
let timer: ReturnType<typeof setInterval> | null = null

const canShowQr = computed(() => {
  const status = detail.value?.status ?? 0
  return status === 30 || status === 35 || status === 40
})

function shortTime(value: string) {
  return value ? value.slice(11, 16) : ''
}

function tagClass(status: number) {
  if (status === 35 || status === 40) return 'tag--ok'
  if (status === 20 || status === 25) return 'tag--warn'
  if (status === 60 || status === 70 || status === 90) return 'tag--danger'
  return 'tag--info'
}

async function load() {
  detail.value = await appointmentDetail(no.value)
}

async function refreshQr() {
  qrLoading.value = true
  try {
    qr.value = await issueQr(no.value)
    startCountdown(qr.value.ttlSeconds)
  } finally {
    qrLoading.value = false
  }
}

function startCountdown(seconds: number) {
  stopCountdown()
  remain.value = seconds
  timer = setInterval(() => {
    remain.value -= 1
    if (remain.value <= 0) {
      stopCountdown()
      // 过期自动换新码，访客无需手动点
      refreshQr().catch(() => undefined)
    }
  }, 1000)
}

function stopCountdown() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

async function onCancel() {
  if (!(await confirm('撤回后需要重新提交并审批，确认撤回？', '撤回预约'))) return
  cancelling.value = true
  try {
    await cancelAppointment(no.value, '访客在移动端撤回')
    showToast('已撤回')
    await load()
  } finally {
    cancelling.value = false
  }
}

onLoad((options) => {
  // 访客端没有登录页：token 由首页/列表静默 wx-login 获取，这里只校验单号
  no.value = (options as Record<string, string>)?.no ?? ''
  if (!no.value) {
    showToast('缺少预约单号')
    return
  }
  load()
})

onUnload(stopCountdown)
</script>
