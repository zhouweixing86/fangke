<template>
  <view class="page">
    <view class="card">
      <view class="card-title">访客信息</view>
      <view class="field">
        <text class="field-label">姓名</text>
        <input v-model="form.name" class="field-input" placeholder="请输入姓名" />
      </view>
      <view class="field">
        <text class="field-label">身份证号</text>
        <input v-model="form.idCard" class="field-input" maxlength="18" placeholder="18 位身份证号" />
      </view>
      <view class="field">
        <text class="field-label">手机号</text>
        <input v-model="form.phone" class="field-input" type="number" maxlength="11" placeholder="用于查看预约进度" />
      </view>
      <view class="field">
        <text class="field-label">单位</text>
        <input v-model="form.company" class="field-input" placeholder="选填" />
      </view>
    </view>

    <view class="card">
      <view class="card-title">走访信息</view>
      <view class="field">
        <text class="field-label">被访人手机</text>
        <input v-model="form.visitPhone" class="field-input" type="number" maxlength="11" placeholder="被访员工手机号" />
      </view>
      <view class="field">
        <text class="field-label">事由</text>
        <input v-model="form.purpose" class="field-input" placeholder="如：商务洽谈 / 送货" />
      </view>
      <view class="field" style="flex-direction: column; align-items: flex-start; gap: 6px">
        <text class="field-label">门点范围</text>
        <radio-group style="display: flex; gap: 16px; padding: 4px 0" @change="onScope">
          <label style="display: flex; align-items: center; gap: 4px">
            <radio value="1" :checked="gateScope === 1" />
            <text>指定门点</text>
          </label>
          <label style="display: flex; align-items: center; gap: 4px">
            <radio value="2" :checked="gateScope === 2" />
            <text>全部门点</text>
          </label>
        </radio-group>
      </view>
      <view v-if="gateScope === 1" class="field"
        style="flex-direction: column; align-items: flex-start; gap: 6px">
        <text class="field-label">选择门点（可多选）</text>
        <checkbox-group style="display: flex; flex-wrap: wrap; gap: 12px; padding: 4px 0"
          @change="onGates">
          <label v-for="g in gates" :key="g.id" style="display: flex; align-items: center; gap: 4px">
            <checkbox :value="String(g.id)" :checked="selectedGateIds.includes(g.id)" />
            <text>{{ g.gateName }}</text>
          </label>
        </checkbox-group>
        <text v-if="gates.length === 0" class="muted">暂无可选门点，请联系管理员</text>
      </view>
      <view v-if="gateScope === 2" class="muted" style="padding: 0 4px 8px">
        申请全部门点后，由被访员工审批时确认最终开放范围
      </view>
      <view class="field">
        <text class="field-label">到访日期</text>
        <picker mode="date" :value="form.date" :start="minDate" :end="maxDate" @change="onDate">
          <view class="field-value">{{ form.date || '请选择' }}</view>
        </picker>
      </view>
      <view class="field">
        <text class="field-label">开始时间</text>
        <picker mode="time" :value="form.start" @change="onStart">
          <view class="field-value">{{ form.start }}</view>
        </picker>
      </view>
      <view class="field">
        <text class="field-label">结束时间</text>
        <picker mode="time" :value="form.end" @change="onEnd">
          <view class="field-value">{{ form.end }}</view>
        </picker>
      </view>
    </view>

    <view class="card" style="display: flex; align-items: flex-start; gap: 8px">
      <checkbox :checked="agreed" @click="agreed = !agreed" />
      <text class="muted" @click="agreed = !agreed">
        我已阅读并同意《访客隐私告知》，同意为门禁核验留存身份信息与通行记录
      </text>
    </view>

    <button class="btn btn--primary btn--block" :loading="submitting" @click="submit">提交预约</button>
    <view class="muted" style="text-align: center; margin-top: 10px">
      提交后由被访员工审批，通过后可在「我的预约」出示通行码
    </view>

    <TabBar :items="tabbar" />
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { reactive, ref } from 'vue'
import TabBar from '@/components/TabBar.vue'
import { createAppointment, listAppGates, unreadCount } from '@/api'
import type { GateOption } from '@/api/types'
import { auth } from '@/store/auth'
import { alert, showToast } from '@/utils/ui'

