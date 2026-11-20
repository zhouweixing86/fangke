<template>
  <view class="page">
    <view class="seg">
      <view class="seg-item" :class="{ active: mode === 'personal' }" @click="switchMode('personal')">个人预约</view>
      <view class="seg-item" :class="{ active: mode === 'group' }" @click="switchMode('group')">团体预约</view>
    </view>

    <view class="sheet">
      <view class="sheet-title">
        <text class="dot" />
        <text>{{ mode === 'group' ? '团体预约信息' : '来访人员信息' }}</text>
      </view>

      <picker :range="visitTypes" :value="typeIndex" @change="onType">
        <view class="row">
          <text class="label required">来访类型</text>
          <view class="row-value">
            <text :class="form.visitorType ? '' : 'ph'">{{ form.visitorType || '请选择' }}</text>
            <text class="arrow">›</text>
          </view>
        </view>
      </picker>

      <picker mode="date" :value="form.date" :start="minDate" :end="maxDate" @change="onDate">
        <view class="row">
          <text class="label required">来访日期</text>
          <view class="row-value">
            <text :class="form.date ? '' : 'ph'">{{ form.date || '请选择' }}</text>
            <text class="arrow">›</text>
          </view>
        </view>
      </picker>

      <view class="period">
        <view v-for="p in periods" :key="p.value" class="period-item" @click="form.period = p.value">
          <view class="radio" :class="{ checked: form.period === p.value }">
            <text v-if="form.period === p.value" class="tick">✓</text>
          </view>
          <text class="period-text">{{ p.label }}</text>
        </view>
      </view>

      <view v-if="mode === 'group'" class="row">
        <text class="label required">总人数</text>
        <input v-model="form.groupCount" class="input" type="number" placeholder="团体预约人数" />
      </view>

      <view class="row">
        <text class="label required">被访人手机</text>
        <input v-model="form.visitPhone" class="input" type="number" maxlength="11"
          placeholder="由被访人提供" @blur="lookupHost" />
      </view>

      <view class="row">
        <text class="label required">被访人</text>
        <view class="row-value">
          <text :class="hostName ? 'host' : 'ph'">{{ hostName || '由手机号自动匹配' }}</text>
        </view>
      </view>

      <view class="row">
        <text class="label required">姓名</text>
        <input v-model="form.name" class="input" :placeholder="mode === 'group' ? '预约负责人姓名' : '请填写您的姓名'" />
      </view>

      <view class="row">
        <text class="label required">手机号</text>
        <input v-model="form.phone" class="input" type="number" maxlength="11"
          :placeholder="mode === 'group' ? '预约负责人手机号' : '请填写您的手机号'" />
      </view>

      <view class="row">
        <text class="label required">身份证号</text>
        <input v-model="form.idCard" class="input" maxlength="18" placeholder="用于门禁刷证核验" />
      </view>

      <view class="row">
        <text class="label required">公司</text>
        <input v-model="form.company" class="input"
          :placeholder="mode === 'group' ? '预约负责人公司' : '请填写您的公司'" />
      </view>

      <view class="row">
        <text class="label">职务</text>
        <input v-model="form.position" class="input"
          :placeholder="mode === 'group' ? '预约负责人职务' : '请填写您的职务'" />
      </view>

      <view class="row">
        <text class="label">车牌号</text>
        <input v-model="form.plateNo" class="input" maxlength="16" placeholder="请填写乘坐车辆牌照" />
      </view>

      <picker :range="gateOptions" :value="gateIndex" @change="onGateScope">
        <view class="row">
          <text class="label">通行门点</text>
          <view class="row-value">
            <text>{{ gateOptions[gateIndex] }}</text>
            <text class="arrow">›</text>
          </view>
        </view>
      </picker>

      <view v-if="gateScope === 1" class="gate-list">
        <label v-for="g in gates" :key="g.id" class="gate-item">
          <checkbox :value="String(g.id)" :checked="selectedGateIds.includes(g.id)" @click="toggleGate(g.id)" />
          <text>{{ g.gateName }}</text>
        </label>
        <text v-if="!gates.length" class="ph">暂无可选门点，请联系管理员</text>
      </view>

      <view class="purpose">
        <text class="label">具体事由</text>
        <textarea v-model="form.purpose" class="textarea" maxlength="50" placeholder="请输入具体事由" />
        <text class="counter">{{ form.purpose.length }} / 50</text>
      </view>
    </view>

    <button class="submit" :loading="submitting" @click="submit">提交</button>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { computed, reactive, ref } from 'vue'
