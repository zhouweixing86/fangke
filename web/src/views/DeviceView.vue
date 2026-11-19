<template>
  <div class="page-card">
    <div class="toolbar">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-select v-model="filterGateId" clearable placeholder="全部门点" style="width: 180px"
        @change="load">
        <el-option v-for="g in gates" :key="g.id" :value="g.id" :label="g.gateName" />
      </el-select>
      <span class="spacer" />
      <span class="text-muted">设备参数以「实时回读」为准，界面值可能是本地缓存</span>
    </div>

    <el-table v-loading="loading" :data="devices" border stripe>
      <el-table-column prop="deviceName" label="名称" width="140" />
      <el-table-column label="门点" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.gateName === '待分配'" size="small" type="warning">{{ row.gateName }}</el-tag>
          <span v-else>{{ row.gateName || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="directionText" label="方向" width="80" />
      <el-table-column prop="deviceSn" label="SN" width="160" />
      <el-table-column prop="ip" label="IP" width="130" />
      <el-table-column prop="model" label="型号" width="100" />
      <el-table-column prop="firmware" label="固件" min-width="220" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.online ? 'success' : 'danger'" size="small">
            {{ row.online ? '在线' : '离线' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="人数/容量" width="110">
        <template #default="{ row }">{{ row.personCount }} / {{ row.capacity || '-' }}</template>
      </el-table-column>
      <el-table-column prop="lastHeartbeat" label="最近心跳" width="160" />
      <el-table-column label="操作" width="380" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">档案</el-button>
          <el-button link type="primary" @click="readParams(row)">参数</el-button>
          <el-button link type="primary" @click="openDoor(row)">开门</el-button>
          <el-button link type="primary" @click="showMessage(row)">提示</el-button>
          <el-button link type="warning" @click="resync(row)">对账</el-button>
          <el-button link type="success" @click="selfCheck(row)">自检</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editVisible" title="设备档案" width="520px">
      <el-alert v-if="editingRow?.gateName === '待分配'" type="warning" :closable="false" show-icon
        style="margin-bottom: 12px"
        title="这台设备还在「待分配」门点，分配到真实门点后才会收到该门点的访客名单" />
      <el-form :model="editForm" label-width="100px">
        <el-form-item label="设备名称">
          <el-input v-model="editForm.deviceName" maxlength="64" placeholder="如 A 栋东门人脸终端" />
        </el-form-item>
        <el-form-item label="所属门点" required>
          <el-select v-model="editForm.gateId" style="width: 100%">
            <el-option v-for="g in gates" :key="g.id" :value="g.id"
              :label="g.gateName + (g.unassigned ? '（待分配）' : '')" />
          </el-select>
        </el-form-item>
        <el-form-item label="出入口方向">
          <el-radio-group v-model="editForm.direction">
            <el-radio :value="1">入口</el-radio>
            <el-radio :value="0">出口</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="型号">
          <el-input v-model="editForm.model" maxlength="64" placeholder="如 Linux 人脸识别终端" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editForm.remark" type="textarea" :rows="2" maxlength="255" show-word-limit
            placeholder="现场位置说明，便于运维定位" />
        </el-form-item>
        <el-form-item label="SN / MAC / IP">
          <span class="text-muted">{{ editingRow?.deviceSn }} / {{ editingRow?.mac }} /
            {{ editingRow?.ip || '-' }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="paramsVisible" title="设备参数（实时回读）" width="760px">
      <el-alert v-if="paramsHint" :title="paramsHint" type="info" :closable="false" show-icon
        style="margin-bottom: 8px" />
      <el-input v-model="paramsText" type="textarea" :rows="20" spellcheck="false" />
      <div class="text-muted" style="margin-top: 8px">
        修改后点击「下发」：系统会先取全量参数再合并目标字段，避免未传字段被重置。
      </div>
      <template #footer>
        <el-button @click="paramsVisible = false">关闭</el-button>
        <el-button type="primary" :loading="saving" @click="saveParams">下发</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="checkVisible" title="设备接入自检" width="820px">
      <template v-if="checkResult">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="设备">
            {{ checkResult.deviceSn }}（{{ checkResult.model || '未知型号' }}）
          </el-descriptions-item>
          <el-descriptions-item label="通道">{{ checkResult.channel }}</el-descriptions-item>
          <el-descriptions-item label="固件">{{ checkResult.firmware || '-' }}</el-descriptions-item>
          <el-descriptions-item label="自检时间">{{ checkResult.checkedAt }}</el-descriptions-item>
          <el-descriptions-item label="结论" :span="2">
            <el-tag :type="checkTagType(checkResult.status)" size="small">
              {{ checkStatusText(checkResult.status) }}
            </el-tag>
            <span class="text-muted" style="margin-left: 8px">
              OK 可用、WARN 需人工确认、FAIL 当前不可用
            </span>
          </el-descriptions-item>
        </el-descriptions>

        <el-table :data="checkResult.items" border size="small" style="margin-top: 12px">
          <el-table-column prop="label" label="检查项" width="120" />
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag :type="checkTagType(row.status)" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="说明" min-width="320" />
        </el-table>

        <div class="text-muted" style="margin: 16px 0 6px">能力矩阵（适配器声明 ∩ 设备档案覆盖）</div>
        <div class="cap-grid">
          <el-tag v-for="cap in checkResult.capabilities" :key="cap.name" size="small"
            :type="cap.supported ? 'success' : 'info'" effect="light">
            {{ cap.label }}
          </el-tag>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  listDevices,
  listGates,
  openDeviceDoor,
  readDeviceParams,
  resyncDevice,
  selfCheckDevice,
  showDeviceMessage,
  updateDevice,
  writeDeviceParams
} from '@/api'
import type { DeviceView, GateRow, SelfCheckResult } from '@/api/types'

const route = useRoute()

const loading = ref(false)
const devices = ref<DeviceView[]>([])
const gates = ref<GateRow[]>([])
const filterGateId = ref<number | undefined>(
  route.query.gateId ? Number(route.query.gateId) : undefined
)

const checkVisible = ref(false)
const checkResult = ref<SelfCheckResult | null>(null)

const editVisible = ref(false)
const editSaving = ref(false)
const editingRow = ref<DeviceView | null>(null)
const editForm = ref({ deviceName: '', model: '', direction: 1, gateId: 0, remark: '' })

const paramsVisible = ref(false)
const paramsText = ref('')
const paramsHint = ref('')
const saving = ref(false)
const currentDevice = ref<DeviceView | null>(null)
let originalParams: Record<string, unknown> = {}

async function load() {
  loading.value = true
  try {
    devices.value = await listDevices(filterGateId.value)
  } finally {
    loading.value = false
  }
}

async function loadGates() {
  gates.value = await listGates()
}

function openEdit(row: DeviceView) {
  editingRow.value = row
  editForm.value = {
    deviceName: row.deviceName,
    model: row.model,
    direction: row.direction,
    gateId: row.gateId,
    remark: row.remark
  }
  editVisible.value = true
}

async function submitEdit() {
  if (!editingRow.value) return
  if (!editForm.value.gateId) {
    ElMessage.warning('请选择所属门点')
    return
  }
  editSaving.value = true
  try {
    await updateDevice(editingRow.value.id, editForm.value)
    ElMessage.success('设备档案已保存')
    editVisible.value = false
    await load()
  } finally {
    editSaving.value = false
  }
}

async function readParams(row: DeviceView) {
  currentDevice.value = row
  const data = await readDeviceParams(row.id)
  originalParams = data
  paramsText.value = JSON.stringify(data, null, 2)
  paramsHint.value = `设备 ${row.deviceName}（${row.deviceSn}）· 回读时间 ${new Date().toLocaleString()}`
  paramsVisible.value = true
}

async function saveParams() {
  if (!currentDevice.value) return
  let parsed: Record<string, unknown>
  try {
    parsed = JSON.parse(paramsText.value)
  } catch {
    ElMessage.error('参数必须是合法 JSON')
    return
  }
  // 只提交发生变化的字段，避免误改
  const changed: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(parsed)) {
    if (JSON.stringify(value) !== JSON.stringify(originalParams[key])) {
      changed[key] = value
    }
  }
  if (Object.keys(changed).length === 0) {
    ElMessage.info('没有检测到改动')
    return
  }
  await ElMessageBox.confirm(
    `将下发 ${Object.keys(changed).length} 个字段：${Object.keys(changed).join(', ')}`,
    '确认下发',
    { type: 'warning' }
  )
  saving.value = true
  try {
    await writeDeviceParams(currentDevice.value.id, changed)
    ElMessage.success('已下发')
    paramsVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function openDoor(row: DeviceView) {
  await ElMessageBox.confirm(`确认远程打开「${row.deviceName}」？`, '远程开门', { type: 'warning' })
  await openDeviceDoor(row.id)
  ElMessage.success('开门指令已发送')
}

async function showMessage(row: DeviceView) {
  const { value } = await ElMessageBox.prompt('屏幕提示（最多 14 个汉字）', '设备提示', {
    inputValidator: (v) => (!!v && v.trim().length > 0 && v.length <= 14) || '请输入 1~14 个汉字'
  })
  await showDeviceMessage(row.id, value)
  ElMessage.success('已发送')
}

async function resync(row: DeviceView) {
  await ElMessageBox.confirm(
    '对账会以平台授权为准：设备上多出的人员将被删除，缺失的会被记录。确认执行？',
    '名单对账',
    { type: 'warning' }
  )
  const result = await resyncDevice(row.id)
  ElMessageBox.alert(
    `期望 ${result.expectedCount} 人，设备实际 ${result.actualCount} 人\n` +
      `缺失：${result.missingInDevice.length} 人（${result.missingInDevice.slice(0, 5).join(', ') || '无'}）\n` +
      `多余：${result.extraInDevice.length} 人，已删除 ${result.removed} 人，失败 ${result.failedRemoval} 人`,
    '对账结果',
    { type: 'info' }
  )
}

async function selfCheck(row: DeviceView) {
  checkResult.value = await selfCheckDevice(row.id)
  checkVisible.value = true
}

function checkTagType(status: string) {
  if (status === 'OK') return 'success'
  if (status === 'WARN') return 'warning'
  return 'danger'
}

function checkStatusText(status: string) {
  if (status === 'OK') return '可用'
  if (status === 'WARN') return '需人工确认'
  return '当前不可用'
}

onMounted(async () => {
  await loadGates()
  await load()
})
</script>

<style scoped>
.cap-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.cap-grid .el-tag {
  margin: 0;
}
</style>
