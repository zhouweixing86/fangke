<template>
  <div>
    <div class="stat-grid">
      <div class="stat-item">
        <div class="stat-value">{{ dashboard.onsite }}</div>
        <div class="stat-label">当前在访</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ dashboard.pendingApproval }}</div>
        <div class="stat-label">待审批</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ dashboard.todayCheckins }}</div>
        <div class="stat-label">今日到访</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ dashboard.devicesOnline }}/{{ dashboard.devicesTotal }}</div>
        <div class="stat-label">设备在线（{{ dashboard.deviceStatusText }}）</div>
      </div>
    </div>

    <div class="page-card">
      <el-tabs v-model="tab">
        <el-tab-pane label="在访人员" name="onsite">
          <div class="toolbar">
            <el-button :icon="Refresh" @click="loadOnsite">刷新</el-button>
            <el-button type="primary" @click="openVerify">扫码核验</el-button>
            <el-select v-model="gateId" placeholder="开门门点" style="width: 160px">
              <el-option v-for="g in gates" :key="g.id" :value="g.id" :label="g.gateName" />
            </el-select>
            <span class="spacer" />
            <span class="text-muted">离场请使用「签离」或让访客在出口设备刷证</span>
          </div>
          <el-table v-loading="onsiteLoading" :data="onsiteList" border stripe>
            <el-table-column prop="appointmentNo" label="预约单号" width="170" />
            <el-table-column prop="visitorName" label="访客" width="120" />
            <el-table-column prop="idCardMask" label="证件号" width="180" />
            <el-table-column prop="purpose" label="事由" min-width="140" />
            <el-table-column prop="checkinAt" label="入场时间" width="150" />
            <el-table-column prop="endTime" label="预约结束" width="150" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.overdue" type="danger" size="small">已超时</el-tag>
                <el-tag v-else type="success" size="small">在访</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openDoor(row)">开门</el-button>
                <el-button link type="warning" @click="checkout(row)">签离</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="pager">
            <el-pagination v-model:current-page="onsitePage" v-model:page-size="onsiteSize"
              :total="onsiteTotal" layout="total, prev, pager, next" @current-change="loadOnsite" />
          </div>
        </el-tab-pane>

        <el-tab-pane label="待审批（只读）" name="pending">
          <el-alert type="info" :closable="false" show-icon
            title="预约提交后进入待审核，由保安/管理员审核；审核通过即下发门禁授权"
            style="margin-bottom: 12px" />
          <el-table v-loading="pendingLoading" :data="pendingList" border stripe>
            <el-table-column prop="appointmentNo" label="预约单号" width="170" />
            <el-table-column prop="visitorName" label="访客" width="120" />
            <el-table-column prop="idCardMask" label="证件号" width="180" />
            <el-table-column prop="purpose" label="事由" min-width="140" />
            <el-table-column prop="sourceText" label="来源" width="120" />
            <el-table-column prop="startTime" label="预约开始" width="150" />
            <el-table-column prop="createdAt" label="提交时间" width="150" />
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="remind(row)">催办</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="pager">
            <el-pagination v-model:current-page="pendingPage" v-model:page-size="pendingSize"
              :total="pendingTotal" layout="total, prev, pager, next" @current-change="loadPending" />
          </div>
        </el-tab-pane>

        <el-tab-pane name="review">
          <template #label>
            待我审核
            <el-badge v-if="reviewCount > 0" :value="reviewCount" type="danger"
              style="margin-left: 6px; vertical-align: top" />
          </template>
          <el-alert type="warning" :closable="false" show-icon
            title="审核通过后立即下发门禁授权；发现异常请拒绝放行"
            style="margin-bottom: 12px" />
          <el-table v-loading="reviewLoading" :data="reviewList" border stripe>
            <el-table-column prop="appointmentNo" label="预约单号" width="170" />
            <el-table-column label="当前节点" width="120">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.nodeName || `第 ${row.level} 级` }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="visitorName" label="访客" width="120" />
            <el-table-column prop="idCardMask" label="证件号" width="180" />
            <el-table-column prop="purpose" label="事由" min-width="140" />
            <el-table-column prop="startTime" label="预约开始" width="150" />
            <el-table-column prop="endTime" label="预约结束" width="150" />
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openReview(row)">审核</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="pager">
            <el-pagination v-model:current-page="reviewPage" v-model:page-size="reviewSize"
              :total="reviewTotal" layout="total, prev, pager, next" @current-change="loadReview" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
    <el-dialog v-model="verifyVisible" title="扫码核验通行码" width="520px">
      <el-input v-model="verifyCode" type="textarea" :rows="3" spellcheck="false"
        placeholder="用扫码枪/PDA 扫描访客手机上的码，或手工粘贴二维码内容（RV1. 开头）" />
      <div style="margin-top: 12px">
        <el-button type="primary" :loading="verifying" @click="doVerify">核验</el-button>
        <span class="text-muted" style="margin-left: 8px">码 60 秒有效，期内可重复核验</span>
      </div>

      <div v-if="verifyResult" style="margin-top: 16px">
        <el-alert :type="verifyResult.allowed ? 'success' : 'error'" :closable="false" show-icon
          :title="verifyResult.allowed ? '允许通行' : '拒绝通行'" :description="verifyResult.reason" />
        <el-descriptions v-if="verifyResult.allowed" :column="1" border size="small"
          style="margin-top: 12px">
          <el-descriptions-item label="预约单号">{{ verifyResult.appointmentNo }}</el-descriptions-item>
          <el-descriptions-item label="访客">
            {{ verifyResult.visitorName }}（{{ verifyResult.visitorType || '-' }}）
          </el-descriptions-item>
          <el-descriptions-item label="证件号">{{ verifyResult.idCardMask }}</el-descriptions-item>
          <el-descriptions-item label="被访人">{{ verifyResult.employeeName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="事由">{{ verifyResult.purpose || '-' }}</el-descriptions-item>
          <el-descriptions-item label="授权时段">
            {{ verifyResult.validFrom }} ~ {{ verifyResult.validTo }}
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </el-dialog>

    <el-dialog v-model="reviewVisible" title="预约审核" width="640px">
      <template v-if="reviewDetail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="预约单号">{{ reviewDetail.appointmentNo }}</el-descriptions-item>
          <el-descriptions-item label="访客">
            {{ reviewDetail.visitorName }}（{{ reviewDetail.visitorType || '-' }}）
          </el-descriptions-item>
          <el-descriptions-item label="证件号">{{ reviewDetail.idCardMask }}</el-descriptions-item>
          <el-descriptions-item label="事由">{{ reviewDetail.purpose || '-' }}</el-descriptions-item>
          <el-descriptions-item label="时段" :span="2">
            {{ reviewDetail.startTime }} ~ {{ reviewDetail.endTime }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="text-muted" style="margin: 14px 0 6px">
          <el-tag size="small" type="warning" effect="plain" style="margin-right: 6px">
            {{ reviewDetail.nodeName || `第 ${reviewDetail.level} 级` }}
          </el-tag>
          开放门点（被访人已确认：{{ appliedText }}）
        </div>
        <el-checkbox-group v-if="reviewDetail.allowEditGates !== false" v-model="reviewGateIds">
          <el-checkbox v-for="g in reviewDetail.gates?.selectable || []" :key="g.id" :value="g.id">
            {{ g.name }}
          </el-checkbox>
        </el-checkbox-group>
        <div v-else class="text-muted" style="font-size: 12px">
          本节点不允许调整门点，沿用上一级的批准结果
        </div>

        <el-input v-model="reviewComment" type="textarea" :rows="2" style="margin-top: 12px"
          placeholder="通过可不填；拒绝放行必须填原因（如：人证不符）" />

        <div v-if="reviewDetail.qrRequested && reviewDetail.allowQr !== false" class="qr-review">
          <div class="text-muted" style="margin-bottom: 4px">
            访客申请用二维码进闸（60 秒动态码）
          </div>
          <el-checkbox v-model="reviewQrApproved">同意二维码开门</el-checkbox>
        </div>

        <div v-if="reviewDetail.actions.length" style="margin-top: 14px">
          <div class="text-muted" style="margin-bottom: 4px">审批轨迹</div>
          <div v-for="(a, i) in reviewDetail.actions" :key="i" class="text-muted" style="font-size: 12px">
            {{ a.actionText }} · {{ a.operatorType === 2 ? '门卫' : '被访人' }} · {{ a.at }}
            <span v-if="a.comment"> · {{ a.comment }}</span>
            <span v-if="a.gates && a.gates.length"> · 门点：{{ a.gates.join('、') }}</span>
          </div>
        </div>
      </template>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="danger" :loading="reviewActing" @click="doReject">拒绝放行</el-button>
        <el-button type="primary" :loading="reviewActing" @click="doPass">审核通过</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  guardCheckout,
  guardDashboard,
  guardOpenDoor,
  guardOnsite,
  guardPending,
  guardRemind,
  guardReviewCount,
  guardReviewDetail,
  guardReviewPass,
  guardReviewReject,
  guardReviewTodo,
  listGuardGates,
  verifyGuardQr
} from '@/api'
import type {
  GateOption,
  GuardDashboard,
  GuardReviewDetail,
  OnsiteRow,
  PendingRow,
  QrVerifyResult,
  ReviewRow
} from '@/api/types'

const tab = ref<'onsite' | 'pending' | 'review'>('onsite')
/** 人工开门的目标门点：多门点部署时保安必须先选门 */
const gates = ref<GateOption[]>([])
const gateId = ref<number | undefined>(undefined)
const dashboard = ref<GuardDashboard>({
  onsite: 0,
  pendingApproval: 0,
  devicesOnline: 0,
  devicesTotal: 0,
  todayCheckins: 0,
  deviceStatusText: '-'
})

const onsiteList = ref<OnsiteRow[]>([])
const onsiteTotal = ref(0)
const onsitePage = ref(1)
const onsiteSize = ref(20)
const onsiteLoading = ref(false)

const pendingList = ref<PendingRow[]>([])
const pendingTotal = ref(0)
const pendingPage = ref(1)
const pendingSize = ref(20)
const pendingLoading = ref(false)

async function loadDashboard() {
  dashboard.value = await guardDashboard()
}

async function loadOnsite() {
  onsiteLoading.value = true
  try {
    const page = await guardOnsite({ page: onsitePage.value, size: onsiteSize.value })
    onsiteList.value = page.list
    onsiteTotal.value = page.total
  } finally {
    onsiteLoading.value = false
  }
}

async function loadPending() {
  pendingLoading.value = true
  try {
    const page = await guardPending({ page: pendingPage.value, size: pendingSize.value })
    pendingList.value = page.list
    pendingTotal.value = page.total
  } finally {
    pendingLoading.value = false
  }
}

async function openDoor(row: OnsiteRow) {
  const gateName = gates.value.find((g) => g.id === gateId.value)?.gateName
  const { value } = await ElMessageBox.prompt(
    `为 ${row.visitorName}（${row.appointmentNo}）开门${gateName ? `（${gateName}）` : ''}，请填写原因`,
    '人工开门',
    { inputPlaceholder: '例如：二维码无法识别', inputValidator: (v) => (!!v && v.trim().length > 0) || '请填写原因' }
  )
  await guardOpenDoor({ appointmentNo: row.appointmentNo, gateId: gateId.value, reason: value })
  ElMessage.success('已发送开门指令')
}

async function checkout(row: OnsiteRow) {
  await ElMessageBox.confirm(`确认对 ${row.visitorName} 执行手工签离？`, '签离确认', { type: 'warning' })
  await guardCheckout(row.appointmentNo)
  ElMessage.success('已签离')
  await Promise.all([loadOnsite(), loadDashboard()])
}

async function remind(row: PendingRow) {
  await ElMessageBox.confirm(`确认催办 ${row.visitorName} 的审批？`, '催办', { type: 'info' })
  const result = await guardRemind(row.appointmentNo)
  ElMessage.success(`已催办，剩余 ${result.remaining} 次`)
}

const verifyVisible = ref(false)
const verifying = ref(false)
const verifyCode = ref('')
const verifyResult = ref<QrVerifyResult | null>(null)

function openVerify() {
  verifyCode.value = ''
  verifyResult.value = null
  verifyVisible.value = true
}

async function doVerify() {
  if (!verifyCode.value.trim()) {
    ElMessage.warning('请先扫描或粘贴二维码内容')
    return
  }
  verifying.value = true
  try {
    verifyResult.value = await verifyGuardQr(verifyCode.value.trim())
    if (verifyResult.value.allowed) {
      await Promise.all([loadOnsite(), loadDashboard()])
    }
  } finally {
    verifying.value = false
  }
}

/* ---------------- 待我审核（C-65） ---------------- */

const reviewList = ref<ReviewRow[]>([])
const reviewTotal = ref(0)
const reviewPage = ref(1)
const reviewSize = ref(20)
const reviewLoading = ref(false)
const reviewCount = ref(0)
const reviewVisible = ref(false)
const reviewActing = ref(false)
const reviewDetail = ref<GuardReviewDetail | null>(null)
const reviewTaskId = ref(0)
const reviewGateIds = ref<number[]>([])
const reviewComment = ref('')
/** 是否同意访客的「二维码开门」申请（仅当访客申请过时显示开关） */
const reviewQrApproved = ref(false)

/** 访客申请的开放范围（只读展示，用于对比审核时是否收窄） */
const appliedText = computed(() => {
  const g = reviewDetail.value?.gates
  if (!g) return '-'
  if (g.gateScope === 2) return '全部门点'
  const names = g.selectable.filter((o) => g.appliedGateIds.includes(o.id)).map((o) => o.name)
  return names.join('、') || '-'
})

async function loadReview() {
  reviewLoading.value = true
  try {
    const page = await guardReviewTodo({ page: reviewPage.value, size: reviewSize.value })
    reviewList.value = page.list
    reviewTotal.value = page.total
  } finally {
    reviewLoading.value = false
  }
  await loadReviewCount()
}

async function loadReviewCount() {
  try {
    reviewCount.value = (await guardReviewCount()).pending
  } catch {
    reviewCount.value = 0
  }
}

async function openReview(row: ReviewRow) {
  reviewTaskId.value = row.taskId
  reviewComment.value = ''
  const detail = await guardReviewDetail(row.taskId)
  reviewDetail.value = detail
  reviewGateIds.value = [...(detail.gates?.approvedGateIds ?? [])]
  // 访客申请了二维码就默认勾上「同意」——绝大多数场景都会同意，减少一次点击；
  // 已明确拒绝过（申请了但 approved=false）时也默认勾上，由审核人再决定是否取消。
  reviewQrApproved.value = Boolean(detail.qrRequested) || Boolean(detail.qrApproved)
  reviewVisible.value = true
}

async function doPass() {
  if (!reviewDetail.value) return
  if (reviewGateIds.value.length === 0) {
    ElMessage.warning('至少要保留一个开放门点')
    return
  }
  reviewActing.value = true
  try {
    const result = await guardReviewPass(reviewTaskId.value, {
      comment: reviewComment.value || undefined,
      approvedGateIds: reviewGateIds.value,
      qrApproved: reviewDetail.value.qrRequested ? reviewQrApproved.value : undefined
    })
    ElMessage.success(result.dispatchStarted ? '已通过，门禁授权下发中' : `已通过（${result.statusText}）`)
    reviewVisible.value = false
    await Promise.all([loadReview(), loadDashboard(), loadPending()])
  } finally {
    reviewActing.value = false
  }
}

async function doReject() {
  if (!reviewComment.value.trim()) {
    ElMessage.warning('拒绝放行必须填写原因')
    return
  }
  await ElMessageBox.confirm('拒绝后该预约单将直接驳回，确认？', '拒绝放行', { type: 'warning' })
  reviewActing.value = true
  try {
    await guardReviewReject(reviewTaskId.value, reviewComment.value.trim())
    ElMessage.success('已拒绝放行')
    reviewVisible.value = false
    await Promise.all([loadReview(), loadDashboard(), loadPending()])
  } finally {
    reviewActing.value = false
  }
}

onMounted(async () => {
  gates.value = await listGuardGates().catch(() => [])
  if (gateId.value === undefined && gates.value.length > 0) {
    gateId.value = gates.value[0].id
  }
  await Promise.all([loadDashboard(), loadOnsite(), loadPending(), loadReview()])
})
</script>
