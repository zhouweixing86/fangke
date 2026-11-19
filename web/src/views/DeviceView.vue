<template>
  <div class="page-card">
    <div class="toolbar">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-select v-model="filterGateId" clearable placeholder="全部门点" style="width: 180px"
        @change="load">
        <el-option v-for="g in gates" :key="g.id" :value="g.id" :label="g.gateName" />
      </el-select>
      <span class="spacer" />
      <span class="text-muted">
        轮询式终端：名单与开门都通过设备心跳下发，不支持平台读参数/对账/自检（请在设备屏幕操作）
      </span>
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
      <el-table-column prop="model" label="型号" width="140" />
      <el-table-column label="通道" width="90">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ row.channel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.online ? 'success' : 'danger'" size="small">
            {{ row.online ? '在线' : '离线' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="人数" width="100">
        <template #default="{ row }">{{ row.personCount }} / {{ row.capacity || '-' }}</template>
      </el-table-column>
      <el-table-column prop="lastHeartbeat" label="最近心跳" width="160" />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">档案</el-button>
          <el-button link type="primary" @click="openDoor(row)">开门</el-button>
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
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { listDevices, listGates, openDeviceDoor, updateDevice } from '@/api'
import type { DeviceView, GateRow } from '@/api/types'

const route = useRoute()

const loading = ref(false)
const devices = ref<DeviceView[]>([])
const gates = ref<GateRow[]>([])
const filterGateId = ref<number | undefined>(
  route.query.gateId ? Number(route.query.gateId) : undefined
)

const editVisible = ref(false)
const editSaving = ref(false)
const editingRow = ref<DeviceView | null>(null)
const editForm = ref({ deviceName: '', model: '', direction: 1, gateId: 0, remark: '' })

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

async function openDoor(row: DeviceView) {
  await ElMessageBox.confirm(
    `确认远程打开「${row.deviceName}」？指令随设备下一次心跳生效（通常 30 秒内）。`,
    '远程开门',
    { type: 'warning' }
  )
  await openDeviceDoor(row.id)
  ElMessage.success('开门指令已排队')
}

onMounted(async () => {
  await loadGates()
  await load()
})
</script>
