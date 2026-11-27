<template>
  <view class="page">
    <view class="sheet">
      <view class="sheet-title">
        <text class="dot" />
        <text>来访人员信息</text>
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

      <view class="row">
        <text class="label">被访人手机</text>
        <input v-model="form.visitPhone" class="input" type="number" maxlength="11"
          placeholder="选填，由被访人提供" @blur="lookupHost" />
      </view>

      <view class="row">
        <text class="label">被访人</text>
        <view class="row-value">
          <text :class="hostName ? 'host' : 'ph'">{{ hostName || '选填，由手机号自动匹配' }}</text>
        </view>
      </view>

      <view class="row">
        <text class="label required">姓名</text>
        <input v-model="form.name" class="input" placeholder="请填写您的姓名" />
      </view>

      <view class="row">
        <text class="label required">手机号</text>
        <input v-model="form.phone" class="input" type="number" maxlength="11" placeholder="请填写您的手机号" />
      </view>

      <view class="row">
        <text class="label required">身份证号</text>
        <input v-model="form.idCard" class="input" maxlength="18" placeholder="必填，到闸机需刷身份证进门" />
      </view>

      <view class="row">
        <text class="label required">公司</text>
        <input v-model="form.company" class="input" placeholder="请填写您的公司" />
      </view>

      <view class="row">
        <text class="label">职务</text>
        <input v-model="form.position" class="input" placeholder="请填写您的职务" />
      </view>

      <view class="row">
        <text class="label">车牌号</text>
        <input v-model="form.plateNo" class="input" maxlength="16" placeholder="请填写乘坐车辆牌照" />
      </view>

      <!-- 只有多个门点时才让访客选；单个门点直接默认那一个 -->
      <template v-if="gates.length > 1">
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
        </view>
      </template>

      <!-- 来访区域：基础字典 AREA，访客到园区哪个区域（C-75） -->
      <picker v-if="areaOptions.length" :range="areaOptions" :value="areaIndex < 0 ? 0 : areaIndex" @change="onArea">
        <view class="row">
          <text class="label">来访区域</text>
          <view class="row-value">
            <text :class="form.areaId ? '' : 'ph'">
              {{ form.areaId ? areaOptions[areaIndex] : '请选择' }}
            </text>
            <text class="arrow">›</text>
          </view>
        </view>
      </picker>

      <view class="purpose">
        <text class="label">具体事由</text>
        <textarea v-model="form.purpose" class="textarea" maxlength="50" placeholder="请输入具体事由" />
        <text class="counter">{{ form.purpose.length }} / 50</text>
      </view>
    </view>

    <!-- 随行人员：选人数后展开多行，只填姓名 + 手机号（跟着主访客一起进，不单独下发） -->
    <view class="sheet">
      <view class="sheet-title">
        <text class="dot" />
        <text>随行人员</text>
      </view>

      <view class="row">
        <text class="label">随行人数</text>
        <input v-model="mateTotal" class="input" type="number" maxlength="2"
          placeholder="不含本人，如 2" @blur="syncMates" />
      </view>

      <view v-for="(mate, index) in mates" :key="index" class="mate">
        <text class="mate-index">随行人员 {{ index + 1 }}</text>
        <view class="mate-row">
          <text class="mate-label required">姓名</text>
          <input v-model="mate.name" class="input" placeholder="请填写姓名" />
        </view>
        <view class="mate-row">
          <text class="mate-label">手机号</text>
          <input v-model="mate.phone" class="input" type="number" maxlength="11" placeholder="选填" />
        </view>
      </view>

      <view v-if="mates.length === 0" class="ph tips">没有随行人员可不填</view>
      <view v-else class="ph tips">随行人员跟随本人一起进出，无需单独刷证；二维码在「我的预约」里出示</view>
    </view>

    <button class="submit" :loading="submitting" @click="submit">提交</button>
  </view>
</template>

<script setup lang="ts">
import { onShareAppMessage, onShow } from '@dcloudio/uni-app'
import { computed, reactive, ref } from 'vue'
import { createAppointment, appDictOptions, listAppGates, lookupEmployee } from '@/api'
import type { GateOption, VisitDictOption } from '@/api/types'
import { visitorOpenid } from '@/utils/visitor'
import { alert, showToast } from '@/utils/ui'