import { createAppointment, listAppGates, lookupEmployee } from '@/api'
import type { GateOption } from '@/api/types'
import { alert, showToast } from '@/utils/ui'

type Mode = 'personal' | 'group'

const mode = ref<Mode>('personal')
const visitTypes = ['商务', '施工', '送货']
const typeIndex = ref(0)
const periods = [
  { value: 1, label: '全天' },
  { value: 2, label: '上午' },
  { value: 3, label: '下午' }
]

const form = reactive({
  visitorType: '商务',
  date: '',
  period: 1,
  groupCount: '',
  visitPhone: '',
  name: '',
  phone: '',
  idCard: '',
  company: '',
  position: '',
  plateNo: '',
  purpose: ''
})

const hostName = ref('')
const submitting = ref(false)

const gates = ref<GateOption[]>([])
const gateOptions = ['全部门点', '指定门点']
const gateIndex = ref(0)
const gateScope = computed(() => (gateIndex.value === 0 ? 2 : 1))
const selectedGateIds = ref<number[]>([])

function pad(n: number) {
  return String(n).padStart(2, '0')
}

const today = new Date()
const minDate = today.getFullYear() + '-' + pad(today.getMonth() + 1) + '-' + pad(today.getDate())
const future = new Date(today.getTime() + 30 * 86400000)
const maxDate = future.getFullYear() + '-' + pad(future.getMonth() + 1) + '-' + pad(future.getDate())

function switchMode(next: Mode) {
  mode.value = next
  if (next === 'personal') {
    form.groupCount = ''
  }
}

function onType(e: any) {
  typeIndex.value = Number(e.detail.value) || 0
  form.visitorType = visitTypes[typeIndex.value]
}

function onDate(e: any) {
  form.date = e.detail.value
}

function onGateScope(e: any) {
  gateIndex.value = Number(e.detail.value) || 0
}

function toggleGate(id: number) {
  const index = selectedGateIds.value.indexOf(id)
  if (index >= 0) {
    selectedGateIds.value.splice(index, 1)
  } else {
    selectedGateIds.value.push(id)
  }
}

/** 被访人手机号 → 自动匹配被访人（失焦即查，避免每次输入都打接口） */
async function lookupHost() {
  const phone = form.visitPhone.trim()
  if (!/^1\d{10}$/.test(phone)) {
    hostName.value = ''
    return
  }
  try {
    const result = await lookupEmployee(phone)
    hostName.value = result.matched
      ? result.name + (result.deptName ? '（' + result.deptName + '）' : '')
      : ''
    if (!result.matched && result.message) {
      showToast(result.message)
    }
  } catch {
    hostName.value = ''
  }
}

async function submit() {
  if (!form.date) return showToast('请选择来访日期')
  if (!form.visitPhone) return showToast('请填写被访人手机号')
  if (mode.value === 'group' && !form.groupCount) return showToast('请填写团体预约人数')
  if (!form.name) return showToast('请填写姓名')
  if (!/^1\d{10}$/.test(form.phone.trim())) return showToast('请填写正确的手机号')
  if (!form.idCard.trim()) return showToast('请填写身份证号')
  if (!form.company.trim()) return showToast('请填写公司')
  if (gateScope.value === 1 && selectedGateIds.value.length === 0) {
    return showToast('请至少选择一个门点')
  }

  submitting.value = true
  try {
    const result = await createAppointment({
      visitPhone: form.visitPhone.trim(),
      visitorType: form.visitorType,
      purpose: form.purpose || undefined,
      plateNo: form.plateNo || undefined,
      company: form.company.trim(),
      position: form.position || undefined,
      period: form.period,
      groupCount: mode.value === 'group' ? Number(form.groupCount) : undefined,
      startDate: form.date,
      gateScope: gateScope.value,
      gateId: gateScope.value === 1 ? selectedGateIds.value[0] : undefined,
      gateIds: gateScope.value === 1 ? selectedGateIds.value : undefined,
      visitor: {
        name: form.name.trim(),
        idCard: form.idCard.trim().toUpperCase(),
        phone: form.phone.trim(),
        company: form.company.trim(),
        position: form.position || undefined
      },
      consentVersion: 'v1.0'
    })
    await alert('预约单号：' + result.appointmentNo + '\n状态：' + result.statusText, '提交成功')
    uni.redirectTo({ url: '/pages/visitor/index' })
  } finally {
    submitting.value = false
  }
}

