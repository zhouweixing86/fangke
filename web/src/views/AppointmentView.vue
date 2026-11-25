<template>
  <div class="page-card">
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="单号 / 姓名 / 证件号后四位" clearable style="width: 240px"
        @keyup.enter="search" />
      <el-select v-model="query.status" placeholder="状态" clearable style="width: 150px">
        <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-select v-model="query.areaId" placeholder="来访区域" clearable style="width: 150px">
        <el-option v-for="a in areaOptions" :key="a.id" :label="a.name" :value="a.id" />
      </el-select>
      <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="到访起"
        end-placeholder="到访止" style="width: 260px" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="appointmentNo" label="预约单号" width="170" />
      <el-table-column prop="visitorName" label="访客" width="110" />
      <el-table-column prop="idCardMask" label="证件号" width="180" />
      <el-table-column prop="visitorType" label="类型" width="80" />
      <el-table-column prop="purpose" label="事由" min-width="130" />
      <el-table-column label="区域" width="120">
        <template #default="{ row }">{{ row.areaName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="startTime" label="开始" width="150" />
      <el-table-column prop="endTime" label="结束" width="150" />
      <el-table-column prop="sourceText" label="来源" width="110" />
      <el-table-column label="申请门点" width="160">
        <template #default="{ row }">
          <el-tag v-if="row.gateScope === 2" size="small" type="warning">全部门点</el-tag>
          <span v-else>{{ row.appliedGates || row.gateName || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="批准门点" width="160">
        <template #default="{ row }">{{ row.approvedGates || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetail(row)">详情</el-button>
          <el-button v-if="row.status === 30" link type="warning" @click="redispatch(row)">重下</el-button>
          <el-button v-if="row.canRevoke" link type="danger" @click="revoke(row)">撤销授权</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
        layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-dialog v-model="detailVisible" title="预约详情" width="560px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="预约单号">{{ detail?.appointmentNo }}</el-descriptions-item>
        <el-descriptions-item label="访客">{{ detail?.visitorName }}（{{ detail?.visitorType }}）</el-descriptions-item>
        <el-descriptions-item label="证件号">{{ detail?.idCardMask }}</el-descriptions-item>
        <el-descriptions-item label="事由">{{ detail?.purpose || '-' }}</el-descriptions-item>
        <el-descriptions-item label="来访区域">{{ detail?.areaName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="到访时间">{{ detail?.startTime }} ~ {{ detail?.endTime }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusType(detail?.status ?? 0)" size="small">{{ detail?.statusText }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="来源">{{ detail?.sourceText }}</el-descriptions-item>
        <el-descriptions-item label="驳回原因">{{ detail?.rejectReason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="授权状态">
          {{ grantText(detail?.grantStatus ?? -1) }}
        </el-descriptions-item>
        <el-descriptions-item label="提交时间">{{ detail?.createdAt }}</el-descriptions-item>
      </el-descriptions>

      <div class="trace">
        <div class="trace-title">审批轨迹</div>
        <el-timeline v-if="detail?.actions && detail.actions.length">
          <el-timeline-item v-for="(a, i) in detail.actions" :key="i" :timestamp="a.at"
            :type="a.action === 1 ? 'success' : a.action === 2 ? 'danger' : 'primary'">
            <div class="trace-line">
              <b>{{ a.operatorName }}</b>
              <el-tag size="small" type="info">{{ a.operatorTypeText }}</el-tag>
              <el-tag size="small" :type="a.action === 1 ? 'success' : a.action === 2 ? 'danger' : 'info'">
                {{ a.actionText }}
              </el-tag>
              <span v-if="a.gates" class="trace-gate">门点：{{ a.gates }}</span>
            </div>
            <div v-if="a.comment" class="trace-comment">意见：{{ a.comment }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-text v-else type="info" size="small">暂无审批记录</el-text>
      </div>

      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button v-if="detail?.canRevoke" type="warning" @click="revokeQr(detail!)">撤销通行码</el-button>
        <el-button type="primary" @click="openQr(detail!)">生成通行码</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="qrVisible" title="动态通行码" width="420px" @closed="stopCountdown">
      <div class="qr-box">
        <img v-if="qrImage" :src="qrImage" alt="通行码" class="qr-img" />
        <el-empty v-else description="暂无可展示的码" :image-size="80" />
        <div class="qr-tip">
          <template v-if="qrRemain > 0">
            剩余 <b>{{ qrRemain }}</b> 秒有效，期内可重复核验
          </template>
          <template v-else>
            <el-text type="danger">已过期，请点击「刷新」重新生成</el-text>
          </template>
        </div>
        <el-input :model-value="qrCode" readonly style="margin-top: 10px">
          <template #append>
            <el-button @click="copyQr">复制</el-button>
          </template>
        </el-input>
      </div>
      <el-alert type="info" :closable="false" show-icon style="margin-top: 12px"
        title="60 秒内可重复核验（BR-V-020）；过期自动失效，刷新即可重新签发；撤销后立即作废。" />
      <template #footer>
        <el-button @click="qrVisible = false">关闭</el-button>
        <el-button type="primary" :loading="qrLoading" @click="refreshQr">刷新</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import QRCode from 'qrcode'
import {
  dispatchAppointment,
  getAppointment,
  issueAppointmentQr,
  listAppointments,
  revokeAppointmentQr,
  revokeGrant,
  visitDictOptions
} from '@/api'
import type { AppointmentRow, VisitDict } from '@/api/types'

const rows = ref<AppointmentRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const dateRange = ref<[string, string] | null>(null)
const query = reactive<{ keyword: string; status: number | undefined; areaId: number | undefined }>({
  keyword: '',
  status: undefined,
  areaId: undefined
})
const areaOptions = ref<VisitDict[]>([])

const detailVisible = ref(false)
const detail = ref<AppointmentRow | null>(null)

const statusOptions = [
  { label: '待审批', value: 20 },
  { label: '待审核', value: 25 },
  { label: '已通过', value: 30 },
  { label: '可通行', value: 35 },
  { label: '已签到', value: 40 },
  { label: '已签离', value: 50 },
  { label: '已驳回', value: 60 },
  { label: '已过期', value: 70 },
  { label: '已撤回', value: 80 },
  { label: '已撤销', value: 90 }
]

function statusType(status: number) {
  if (status === 35 || status === 40) return 'success'
  if (status === 60 || status === 70 || status === 90) return 'danger'
  if (status === 30 || status === 25 || status === 20) return 'warning'
  return 'info'
}

function grantText(status: number) {
  if (status === 1) return '生效'
  if (status === 2) return '待下发'
  if (status === 3) return '下发中'
  if (status === 4) return '已撤销'
  if (status === 5) return '已过期'
  return '无授权'
}

async function load() {
  loading.value = true
  try {
    const result = await listAppointments({
      keyword: query.keyword || undefined,
      status: query.status,
      areaId: query.areaId,
      from: dateRange.value?.[0],
      to: dateRange.value?.[1],
      page: page.value,
      size: size.value
    })
    rows.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

async function showDetail(row: AppointmentRow) {
  detail.value = await getAppointment(row.id)
  detailVisible.value = true
}

async function redispatch(row: AppointmentRow) {
  await ElMessageBox.confirm(`重新下发 ${row.appointmentNo} 到设备？`, '手动下发', { type: 'warning' })
  const result = await dispatchAppointment(row.appointmentNo)
  ElMessageBox.alert(
    `${result.message}\n设备 ${result.total} 台，成功 ${result.success}，失败 ${result.fail}\n批次号：${result.batchNo}`,
    '下发结果',
    { type: result.fail > 0 ? 'warning' : 'success' }
  )
  await load()
}

async function revoke(row: AppointmentRow) {
  await ElMessageBox.confirm(
    `撤销 ${row.appointmentNo} 的授权：将从设备名单删除该访客，并把预约单置为已撤销。确认？`,
    '撤销授权',
    { type: 'warning' }
  )
  const result = await revokeGrant(row.appointmentNo)
  ElMessage.success(result.message)
  await load()
}

const qrVisible = ref(false)
const qrLoading = ref(false)
const qrCode = ref('')
const qrImage = ref('')
const qrRemain = ref(0)
const qrAppointmentNo = ref('')
let qrTimer: ReturnType<typeof setInterval> | null = null

async function openQr(row: AppointmentRow) {
  qrAppointmentNo.value = row.appointmentNo
  qrVisible.value = true
  await refreshQr()
}

async function refreshQr() {
  if (!qrAppointmentNo.value) return
  qrLoading.value = true
  try {
    const result = await issueAppointmentQr(qrAppointmentNo.value)
    qrCode.value = result.code
    qrImage.value = await QRCode.toDataURL(result.code, { width: 260, margin: 1 })
    startCountdown(result.ttlSeconds)
  } finally {
    qrLoading.value = false
  }
}

function startCountdown(seconds: number) {
  stopCountdown()
  qrRemain.value = seconds
  qrTimer = setInterval(() => {
    qrRemain.value -= 1
    if (qrRemain.value <= 0) {
      stopCountdown()
    }
  }, 1000)
}

function stopCountdown() {
  if (qrTimer) {
    clearInterval(qrTimer)
    qrTimer = null
  }
}

async function copyQr() {
  if (!qrCode.value) return
  try {
    await navigator.clipboard.writeText(qrCode.value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('浏览器不允许自动复制，请手动选择文本')
  }
}

async function revokeQr(row: AppointmentRow) {
  await ElMessageBox.confirm(
    `撤销 ${row.appointmentNo} 的所有有效通行码？访客手里的码会立即失效。`,
    '撤销通行码',
    { type: 'warning' }
  )
  const result = await revokeAppointmentQr(row.appointmentNo)
  ElMessage.success(`已撤销 ${result.revoked} 张有效的码`)
}

async function loadAreas() {
  try {
    const options = await visitDictOptions()
    areaOptions.value = options.AREA ?? []
  } catch {
    // 区域字典取不到不影响预约列表本身
  }
}

onMounted(async () => {
  await loadAreas()
  await load()
})
onBeforeUnmount(stopCountdown)
</script>

<style scoped>
.qr-box {
  text-align: center;
}

.qr-img {
  width: 260px;
  height: 260px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.qr-tip {
  margin-top: 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.trace {
  margin-top: 16px;
}

.trace-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 10px;
}

.trace-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.trace-gate {
  color: var(--el-text-color-secondary);
}

.trace-comment {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
</style>