interface Mate {
  name: string
  phone: string
}

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
  visitPhone: '',
  name: '',
  phone: '',
  idCard: '',
  company: '',
  position: '',
  plateNo: '',
  purpose: '',
  areaId: undefined as number | undefined
})

const hostName = ref('')
const submitting = ref(false)
const mates = ref<Mate[]>([])
const mateTotal = ref('')

const gates = ref<GateOption[]>([])
const gateOptions = ['全部门点', '指定门点']
const gateIndex = ref(0)
const gateScope = computed(() => (gateIndex.value === 0 ? 2 : 1))
const selectedGateIds = ref<number[]>([])

const areas = ref<VisitDictOption[]>([])
const areaOptions = computed(() => areas.value.map((a) => a.name))
const areaIndex = ref(-1)

function pad(n: number) {
  return String(n).padStart(2, '0')
}

const today = new Date()
const minDate = today.getFullYear() + '-' + pad(today.getMonth() + 1) + '-' + pad(today.getDate())
const future = new Date(today.getTime() + 30 * 86400000)
const maxDate = future.getFullYear() + '-' + pad(future.getMonth() + 1) + '-' + pad(future.getDate())

/** 随行人数 → 自动补齐/裁剪行 */
function syncMates() {
  const total = Math.max(0, Math.min(10, Number(mateTotal.value) || 0))
  mateTotal.value = total ? String(total) : ''
  const next: Mate[] = []
  for (let i = 0; i < total; i++) {
    next.push(mates.value[i] ?? { name: '', phone: '' })
  }
  mates.value = next
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

function onArea(e: any) {
  areaIndex.value = Number(e.detail.value) || 0
  form.areaId = areas.value[areaIndex.value]?.id
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
  // 被访人手机改为选填（C-83：不再由被访人审批）；填了才校验格式
  if (form.visitPhone && !/^1\d{10}$/.test(form.visitPhone.trim())) {
    return showToast('被访人手机号格式不正确')
  }
  if (!form.name) return showToast('请填写姓名')
  if (!/^1\d{10}$/.test(form.phone.trim())) return showToast('请填写正确的手机号')
  if (!form.company.trim()) return showToast('请填写公司')
  // 身份证必填：访客只能刷身份证进门（不支持刷脸），没有证件号就到不了闸机
  const idCard = form.idCard.trim().toUpperCase()
  if (!idCard) return showToast('请填写身份证号（到闸机需刷身份证进门）')
  if (!/^\d{17}[0-9X]$/.test(idCard)) return showToast('身份证号需为 18 位')
  if (mates.value.some((m) => !m.name.trim())) return showToast('请填写随行人员姓名')
  const badMatePhone = mates.value.find((m) => m.phone.trim() && !/^1\d{10}$/.test(m.phone.trim()))
  if (badMatePhone) return showToast('随行人员「' + badMatePhone.name + '」手机号格式不正确')
  if (gates.value.length > 1 && gateScope.value === 1 && selectedGateIds.value.length === 0) {
    return showToast('请至少选择一个门点')
  }
  if (areaOptions.value.length > 0 && !form.areaId) return showToast('请选择来访区域')

  const useGates = gates.value.length > 1 && gateScope.value === 1
  submitting.value = true
  try {
    // 首次预约要把 openid 带上，后端据此绑定访客档案，否则「我的预约」查不到自己的单
    const openid = await visitorOpenid()
    const result = await createAppointment({
      visitPhone: form.visitPhone.trim() || undefined,
      visitorType: form.visitorType,
      purpose: form.purpose || undefined,
      areaId: form.areaId,
      plateNo: form.plateNo || undefined,
      company: form.company.trim(),
      position: form.position || undefined,
      period: form.period,
      groupCount: mates.value.length > 0 ? mates.value.length + 1 : undefined,
      startDate: form.date,
      gateScope: useGates ? 1 : gates.value.length === 1 ? 1 : 2,
      gateId: useGates ? selectedGateIds.value[0] : gates.value[0]?.id,
      gateIds: useGates ? selectedGateIds.value : undefined,
      visitor: {
        name: form.name.trim(),
        idCard,
        phone: form.phone.trim(),
        company: form.company.trim(),
        position: form.position || undefined
      },
      mates: mates.value.length > 0
        ? mates.value.map((m) => ({
            name: m.name.trim(),
            phone: m.phone.trim() || undefined
          }))
        : undefined,
      wxOpenid: openid || undefined,
      consentVersion: 'v1.0'
    })
    await alert('预约单号：' + result.appointmentNo + '\n状态：' + result.statusText, '提交成功')
    uni.redirectTo({ url: '/pages/visitor/index' })
  } finally {
    submitting.value = false
  }
}

onShareAppMessage(() => ({
  title: '访客预约登记',
  path: '/pages/visitor/new'
}))

onShow(async () => {
  try {
    gates.value = await listAppGates()
    // 只有一个门点时直接默认它，界面上不出现选门控件
    if (gates.value.length === 1) {
      selectedGateIds.value = [gates.value[0].id]
    }
  } catch {
    // 门点拉取失败不阻塞页面，提交时再提示
  }
  try {
    const dict = await appDictOptions()
    areas.value = dict.AREA ?? []
  } catch {
    // 区域字典拉不到就不显示该控件（后端 areaId 可选，不阻塞提交）
  }
})
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: #eef4fb;
  padding-bottom: 60rpx;
}