onShow(async () => {
  try {
    gates.value = await listAppGates()
    if (gates.value.length === 1) {
      selectedGateIds.value = [gates.value[0].id]
    }
  } catch {
    // 门点拉取失败不阻塞页面，提交时再提示
  }
})
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: #eef4fb;
  padding-bottom: 40rpx;
}

.seg {
  display: flex;
  gap: 16rpx;
  padding: 20rpx 24rpx;
}

.seg-item {
  flex: 1;
  height: 76rpx;
  line-height: 76rpx;
  text-align: center;
  border-radius: 12rpx;
  background: #ffffff;
  color: #4a5160;
  font-size: 30rpx;
}

.seg-item.active {
  background: #1565e8;
  color: #ffffff;
  font-weight: 500;
}

.sheet {
  margin: 0 24rpx;
  background: #ffffff;
  border-radius: 16rpx;
  padding: 8rpx 28rpx 20rpx;
}

.sheet-title {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 26rpx 0 10rpx;
  font-size: 30rpx;
  font-weight: 600;
  color: #1f2329;
}

.dot {
  width: 18rpx;
  height: 18rpx;
  border-radius: 50%;
  border: 4rpx solid #1565e8;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 96rpx;
  border-bottom: 1rpx solid #f0f2f5;
}

.label {
  font-size: 30rpx;
  color: #1f2329;
  flex-shrink: 0;
}

.label.required::after {
  content: ' *';
  color: #f5222d;
}

.row-value {
  display: flex;
  align-items: center;
  gap: 8rpx;
  font-size: 30rpx;
  color: #1f2329;
}

.arrow {
  color: #b7bcc4;
  font-size: 34rpx;
}

.ph {
  color: #b7bcc4;
  font-size: 28rpx;
}

.host {
  color: #1565e8;
}

.input {
  flex: 1;
  text-align: right;
  font-size: 30rpx;
  color: #1f2329;
}

.period {
  display: flex;
  align-items: center;
  gap: 48rpx;
  padding: 26rpx 0;
  border-bottom: 1rpx solid #f0f2f5;
}

.period-item {
  display: flex;
  align-items: center;
  gap: 12rpx;
}

.radio {
  width: 38rpx;
  height: 38rpx;
  border-radius: 50%;
  border: 2rpx solid #cfd4dc;
  display: flex;
  align-items: center;
  justify-content: center;
}

.radio.checked {
  background: #1565e8;
  border-color: #1565e8;
}

.tick {
  color: #ffffff;
  font-size: 24rpx;
  line-height: 1;
}

.period-text {
  font-size: 30rpx;
  color: #1f2329;
}

.gate-list {
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx;
  padding: 20rpx 0;
  border-bottom: 1rpx solid #f0f2f5;
}

.gate-item {
  display: flex;
  align-items: center;
  gap: 8rpx;
  font-size: 28rpx;
  color: #1f2329;
}

.purpose {
  padding: 26rpx 0 6rpx;
}

.textarea {
  width: 100%;
  height: 150rpx;
  margin-top: 14rpx;
  font-size: 28rpx;
  color: #1f2329;
}

.counter {
  display: block;
  text-align: right;
  font-size: 24rpx;
  color: #b7bcc4;
}

.submit {
  margin: 30rpx 24rpx 0;
  height: 92rpx;
  line-height: 92rpx;
  border-radius: 10rpx;
  background: #1565e8;
  color: #ffffff;
  font-size: 32rpx;
}

.submit::after {
  border: none;
}
</style>