const tabbar = ref([
  { text: '我的预约', path: '/pages/visitor/index' },
  { text: '访客预约', path: '/pages/visitor/new' },
  { text: '消息', path: '/pages/common/messages', badge: 0 },
  { text: '我的', path: '/pages/common/profile' }
])

const form = reactive({ name: '', idCard: '', phone: '', company: '', visitPhone: '', purpose: '', date: '', start: '09:00', end: '18:00' })
const agreed = ref(false)
const submitting = ref(false)

/** 门点：可指定多个，也可直接申请全部门点（审批时可增可减） */
const gates = ref<GateOption[]>([])
const gateScope = ref(1)
const selectedGateIds = ref<number[]>([])

function onScope(e: any) {
  gateScope.value = Number(e.detail.value) || 1
}

function onGates(e: any) {
  selectedGateIds.value = ((e.detail.value as string[]) || []).map((v) => Number(v))
}

function pad(n: number) {
  return String(n).padStart(2, '0')
}

const today = new Date()
const minDate = today.getFullYear() + '-' + pad(today.getMonth() + 1) + '-' + pad(today.getDate())
const future = new Date(today.getTime() + 30 * 86400000)
const maxDate = future.getFullYear() + '-' + pad(future.getMonth() + 1) + '-' + pad(future.getDate())

function onDate(e: any) {
  form.date = e.detail.value
}
function onStart(e: any) {
  form.start = e.detail.value
}
function onEnd(e: any) {
  form.end = e.detail.value
}

async function submit() {
  if (!form.name || !form.idCard || !form.phone) {
    showToast('请填写访客姓名、身份证号和手机号')
    return
  }
  if (!form.visitPhone || !form.purpose) {
    showToast('请填写被访人手机号和事由')
    return
  }
  if (!form.date) {
    showToast('请选择到访日期')
    return
  }
  if (gates.value.length === 0) {
    showToast('暂无可用门点，请联系管理员')
    return
  }
  if (gateScope.value === 1 && selectedGateIds.value.length === 0) {
    showToast('请选择至少一个门点，或直接申请全部门点')
    return
  }
  if (form.start >= form.end) {
    showToast('结束时间需晚于开始时间')
    return
  }
  if (!agreed.value) {
    showToast('请先同意隐私告知')
    return
  }
  submitting.value = true
  try {
    const result = await createAppointment({
      visitPhone: form.visitPhone,
      visitorType: '商务',
      purpose: form.purpose,
      startDate: form.date,
      startTime: form.start,
      endTime: form.end,
      gateId: selectedGateIds.value[0],
      gateScope: gateScope.value,
      gateIds: gateScope.value === 1 ? selectedGateIds.value : undefined,
      visitor: {
        name: form.name,
        idCard: form.idCard.toUpperCase(),
        phone: form.phone,
        company: form.company || undefined
      },
      consentVersion: 'v1.0'
    })
    await alert('预约单号：' + result.appointmentNo + '\n状态：' + result.statusText + '\n审批通过后可在「我的预约」查看通行码。', '预约已提交')
    uni.reLaunch({ url: auth.token ? '/pages/visitor/index' : '/pages/login/login' })
  } finally {
    submitting.value = false
  }
}

onShow(async () => {
  // 首次来访可在**未登录**状态提交预约（后端 POST /api/app/appointment 是公开接口），
  // 提交后再登录查看进度；这里不再强制跳登录页，否则新访客永远进不来。
  try {
    gates.value = await listAppGates()
    if (gates.value.length > 0 && selectedGateIds.value.length === 0) {
      selectedGateIds.value = [gates.value[0].id]
    }
  } catch {
    // 门点拉取失败不阻塞页面，提交时会提示
  }
  if (!auth.token) {
    return
  }
  try {
    tabbar.value[2].badge = (await unreadCount()).unread
  } catch {
    // 忽略
  }
})
</script>