.sheet {
  margin: 0 24rpx 20rpx;
  background: #ffffff;
  border-radius: 16rpx;
  padding: 8rpx 30rpx 24rpx;
}

.sheet-title {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 28rpx 0 12rpx;
  font-size: 34rpx;
  font-weight: 600;
  color: #1f2329;
}

.dot {
  width: 20rpx;
  height: 20rpx;
  border-radius: 50%;
  border: 4rpx solid #1565e8;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 104rpx;
  border-bottom: 1rpx solid #f0f2f5;
}

.label {
  font-size: 34rpx;
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
  font-size: 34rpx;
  color: #1f2329;
}

.arrow {
  color: #b7bcc4;
  font-size: 38rpx;
}

.ph {
  color: #b7bcc4;
  font-size: 32rpx;
}

.host {
  color: #1565e8;
}

.input {
  flex: 1;
  text-align: right;
  font-size: 34rpx;
  color: #1f2329;
}

.period {
  display: flex;
  align-items: center;
  gap: 52rpx;
  padding: 30rpx 0;
  border-bottom: 1rpx solid #f0f2f5;
}

.period-item {
  display: flex;
  align-items: center;
  gap: 14rpx;
}

.radio {
  width: 44rpx;
  height: 44rpx;
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
  font-size: 26rpx;
  line-height: 1;
}

.period-text {
  font-size: 34rpx;
  color: #1f2329;
}

.gate-list {
  display: flex;
  flex-wrap: wrap;
  gap: 24rpx;
  padding: 24rpx 0;
  border-bottom: 1rpx solid #f0f2f5;
}

.gate-item {
  display: flex;
  align-items: center;
  gap: 8rpx;
  font-size: 32rpx;
  color: #1f2329;
}

.purpose {
  padding: 30rpx 0 6rpx;
}

.textarea {
  width: 100%;
  height: 170rpx;
  margin-top: 16rpx;
  font-size: 32rpx;
  color: #1f2329;
}

.counter {
  display: block;
  text-align: right;
  font-size: 26rpx;
  color: #b7bcc4;
}

.mate {
  padding: 20rpx 0;
  border-bottom: 1rpx solid #f0f2f5;
}

.mate-index {
  display: block;
  font-size: 30rpx;
  color: #1565e8;
  margin-bottom: 6rpx;
}

.mate-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 92rpx;
}

.mate-label {
  font-size: 32rpx;
  color: #4a5160;
}

.tips {
  padding: 24rpx 0 8rpx;
  text-align: center;
}

/* 提交不做通栏 bar：圆角按钮居中，视觉更干净 */
.submit {
  width: 320rpx;
  height: 88rpx;
  line-height: 88rpx;
  margin: 40rpx auto 0;
  border-radius: 44rpx;
  background: #1565e8;
  color: #ffffff;
  font-size: 34rpx;
}

.submit::after {
  border: none;
}
</style>
